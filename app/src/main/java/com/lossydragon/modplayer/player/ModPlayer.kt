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
import com.lossydragon.modplayer.db.PlaybackState
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.native.model.AudioStats
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Media3 [SimpleBasePlayer] backed by [PlayerEngine]. Queue decisions live in
 * [QueueController]; this class carries them out against the engine and Media3.
 */
@OptIn(UnstableApi::class)
class ModPlayer(
    private val context: Context,
    private val engine: Engine,
    private val prefs: AppPreferences
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val queue = QueueController<ModuleEntity>()

    /** [QueueController.items] as [MediaItem]s for [getState]; the current one has real metadata. */
    private val playlist = mutableListOf<MediaItem>()

    /** Guards [isLoading] and [pendingLoad]. */
    private val loadLock = Any()

    /** True while [loadAndStartAt] is running a background [Thread]. */
    @Volatile
    private var isLoading = false

    /** Newest load requested while [isLoading]; processed when the current load finishes. */
    private var pendingLoad: LoadRequest? = null

    /** True when the user explicitly paused; prevents audio-focus regain from auto-resuming. */
    @Volatile
    private var userPaused = false

    /**
     * Desired seek target while a seek is in-flight. Held until the engine
     * position converges, then reset to -1. Guards against position flicker.
     */
    @Volatile
    private var pendingSeekPositionMs = -1L

    /**
     * True while shuffle is being toggled. Prevents [currentIndexFlow] from
     * triggering a metadata reload mid-reorder.
     */
    @Volatile
    var isReordering = false

    /** Per-frame snapshots from the render thread. */
    val frameInfoFlow by engine::frameInfoFlow

    /** True while the engine render loop is running. */
    val isPlaying by engine::isPlaying

    /** Current multi-sequence index, emitted on sequence change. */
    val currentSequenceFlow by engine::currentSequenceFlow

    /** Module metadata, emitted once after each successful load. */
    val modVarsFlow by engine::modVarsFlow

    /** Index of the currently playing item in the queue; emitted on navigation. */
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

    init {
        scope.launch {
            prefs.getGlobalSubSongsFlow().collect { value ->
                engine.playAllSequences = value
            }
        }

        scope.launch {
            engine.isPlaying.collect { playing ->
                Timber.i(
                    "isPlaying collector: playing=$playing " +
                        "endedNaturally=${engine.endedNaturally} idx=${queue.currentIndex}/${queue.size}"
                )
                invalidateState()
                if (playing) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    // When the engine ends naturally, advance the queue on the main thread.
                    if (engine.endedNaturally) mainHandler.post { perform(queue.next()) }
                }
            }
        }

        scope.launch {
            engine.audioDisconnectedFlow.collect { disconnected ->
                if (!disconnected) return@collect
                Timber.w("Audio device disconnected — reopening stream and reloading current track")
                val resumePositionMs = engine.positionMs.value
                stopPositionUpdates()
                invalidateState()
                val resume = !userPaused
                Thread {
                    engine.handleAudioDisconnect()
                    if (!queue.isEmpty) {
                        mainHandler.post {
                            // Reopen on the new route; only keep playing if the user hadn't paused.
                            loadAndStartAt(
                                index = queue.currentIndex,
                                seekToMs = resumePositionMs,
                                autoStart = resume,
                            )
                        }
                    }
                }.start()
            }
        }
    }

    /** Enables or disables single-track repeat in the engine. */
    fun isRepeatingOne(value: Boolean) = engine.isRepeatingOne(value)

    /** Switches to sequence [index]; updates duration and position flows on success. */
    fun setSequence(index: Int): Boolean = engine.setSequence(index)

    /** Enables or disables automatic playback of all module sequences. */
    fun setPlayAllSequences(value: Boolean) {
        engine.playAllSequences = value
    }

    /**
     * Loads [files] into the queue and starts playback at [startAt],
     * seeking to [startPositionMs] once the module is loaded if it is > 0.
     */
    fun loadQueue(
        files: List<ModuleEntity>,
        startAt: Int,
        shuffleMode: Boolean = queue.shuffle,
        repeatMode: Int = queue.repeatMode.toMedia3(),
        startPositionMs: Long = 0L
    ) {
        val repeat = repeatModeFromMedia3(repeatMode)
        isRepeatingOne(repeat == RepeatMode.ONE)
        userPaused = false

        requestAudioFocus()

        val step = queue.load(files, startAt, shuffle = shuffleMode, repeatMode = repeat)
        syncPlaylist()
        perform(step, startPositionMs)
        persistQueueList()
    }

    /** Advances to the next item (or loops/parks depending on repeat mode). */
    fun next() = perform(queue.next())

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
            perform(queue.previous())
        }
    }

    /** Jumps directly to [index] in the current queue, optionally starting at [startPositionMs]. */
    fun jumpToIndex(index: Int, startPositionMs: Long = 0L) =
        perform(queue.jumpTo(index), startPositionMs)

    /**
     * Requests / re-requests audio focus from the system. Must run on every play
     * request: after a permanent AUDIOFOCUS_LOSS the system never hands focus back on
     * its own, so the user pressing play is what gets it (and playback) back.
     */
    fun requestAudioFocus() = audioFocus.request(
        onGain = {
            if (!userPaused) {
                when {
                    engine.paused -> engine.resume()

                    // Delayed-focus case: engine loaded and waiting to start (not paused, not playing).
                    engine.initialized && !engine.isPlaying.value && !engine.endedNaturally ->
                        Thread { engine.start() }.start()
                }
            }
        },
        onLoss = { engine.pause() },
        onDuck = engine::setDucked,
    )

    /** Releases audio focus. Called from [PlayerService.onDestroy]. */
    fun abandonAudioFocus() = audioFocus.abandon()

    /** Returns cached pattern data for the pattern view. */
    fun getPatternData(patternIndex: Int) = engine.getPatternData(patternIndex)

    /** Fills [stats] with current Oboe stream statistics. */
    fun getAudioStats(stats: AudioStats) = engine.getAudioStats(stats)

    /**
     * Flushes the playback position and tears down the engine. Called from
     * [PlayerService.onDestroy].
     *
     * This player is a process-wide singleton shared by the UI and every
     * [PlayerService] instance, so neither [scope] nor [SimpleBasePlayer.release] are
     * touched here: the collectors in `init` must keep running for the next service
     * instance (queue advance, position updates, disconnect handling all live there).
     */
    fun releaseEngine() {
        stopPositionUpdates()
        if (!queue.isEmpty) persistPlaybackState()
        Thread { engine.release() }.start()
    }

    /**
     * Restores the persisted queue state, starting playback only if
     * [AppPreferences.getAutoResume] is enabled. Called on service start.
     *
     * @return true if a non-empty queue was successfully restored.
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
        if (files.isEmpty() || !queue.isEmpty) return false

        val repeat = repeatModeFromMedia3(state.repeat)
        isRepeatingOne(repeat == RepeatMode.ONE)
        queue.load(files, startAt = state.index, shuffle = state.shuffle, repeatMode = repeat)
        syncPlaylist()

        val autoResume = prefs.getAutoResume()
        if (autoResume) requestAudioFocus()
        loadAndStartAt(
            index = queue.currentIndex,
            seekToMs = state.positionMs.takeIf { autoResume && it > 0L },
            autoStart = autoResume,
        )
        invalidateState()
        return true
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
            COMMAND_SEEK_FORWARD,
            COMMAND_SEEK_BACK,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_METADATA,
            COMMAND_GET_TIMELINE,
            COMMAND_STOP,
            COMMAND_SET_MEDIA_ITEM,
            COMMAND_CHANGE_MEDIA_ITEMS,
            COMMAND_SET_REPEAT_MODE,
            COMMAND_SET_SHUFFLE_MODE,
        ).build()

        val currentIndex = queue.currentIndex
        val durationMs = engine.modVarsFlow.value.sequenceDuration(engine.currentSequenceFlow.value)
        val playlistItems = playlist.mapIndexed { i, item ->
            val uid = item.mediaId.ifEmpty {
                item.localConfiguration?.uri?.toString() ?: i.toString()
            }
            val durationUs =
                if (i == currentIndex && durationMs > 0) durationMs * 1_000L else C.TIME_UNSET
            MediaItemData.Builder(uid)
                .setMediaItem(item)
                .setIsSeekable(true)
                .setDurationUs(durationUs)
                .build()
        }

        val position =
            if (pendingSeekPositionMs >= 0) pendingSeekPositionMs else engine.positionMs.value
        val playbackState = when {
            playlist.isEmpty() -> STATE_IDLE
            engine.endedNaturally -> STATE_ENDED
            else -> STATE_READY
        }

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlistItems)
            .setShuffleModeEnabled(queue.shuffle)
            .setRepeatMode(queue.repeatMode.toMedia3())
            .setCurrentMediaItemIndex(currentIndex)
            .setPlayWhenReady(engine.isPlaying.value, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setContentPositionMs(position)
            .setSeekForwardIncrementMs(10_000L) // 10 Seconds
            .setSeekBackIncrementMs(10_000L) // 10 Seconds
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
            userPaused = true
            engine.pause()
        } else if (!engine.isPlaying.value) {
            userPaused = false
            requestAudioFocus() // also the only way back after a permanent focus loss
            when {
                !engine.initialized -> if (!isLoading) loadAndStartAt(queue.currentIndex)

                engine.paused -> engine.resume()

                // start() reads prefs and opens the native player: keep it off the main thread.
                !engine.endedNaturally -> Thread { engine.start() }.start()

                // Queue finished: play again from the top.
                else -> jumpToIndex(0)
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Stores the new repeat mode and persists it with the queue. */
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        queue.repeatMode = repeatModeFromMedia3(repeatMode)
        isRepeatingOne(queue.repeatMode == RepeatMode.ONE)
        invalidateState()
        persistPlaybackState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Toggles shuffle mode. Rebuilds the queue so the currently-playing track stays
     * current, preserving its real metadata item across the reorder.
     */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        isReordering = true
        val currentItem = playlist.getOrNull(queue.currentIndex)
        queue.setShuffle(shuffleModeEnabled)
        syncPlaylist(retainCurrentItem = currentItem)
        isReordering = false
        persistPlaybackState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Routes seek commands: next/previous navigate the queue; a seek that names a
     * different [mediaItemIndex] (queue tap from Android Auto / a controller) jumps
     * to that item; everything else seeks within the current item.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> next()

            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> previous()

            else -> {
                val targetMs = positionMs.takeIf { it != C.TIME_UNSET } ?: 0L
                if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != queue.currentIndex) {
                    jumpToIndex(mediaItemIndex, startPositionMs = targetMs)
                } else {
                    pendingSeekPositionMs = targetMs
                    engine.seek(targetMs.toInt())
                    invalidateState()
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Stops the engine and clears the queue. */
    override fun handleStop(): ListenableFuture<*> {
        Thread { engine.stop() }.start()
        queue.clear()
        syncPlaylist()
        persistQueueList()
        return Futures.immediateVoidFuture()
    }

    /**
     * Accepts a pre-resolved item list from [AutoBrowseCallback] (or playback
     * resumption) and loads the queue starting at [startIndex]. Queue expansion
     * (sibling scanning) is done upstream, so the list is trusted as-is.
     */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val files = mediaItems.mapNotNull { item ->
            val uri = item.localConfiguration?.uri ?: return@mapNotNull null
            MediaItemExtras.moduleFrom(item) ?: ModuleEntity(
                filePath = uri.toString(),
                filename = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: "Unknown",
                fileSize = 0L,
                fileExtension = uri.lastPathSegment?.substringAfterLast('.', "") ?: "",
            )
        }
        if (files.isNotEmpty()) {
            loadQueue(
                files = files,
                startAt = startIndex.coerceIn(0, files.lastIndex),
                startPositionMs = startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0L,
            )
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * Carries out a [QueueStep] decided by [queue]: loads and starts the target item,
     * parks at the end, or drops the emptied queue. Always on the main thread.
     */
    private fun perform(step: QueueStep, startPositionMs: Long = 0L) {
        when (step) {
            is QueueStep.Play -> {
                currentIndexFlow.value = step.index
                pendingSeekPositionMs = -1L
                invalidateState()
                loadAndStartAt(step.index, seekToMs = startPositionMs.takeIf { it > 0L })
                persistPlaybackState()
            }

            QueueStep.Park -> {
                persistPlaybackState()
                invalidateState()
            }

            QueueStep.Clear -> {
                syncPlaylist()
                persistQueueList()
            }

            QueueStep.None -> Unit
        }
    }

    /**
     * Rebuilds [playlist] from the controller's order and publishes the queue flows.
     *
     * @param retainCurrentItem Real-metadata item to keep at the current index
     *   across a reorder; the engine's metadata belongs to the loaded file, not to
     *   whatever lands at that index.
     */
    private fun syncPlaylist(retainCurrentItem: MediaItem? = null) {
        playlist.clear()
        playlist.addAll(queue.items.map { it.toMediaItem() })
        retainCurrentItem?.let { item ->
            if (queue.currentIndex in playlist.indices) playlist[queue.currentIndex] = item
        }
        queueFlow.value = queue.items.toList()
        currentIndexFlow.value = queue.currentIndex
        invalidateState()
    }

    /** A queued request for the loader thread. */
    private class LoadRequest(
        val index: Int,
        val file: ModuleEntity,
        val seekToMs: Long?,
        val autoStart: Boolean
    )

    /**
     * Loads the module at [index] on a background thread.
     *
     * The native engine can only load one module at a time, so if a load is already
     * running the request is parked in [pendingLoad] and picked up as soon as the current
     * one finishes (only the newest request is kept — rapid next/next/next lands on the
     * last track the user asked for instead of being dropped).
     *
     * @param seekToMs Optional position to seek to after loading.
     * @param autoStart Whether to begin playback immediately after loading.
     */
    private fun loadAndStartAt(index: Int, seekToMs: Long? = null, autoStart: Boolean = true) {
        val file = queue.items.getOrNull(index) ?: return
        val request = LoadRequest(index, file, seekToMs, autoStart)
        synchronized(loadLock) {
            if (isLoading) {
                pendingLoad = request
                return
            }
            isLoading = true
        }

        Thread {
            var current: LoadRequest? = request
            while (current != null) {
                performLoad(current)
                current = synchronized(loadLock) {
                    pendingLoad.also {
                        pendingLoad = null
                        if (it == null) isLoading = false
                    }
                }
            }
        }.start()
    }

    /** Runs on the loader thread: loads, publishes metadata, and starts [request]. */
    private fun performLoad(request: LoadRequest) {
        val (index, file) = request.index to request.file
        Timber.d("performLoad: index=$index autoStart=${request.autoStart} file=${file.name}")

        if (!engine.loadNext(file)) {
            mainHandler.post {
                // Only react if the user hasn't already navigated elsewhere.
                if (queue.current === file) perform(queue.onLoadFailed())
            }
            return
        }

        // A newer request arrived while loading: don't start audio for this stale one.
        if (synchronized(loadLock) { pendingLoad != null }) return

        // Replace the placeholder item with real metadata from the backend.
        val duration = engine.modVarsFlow.value.sequenceDuration(currentSequenceFlow.value)
        val realItem = file.toMediaItem(file.toRealMetadata(duration.toLong()))
        mainHandler.post {
            queue.onLoadSucceeded()
            // The queue may have been cleared or re-ordered while we were loading.
            if (playlist.getOrNull(index)?.mediaId == realItem.mediaId) playlist[index] = realItem
            moduleLoadedFlow.value++
            invalidateState()
        }

        if (request.autoStart) {
            engine.start()
            val seekToMs = request.seekToMs
            if (seekToMs != null && seekToMs > 0L) {
                engine.seek(seekToMs.toInt())
                pendingSeekPositionMs = seekToMs
            }
        }
        mainHandler.post(::invalidateState)
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
                    persistPlaybackState()
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
     * Persists the queue *contents* (or clears them when empty) plus the current
     * playback state. Only called when the queue itself changes — the JSON
     * encoding of a large queue is not something to redo every few seconds.
     */
    private fun persistQueueList() {
        if (queue.isEmpty) {
            scope.launch { prefs.clearQueueState() }
            return
        }
        val snapshot = queue.originalItems.toList()
        val state = capturePlaybackState()
        scope.launch {
            val json = withContext(Dispatchers.Default) { Json.encodeToString(snapshot) }
            prefs.saveQueueList(json)
            prefs.savePlaybackState(state)
        }
    }

    /** Persists only index / position / modes — cheap, safe to call every few seconds. */
    private fun persistPlaybackState() {
        if (queue.isEmpty) return
        val state = capturePlaybackState()
        scope.launch { prefs.savePlaybackState(state) }
    }

    /** Snapshot of the mutable playback state, taken on the main thread. */
    private fun capturePlaybackState() = PlaybackState(
        index = queue.currentOriginalIndex,
        shuffle = queue.shuffle,
        repeat = queue.repeatMode.toMedia3(),
        positionMs = engine.positionMs.value,
    )

    /** Placeholder artwork for the media notification. */
    private fun drawableUri(resId: Int): Uri =
        "android.resource://${context.packageName}/$resId".toUri()

    /**
     * Constructs a [MediaMetadata] with the given [title] and [artist], the app's placeholder
     * album art, and an optional [durationMs]. Used to populate media session metadata for the
     * lock-screen / notification; artwork is a static drawable because module files carry no
     * embedded images.
     */
    private fun buildMetadata(
        title: String,
        artist: String,
        durationMs: Long? = null
    ): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .apply { durationMs?.let { setDurationMs(it) } }
        .setArtworkUri(drawableUri(R.drawable.aa_album_art))
        .setIsPlayable(true)
        .build()

    /**
     * Builds a [MediaItem] with placeholder metadata for initial queue population.
     * Real metadata (loaded from libxmp) is injected by [loadAndStartAt] later.
     */
    private fun ModuleEntity.toMediaItem(
        metadata: MediaMetadata = buildMetadata(name, type)
    ): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(filePath)
        .setMediaMetadata(metadata)
        .build()

    /** Builds [MediaMetadata] from libxmp after the module has been loaded successfully. */
    private fun ModuleEntity.toRealMetadata(durationMs: Long): MediaMetadata = buildMetadata(
        title = modVarsFlow.value.modName.ifBlank { filename },
        artist = modVarsFlow.value.modType.ifBlank { fileExtension.uppercase() },
        durationMs = durationMs,
    )
}

private fun RepeatMode.toMedia3(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
}

private fun repeatModeFromMedia3(mode: Int): RepeatMode = when (mode) {
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}
