package com.lossydragon.modplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Media3 [SimpleBasePlayer] backed by [PlayerEngine].
 */
@OptIn(UnstableApi::class)
class ModPlayer(
    private val context: Context,
    private val engine: PlayerEngine,
    private val prefs: AppPreferences
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** Shuffle and repeat settings that persist across queue reloads. */
    private var repeatMode = REPEAT_MODE_OFF
    private var shuffleMode = false

    /** Current playback order; rebuilt by [applyQueueOrder]. */
    private val queue = mutableListOf<ModuleEntity>()

    /** Original (pre-shuffle) order, used to restore sequential play and persist state. */
    private val originalQueue = mutableListOf<ModuleEntity>()

    /** Mirror of [queue] as [MediaItem]s for [getState]; the current item has real metadata. */
    private val playlist = mutableListOf<MediaItem>()

    private var currentIndex = 0

    /** +1 when navigating forward, -1 when navigating backward. Used by [skipUnplayable]. */
    private var lastNavDirection: Int = 1

    /** Counts consecutive skip attempts so we don't loop forever on an unplayable queue. */
    private var skipAttempts: Int = 0

    /** True while [loadAndStartAt] is running a background [Thread]. */
    @Volatile
    private var isLoading = false

    /**
     * Desired seek target while a seek is in-flight. Held until the engine
     * position converges, then reset to -1. Guards against position flicker.
     */
    @Volatile
    private var pendingSeekPositionMs: Long = -1L

    /**
     * True while shuffle is being toggled. Prevents [currentIndexFlow] from
     * triggering a metadata reload mid-reorder.
     */
    @Volatile
    var isReordering = false

    /** Per-frame snapshots from the render thread; null when idle. */
    val frameInfoFlow by engine::frameInfoFlow

    /** True while the engine render loop is running. */
    val isPlaying by engine::isPlaying

    /** Current multi-sequence index, emitted on sequence change. */
    val currentSequenceFlow by engine::currentSequenceFlow

    /** Module metadata, emitted once after each successful load. */
    val modVarsFlow by engine::modVarsFlow

    /** Index of the currently playing item in [queue]; emitted on navigation. */
    val currentIndexFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    /** Snapshot of the current playback queue; emitted when the queue changes. */
    val queueFlow: StateFlow<List<ModuleEntity>>
        field = MutableStateFlow(emptyList())

    /** Incremented each time a new module finishes loading; triggers metadata sync. */
    val moduleLoadedFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    private val audioFocus = AudioFocusHandler(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionUpdateJob: Job? = null

    /** Placeholder Artwork */
    private fun drawableUri(resId: Int): Uri =
        "android.resource://${context.packageName}/$resId".toUri()

    init {
        scope.launch {
            engine.isPlaying.collect { playing ->
                Timber.i(
                    "isPlaying collector: playing=$playing " +
                        "endedNaturally=${engine.endedNaturally} idx=$currentIndex/${queue.size}"
                )
                invalidateState()
                if (playing) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    // When the engine ends naturally, advance the queue on the main thread.
                    if (engine.endedNaturally) mainHandler.post { advanceToNext() }
                }
            }
        }
    }

    /* TODO kDoc */
    fun isRepeatingOne(value: Boolean) = engine.isRepeatingOne(value)

    /** Wraps [engine.setSequence] updates duration and position flows on success. */
    fun setSequence(index: Int): Boolean = engine.setSequence(index)

    /** Sets [engine.playAllSequences] to play all sequences or not */
    fun setPlayAllSequences(value: Boolean) {
        engine.playAllSequences = value
    }

    /** Loads [files] into the queue and starts playback at [startAt]. */
    fun loadQueue(
        files: List<ModuleEntity>,
        startAt: Int,
        shuffleMode: Boolean = this.shuffleMode,
        repeatMode: Int = this.repeatMode
    ) {
        this.shuffleMode = shuffleMode
        this.repeatMode = repeatMode
        isRepeatingOne(this.repeatMode == REPEAT_MODE_ONE)

        requestAudioFocus()

        originalQueue.clear()
        originalQueue.addAll(files)
        applyQueueOrder(startAt)
        invalidateState()
        loadAndStartAt(currentIndex)
        persistQueue()
    }

    /** Advances to the next item (or loops/stops depending on repeat mode). */
    fun next() = advanceToNext()

    /**
     * Goes to the previous item, or seeks to the start of the current track
     * if more than 3 seconds have elapsed.
     */
    fun previous() {
        if (engine.positionMs.value > 3_000L) {
            pendingSeekPositionMs = 0L
            engine.seek(0)
            invalidateState()
        } else {
            advanceToPrevious()
        }
    }

    /** Jumps directly to [index] in the current queue. */
    fun jumpToIndex(index: Int) {
        if (index in queue.indices) {
            lastNavDirection = if (index >= currentIndex) 1 else -1
            navigate(index)
        }
    }

    /** Requests / re-requests audio focus from the system. */
    fun requestAudioFocus() = audioFocus.request(
        onGain = { engine.resume() },
        onLoss = { engine.pause() }
    )

    /** Releases audio focus. Called from [PlayerService.onDestroy]. */
    fun abandonAudioFocus() = audioFocus.abandon()

    /** Returns cached pattern data for the pattern view. */
    fun getPatternData(patternIndex: Int) = engine.getPatternData(patternIndex)

    /**
     * Builds [queue] and [playlist] from [originalQueue], applying shuffle if enabled.
     * [startAt] is the desired starting index within [originalQueue].
     */
    private fun applyQueueOrder(startAt: Int = 0) {
        Timber.d(
            "applyQueueOrder shuffleModeEnabled=$shuffleMode " +
                "startAt=$startAt originalQueue.size=${originalQueue.size}"
        )

        queue.clear()
        playlist.clear()

        if (shuffleMode) {
            // Place the selected track first, then shuffle the rest.
            val shuffled = originalQueue.toMutableList()
            val first = shuffled.removeAt(startAt.coerceIn(0, shuffled.lastIndex))
            shuffled.shuffle()
            queue.add(first)
            queue.addAll(shuffled)
            currentIndex = 0
        } else {
            queue.addAll(originalQueue)
            currentIndex = startAt.coerceIn(0, queue.lastIndex)
        }

        playlist.addAll(queue.map { it.toMediaItem() })
        queueFlow.value = queue.toList()
        currentIndexFlow.value = currentIndex
        invalidateState()
    }

    /** Clears all queue state and notifies observers. */
    private fun clearQueue() {
        playlist.clear()
        queue.clear()
        originalQueue.clear()
        currentIndex = 0
        currentIndexFlow.value = 0
        queueFlow.value = emptyList()
        persistQueue()
        invalidateState()
    }

    /**
     * Loads the module at [index] on a background thread.
     *
     * @param seekToMs Optional position to seek to after loading.
     * @param autoStart Whether to begin playback immediately after loading.
     */
    private fun loadAndStartAt(index: Int, seekToMs: Long? = null, autoStart: Boolean = true) {
        val file = queue.getOrNull(index) ?: return
        if (isLoading) return
        isLoading = true

        Thread {
            Timber.d("loadAndStartAt: loading index=$index autoStart=$autoStart file=${file.name}")
            if (engine.loadNext(file)) {
                Timber.d("loadAndStartAt: loadNext succeeded, autoStart=$autoStart")

                // todo sucks
                // Replace the placeholder item with real metadata from libxmp.
                val duration = engine.modVarsFlow.value.sequenceDuration(currentSequenceFlow.value)
                val realItem = MediaItem.Builder()
                    .setUri(file.uri)
                    .setMediaId(file.filePath)
                    .setMediaMetadata(file.toRealMetadata(duration.toLong()))
                    .build()

                mainHandler.post {
                    playlist[index] = realItem
                    moduleLoadedFlow.value++
                    invalidateState()
                }

                if (autoStart) {
                    Timber.d("loadAndStartAt: calling engine.start()")
                    engine.start()
                    if (seekToMs != null && seekToMs > 0L) {
                        engine.seek(seekToMs.toInt())
                        pendingSeekPositionMs = seekToMs
                    }
                }

                mainHandler.post(::invalidateState)
            } else {
                mainHandler.post(::skipUnplayable)
            }
            isLoading = false
        }.start()
    }

    /**
     * Skips the current unplayable file, continuing in [lastNavDirection].
     * Clears the queue if every item fails to load.
     */
    private fun skipUnplayable() {
        skipAttempts++
        if (skipAttempts >= queue.size) {
            skipAttempts = 0
            clearQueue()
            return
        }
        if (lastNavDirection < 0) advanceToPrevious() else advanceToNext()
    }

    /** Navigates to [to], loads and starts the module there. */
    private fun navigate(to: Int) {
        currentIndex = to
        currentIndexFlow.value = currentIndex
        pendingSeekPositionMs = -1L
        invalidateState()
        loadAndStartAt(currentIndex)
        persistQueue()
    }

    /** Advances forward according to the current [repeatMode]. */
    private fun advanceToNext() {
        lastNavDirection = 1
        when {
            repeatMode == REPEAT_MODE_ONE -> navigate(currentIndex)

            repeatMode == REPEAT_MODE_ALL -> {
                val idx = if (currentIndex + 1 < queue.size) currentIndex + 1 else 0
                navigate(idx)
            }

            currentIndex + 1 < queue.size -> navigate(currentIndex + 1)

            else -> clearQueue()
        }
    }

    /** Advances backward according to the current [repeatMode]. */
    private fun advanceToPrevious() {
        lastNavDirection = -1
        when {
            repeatMode == REPEAT_MODE_ONE -> navigate(currentIndex)

            repeatMode == REPEAT_MODE_ALL -> {
                val idx = if (currentIndex - 1 >= 0) currentIndex - 1 else queue.lastIndex
                navigate(idx)
            }

            currentIndex - 1 >= 0 -> navigate(currentIndex - 1)

            else -> navigate(0)
        }
    }

    /** Starts the 500 ms position-update loop, also flushing queue state every 5 s. */
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var lastPersist = 0L
            while (true) {
                delay(500L.milliseconds)
                // Clear a pending seek once the engine position has converged.
                if (pendingSeekPositionMs >= 0 &&
                    abs(engine.positionMs.value - pendingSeekPositionMs) < 2_000L
                ) {
                    pendingSeekPositionMs = -1L
                }
                invalidateState()
                val now = System.currentTimeMillis()
                if (now - lastPersist > 5_000) {
                    persistQueue()
                    lastPersist = now
                }
            }
        }
    }

    /** Cancels the position-update loop. */
    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Constructs the immutable player state snapshot that Media3 uses for
     * notification metadata, transport controls, and session state.
     */
    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_METADATA,
            COMMAND_GET_TIMELINE,
            COMMAND_STOP,
            COMMAND_PREPARE,
            COMMAND_SET_MEDIA_ITEM,
            COMMAND_CHANGE_MEDIA_ITEMS,
            COMMAND_SET_REPEAT_MODE,
            COMMAND_SET_SHUFFLE_MODE,
        ).build()

        val playlistItems = playlist.mapIndexed { i, item ->
            val uid = item.mediaId.ifEmpty {
                item.localConfiguration?.uri?.toString()
                    ?: i.toString()
            }

            val duration = engine.modVarsFlow.value.sequenceDuration(
                engine.currentSequenceFlow.value
            )
            val durationMs = duration
            val durationUs = if (i == currentIndex && durationMs > 0) {
                durationMs * 1_000L
            } else {
                C.TIME_UNSET
            }
            MediaItemData.Builder(uid)
                .setMediaItem(item)
                .setIsSeekable(true)
                .setDurationUs(durationUs)
                .build()
        }

        val position = if (pendingSeekPositionMs >= 0) {
            pendingSeekPositionMs
        } else {
            engine.positionMs.value
        }
        val playbackState = when {
            playlist.isEmpty() -> STATE_IDLE
            engine.endedNaturally -> STATE_ENDED
            else -> STATE_READY
        }

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlistItems)
            .setShuffleModeEnabled(shuffleMode)
            .setRepeatMode(repeatMode)
            .setCurrentMediaItemIndex(currentIndex)
            .setPlayWhenReady(engine.isPlaying.value, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setContentPositionMs(position)
            .build()
    }

    /** Starts or resumes the engine, or loads the current item if not yet initialised. */
    @SuppressLint("BinaryOperationInTimber")
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Timber.d(
            "handleSetPlayWhenReady: playWhenReady=$playWhenReady " +
                "isPlaying=${engine.isPlaying.value} initialized=${engine.initialized} " +
                "isLoading=$isLoading queue=${queue.size}"
        )
        if (!playWhenReady) {
            engine.pause()
        } else if (!engine.isPlaying.value) {
            requestAudioFocus()
            if (!engine.initialized) {
                if (!isLoading) loadAndStartAt(currentIndex)
            } else {
                when {
                    engine.paused -> engine.resume()
                    !engine.endedNaturally -> engine.start()
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Stores the new repeat mode and persists it with the queue. */
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        isRepeatingOne(this.repeatMode == REPEAT_MODE_ONE)
        invalidateState()
        persistQueue()
        return Futures.immediateVoidFuture()
    }

    /**
     * Toggles shuffle mode.
     * Rebuilds [queue] so the currently-playing track stays at the front,
     * preserving the real metadata item across the reorder.
     */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        this.shuffleMode = shuffleModeEnabled

        if (queue.isEmpty()) {
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        isReordering = true

        val currentFile = queue.getOrNull(currentIndex)
        val currentRealItem = playlist.getOrNull(currentIndex)

        queue.clear()
        playlist.clear()

        if (shuffleModeEnabled) {
            val remaining = originalQueue.toMutableList()
            currentFile?.let { remaining.remove(it) }
            remaining.shuffle()
            currentFile?.let { queue.add(it) }
            queue.addAll(remaining)
            currentIndex = 0
        } else {
            queue.addAll(originalQueue)
            currentIndex = currentFile
                ?.let { originalQueue.indexOf(it) }
                ?.coerceAtLeast(0)
                ?: 0
        }

        playlist.addAll(queue.map { it.toMediaItem() })

        // Restore real metadata for the current track - Xmp metadata belongs to
        // the item that was loaded, not to whatever is currently at this index.
        if (currentRealItem != null) playlist[currentIndex] = currentRealItem

        currentIndexFlow.value = currentIndex
        queueFlow.value = queue.toList()

        isReordering = false
        invalidateState()
        persistQueue()

        return Futures.immediateVoidFuture()
    }

    /**
     * Routes seek commands: next/previous navigate the queue; all other commands
     * seek within the current item.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> advanceToNext()

            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> previous()

            else -> {
                pendingSeekPositionMs = positionMs
                engine.seek(positionMs.toInt())
                invalidateState()
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Stops the engine and clears the queue. */
    override fun handleStop(): ListenableFuture<*> {
        Thread { engine.stop() }.start()
        clearQueue()
        return Futures.immediateVoidFuture()
    }

    /**
     * Accepts a pre-resolved item list from [AutoBrowseCallback] (or playback resumption)
     * and loads the queue starting at [startIndex].
     *
     * Queue expansion (sibling scanning) is done upstream in [AutoBrowseCallback.onSetMediaItems]
     * so this handler simply trusts the list it receives.
     */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val files = mediaItems.mapNotNull { item ->
            val uri = item.localConfiguration?.uri ?: return@mapNotNull null
            ModuleEntity(
                filePath = uri.toString(),
                filename = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: "Unknown",
                fileSize = 0L,
                fileExtension = uri.lastPathSegment?.substringAfterLast('.', "") ?: "",
            )
        }
        if (files.isEmpty()) return Futures.immediateVoidFuture()
        loadQueue(files = files, startAt = startIndex.coerceIn(0, files.lastIndex))
        return Futures.immediateVoidFuture()
    }

    /**
     * Flushes the queue state synchronously, cancels the coroutine scope, and
     * stops the engine. Called from [PlayerService.onDestroy].
     */
    fun releaseEngine() {
        if (originalQueue.isNotEmpty()) {
            val saveIndex = if (shuffleMode) {
                queue.getOrNull(currentIndex)
                    ?.let { originalQueue.indexOf(it) }
                    ?.coerceAtLeast(0) ?: 0
            } else {
                currentIndex
            }
            runBlocking {
                prefs.saveQueueState(
                    json = Json.encodeToString(originalQueue.toList()),
                    index = saveIndex,
                    shuffle = shuffleMode,
                    repeat = repeatMode,
                    positionMs = engine.positionMs.value,
                )
            }
        }
        scope.cancel("Releasing Engine")
        Thread { engine.release() }.start()
    }

    /**
     * Saves the current queue and playback position to [AppPreferences] so it
     * can be restored after process death.
     */
    private fun persistQueue() {
        scope.launch {
            if (originalQueue.isEmpty()) {
                prefs.clearQueueState()
                return@launch
            }
            val saveIndex = if (shuffleMode) {
                queue.getOrNull(currentIndex)
                    ?.let { originalQueue.indexOf(it) }
                    ?.coerceAtLeast(0)
                    ?: 0
            } else {
                currentIndex
            }
            prefs.saveQueueState(
                json = Json.encodeToString(originalQueue.toList()),
                index = saveIndex,
                shuffle = shuffleMode,
                repeat = repeatMode,
                positionMs = engine.positionMs.value,
            )
        }
    }

    /**
     * Restores the persisted queue state. Calls [loadAndStartAt] with autoStart=false
     * unless [AppPreferences.getAutoResume] is enabled. Called on service start.
     * @return `true` if a non-empty queue was successfully restored.
     */
    suspend fun restoreQueue(): Boolean {
        val state = prefs.getQueueState() ?: return false
        val files = try {
            Json.decodeFromString<List<ModuleEntity>>(state.json)
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore queue")
            prefs.clearQueueState()
            return false
        }

        if (files.isEmpty()) return false

        if (originalQueue.isNotEmpty()) return false

        originalQueue.clear()
        originalQueue.addAll(files)
        this.shuffleMode = state.shuffle
        this.repeatMode = state.repeat
        isRepeatingOne(this.repeatMode == REPEAT_MODE_ONE)
        applyQueueOrder(startAt = state.index)

        if (prefs.getAutoResume()) {
            loadAndStartAt(index = currentIndex, seekToMs = state.positionMs.takeIf { it > 0L })
        } else {
            loadAndStartAt(index = currentIndex, autoStart = false)
        }

        invalidateState()
        return true
    }

    /**
     * Builds a [MediaItem] with placeholder metadata for initial queue population.
     * Real metadata (loaded from libxmp) is injected by [loadAndStartAt] later.
     */
    private fun ModuleEntity.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(filePath)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(type)
                .setArtworkUri(drawableUri(R.drawable.aa_album_art))
                .setIsPlayable(true)
                .build()
        )
        .build()

    /** Builds [MediaMetadata] from libxmp after the module has been loaded successfully. */
    private fun ModuleEntity.toRealMetadata(duration: Long): MediaMetadata {
        val name = modVarsFlow.value.modName.ifBlank { filename }
        val type = modVarsFlow.value.modType.ifBlank { fileExtension.uppercase() }
        return MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(type)
            .setDurationMs(duration)
            .setArtworkUri(drawableUri(R.drawable.aa_album_art))
            .setIsPlayable(true)
            .build()
    }
}
