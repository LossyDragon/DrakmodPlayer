package com.lossydragon.modplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
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
import com.lossydragon.modplayer.model.ModuleFile
import com.lossydragon.modplayer.util.queryDirectoryEntries
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
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ModInfo
import timber.log.Timber

/**
 * Media3 [SimpleBasePlayer] backed by [PlayerEngine].
 * Manages queue, playback lifecycle, audio focus, and Android Auto item resolution.
 */
@OptIn(UnstableApi::class)
class ModPlayer(
    private val context: Context,
    private val engine: PlayerEngine,
    private val prefs: AppPreferences
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var repeatMode = REPEAT_MODE_OFF
    private var shuffleMode = false
    private var hasFocus = false

    private val artworkUri: Uri by lazy {
        "android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}".toUri()
    }

    @Volatile
    private var isLoading = false

    @Volatile
    private var pendingSeekPositionMs: Long = -1L

    @Volatile
    var isReordering = false

    private val playlist = mutableListOf<MediaItem>()
    private val queue = mutableListOf<ModuleFile>()
    private val originalQueue = mutableListOf<ModuleFile>()

    private var currentIndex = 0
    private var lastNavDirection: Int = 1 // 1 = forward, -1 = backward
    private var skipAttempts: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionUpdateJob: Job? = null

    val frameFlow by engine::frameFlow
    val isPlaying by engine::isPlaying
    val currentSequenceFlow by engine::currentSequenceFlow

    val numPatterns: Int get() = engine.numPatterns
    val numChannels: Int get() = engine.numChannels
    val numInstruments: Int get() = engine.numInstruments
    val numSamples: Int get() = engine.numSamples
    val numSequences: Int get() = engine.numSequences
    val sequenceDurations: List<Int> get() = engine.getSequenceDurations()

    val currentIndexFlow: StateFlow<Int>
        field = MutableStateFlow(0)
    val queueFlow: StateFlow<List<ModuleFile>>
        field = MutableStateFlow(emptyList())
    val moduleLoadedFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var playAllSequences: Boolean
        get() = engine.playAllSequences
        set(value) {
            engine.playAllSequences = value
        }

    fun setSequence(index: Int): Boolean = engine.setSequence(index)

    private fun requestAudioFocus() {
        if (hasFocus) return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        engine.resume()
                    }

                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasFocus = false
                        engine.pause()
                    }
                }
            }
            .build()

        audioFocusRequest = request

        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) hasFocus = true

        Timber.d("Audio focus result=$result")
    }

    /** Releases audio focus. Call from [PlayerService.onDestroy]. */
    fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        hasFocus = false
    }

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
                    if (engine.endedNaturally) mainHandler.post { advanceToNext() }
                }
            }
        }
    }

    /** Loads [files] into the queue and starts playback at [startAt]. Respects current shuffle state. */
    fun loadQueue(
        files: List<ModuleFile>,
        startAt: Int,
        shuffle: Boolean = shuffleMode,
        repeatMode: Int = this.repeatMode
    ) {
        this.shuffleMode = shuffle
        this.repeatMode = repeatMode

        requestAudioFocus()

        originalQueue.clear()
        originalQueue.addAll(files)

        applyQueueOrder(startAt)
        invalidateState()
        loadAndStartAt(currentIndex)
        persistQueue()
    }

    private fun applyQueueOrder(startAt: Int = 0) {
        Timber.d(
            "applyQueueOrder shuffleModeEnabled=$shuffleMode " +
                "startAt=$startAt originalQueue.size=${originalQueue.size}"
        )
        queue.clear()
        playlist.clear()

        if (shuffleMode) {
            val shuffled = originalQueue.toMutableList()
            val first = shuffled.removeAt(startAt.coerceIn(0, shuffled.lastIndex))
            shuffled.shuffle()
            queue.add(first)
            queue.addAll(shuffled)
            currentIndex = 0
            Timber.d("Shuffled queue: ${queue.map { it.name }}")
        } else {
            queue.addAll(originalQueue)
            currentIndex = startAt.coerceIn(0, queue.lastIndex)
            Timber.d("Sequential queue: ${queue.map { it.name }}")
        }

        playlist.addAll(queue.map { it.toMediaItem() })
        queueFlow.value = queue.toList()
        currentIndexFlow.value = currentIndex
        invalidateState()
    }

    private fun loadAndStartAt(index: Int, seekToMs: Long? = null, autoStart: Boolean = true) {
        val file = queue.getOrNull(index) ?: return

        if (isLoading) return
        isLoading = true

        Thread {
            Timber.d("loadAndStartAt: loading index=$index autoStart=$autoStart file=${file.name}")
            if (engine.loadNext(file)) {
                Timber.d("loadAndStartAt: loadNext succeeded, autoStart=$autoStart")

                val realItem = MediaItem.Builder()
                    .setUri(file.uri)
                    .setMediaId(file.uri.toString())
                    .setMediaMetadata(file.toRealMetadata(engine.durationMs.value))
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

                mainHandler.post { invalidateState() }
            } else {
                mainHandler.post { skipUnplayable() }
            }
            isLoading = false
        }.start()
    }

    private fun skipUnplayable() {
        skipAttempts++
        if (skipAttempts >= queue.size) {
            skipAttempts = 0
            clearQueue()
            return
        }
        if (lastNavDirection < 0) advanceToPrevious() else advanceToNext()
    }

    private fun navigate(to: Int) {
        currentIndex = to
        currentIndexFlow.value = currentIndex
        pendingSeekPositionMs = -1L
        invalidateState()
        loadAndStartAt(currentIndex)
        persistQueue()
    }

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

    private fun advanceToPrevious() {
        lastNavDirection = -1

        when {
            repeatMode == REPEAT_MODE_ONE -> navigate(currentIndex)

            repeatMode == REPEAT_MODE_ALL -> navigate(
                if (currentIndex - 1 >=
                    0
                ) {
                    currentIndex - 1
                } else {
                    queue.lastIndex
                }
            )

            currentIndex - 1 >= 0 -> navigate(currentIndex - 1)

            else -> navigate(0) // Fallback
        }
    }

    fun next() = advanceToNext()

    fun previous() {
        if (engine.positionMs.value > 3_000L) {
            pendingSeekPositionMs = 0L
            engine.seek(0)
            invalidateState()
        } else {
            advanceToPrevious()
        }
    }

    fun jumpToIndex(index: Int) {
        if (index in queue.indices) {
            lastNavDirection = if (index >= currentIndex) 1 else -1
            navigate(index)
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var lastPersist = 0L
            while (true) {
                delay(500L.milliseconds)
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

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

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
                item.localConfiguration?.uri?.toString() ?: i.toString()
            }
            val durationUs = if (i == currentIndex && engine.durationMs.value > 0) {
                engine.durationMs.value * 1_000L
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

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Timber.d(
            "handleSetPlayWhenReady: playWhenReady=$playWhenReady isPlaying=${engine.isPlaying.value} initialized=${engine.initialized} isLoading=$isLoading queue=${queue.size}"
        )
        if (!playWhenReady) {
            engine.pause()
        } else if (!engine.isPlaying.value) {
            requestAudioFocus()
            if (!engine.initialized) {
                if (!isLoading) {
                    Timber.d(
                        "handleSetPlayWhenReady: engine not initialized, calling loadAndStartAt($currentIndex)"
                    )
                    loadAndStartAt(currentIndex)
                } else {
                    Timber.d(
                        "handleSetPlayWhenReady: engine not initialized but isLoading=true, skipping"
                    )
                }
            } else {
                if (engine.paused) {
                    Timber.d("handleSetPlayWhenReady: engine initialized, calling resume()")
                    engine.resume()
                } else if (!engine.endedNaturally) {
                    Timber.d("handleSetPlayWhenReady: engine initialized, calling start()")
                    engine.start()
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        invalidateState()
        persistQueue()
        return Futures.immediateVoidFuture()
    }

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

        // Restore real metadata for current track.
        // Xmp.getModName()/getModType() may not reflect the current track if called
        // during shuffle toggle. Use the saved item instead.
        if (currentRealItem != null) {
            playlist[currentIndex] = currentRealItem
        }

        currentIndexFlow.value = currentIndex
        queueFlow.value = queue.toList()

        isReordering = false
        invalidateState()
        persistQueue()

        return Futures.immediateVoidFuture()
    }

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

    override fun handleStop(): ListenableFuture<*> {
        Thread { engine.stop() }.start()
        clearQueue()
        return Futures.immediateVoidFuture()
    }

    /** Android Auto - resolves selected item to full directory queue. */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val files = mediaItems.mapNotNull { item ->
            val uri = item.localConfiguration?.uri ?: return@mapNotNull null
            ModuleFile(
                uri = uri,
                name = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: "Unknown",
                sizeBytes = 0L,
                extension = uri.lastPathSegment?.substringAfterLast('.') ?: "",
            )
        }
        if (files.isEmpty()) return Futures.immediateVoidFuture()

        val firstUri = files.first().uri
        val treeUri = runBlocking { prefs.getLastDirectoryUri() }?.toUri()
            ?: return Futures.immediateVoidFuture()

        val parentDocId = DocumentsContract.getDocumentId(firstUri).substringBeforeLast('/')

        val siblings = context.contentResolver
            .queryDirectoryEntries(treeUri, parentDocId)
            .sortedBy { it.name }
            .filter { !it.isDirectory && Xmp.testFromFd(context, it.childUri, ModInfo()) }
            .map { entry ->
                ModuleFile(
                    uri = entry.childUri,
                    name = entry.name,
                    sizeBytes = 0L,
                    extension = entry.name.substringAfterLast('.', ""),
                )
            }

        val resolved = siblings.ifEmpty { files }
        loadQueue(
            files = resolved,
            startAt = resolved.indexOfFirst { it.uri == firstUri }.coerceAtLeast(0)
        )

        return Futures.immediateVoidFuture()
    }

    fun releaseEngine() {
        // Flush queue state synchronously before cancelling the scope so that any
        // persistQueue() coroutine that was queued but not yet run isn't lost.
        if (originalQueue.isNotEmpty()) {
            val saveIndex = if (shuffleMode) {
                queue.getOrNull(currentIndex)
                    ?.let { originalQueue.indexOf(it) }
                    ?.coerceAtLeast(0)
                    ?: 0
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
        Thread { engine.stop() }.start()
    }

    private fun persistQueue() {
        scope.launch {
            if (originalQueue.isEmpty()) {
                prefs.clearQueueState()
                return@launch
            }
            // When shuffled, currentIndex is a position in the shuffled queue; translate it
            // back to an originalQueue index so applyQueueOrder() restores the right track.
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

    suspend fun restoreQueue(): Boolean {
        val state = prefs.getQueueState() ?: return false
        val files = try {
            Json.decodeFromString<List<ModuleFile>>(state.json)
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore queue")
            prefs.clearQueueState()
            return false
        }
        if (files.isEmpty()) return false

        originalQueue.clear()
        originalQueue.addAll(files)
        this.shuffleMode = state.shuffle
        this.repeatMode = state.repeat
        applyQueueOrder(state.index)

        if (prefs.getAutoResume()) {
            loadAndStartAt(currentIndex, seekToMs = state.positionMs.takeIf { it > 0L })
        } else {
            loadAndStartAt(currentIndex, autoStart = false)
        }

        invalidateState()
        return true
    }

    fun getPatternData(patternIndex: Int) = engine.getPatternData(patternIndex)

    /** Builds a [MediaItem] with placeholder metadata for initial queue population. */
    private fun ModuleFile.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(resolvedName.ifBlank { name })
                    .setArtist(resolvedType.ifBlank { extension.uppercase() })
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    /** Builds [MediaMetadata] from libxmp after the module is loaded */
    private fun ModuleFile.toRealMetadata(duration: Long): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(Xmp.getModName().ifBlank { resolvedName.ifBlank { name } })
            .setArtist(Xmp.getModType().ifBlank { resolvedType.ifBlank { extension.uppercase() } })
            .setDurationMs(duration)
            .setArtworkUri(artworkUri)
            .setIsPlayable(true)
            .build()
}
