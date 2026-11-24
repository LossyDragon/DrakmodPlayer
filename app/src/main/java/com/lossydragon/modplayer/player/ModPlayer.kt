package com.lossydragon.modplayer.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.PlaybackState
import com.lossydragon.modplayer.db.entity.ModuleEntity
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
 * Media3 [SimpleBasePlayer] backed by an [Engine]. Queue decisions live in
 * [QueueController]; this class carries them out against the engine and Media3.
 *
 * All state here is owned by the main thread. Engine commands are posted to the engine
 * thread and report back through [mainHandler]; a load generation counter discards
 * results of requests that were superseded while in flight.
 */
@OptIn(UnstableApi::class)
class ModPlayer(
    private val context: Context,
    private val engine: Engine,
    private val prefs: AppPreferences
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private enum class Phase { IDLE, LOADING, READY, PLAYING, ENDED }

    private companion object {
        /** The disconnect itself plus one failed restart. */
        const val MAX_RECOVERIES = 2

        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_PREPARE,
            COMMAND_STOP,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_FORWARD,
            COMMAND_SEEK_BACK,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_METADATA,
            COMMAND_GET_TIMELINE,
            COMMAND_SET_MEDIA_ITEM,
            COMMAND_CHANGE_MEDIA_ITEMS,
            COMMAND_SET_REPEAT_MODE,
            COMMAND_SET_SHUFFLE_MODE,
        ).build()
    }

    private val queue = QueueController<ModuleEntity>()

    /** [QueueController.items] as [MediaItem]s for [getState]; the loaded one has real metadata. */
    private val playlist = mutableListOf<MediaItem>()

    /** Real-metadata item of the loaded module; survives [syncPlaylist]. */
    private var loadedItem: MediaItem? = null

    /** Last [getState] result; its timeline is reused until [playlist], index or duration change. */
    private var cachedState: State? = null
    private var playlistDirty = true
    private var cachedIndex = -1
    private var cachedDurationMs = -1L

    private var phase = Phase.IDLE

    /** User intent, independent of whether the engine is actually rendering yet. */
    private var playWhenReady = false
    private var playWhenReadyReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var suppressionReason = PLAYBACK_SUPPRESSION_REASON_NONE
    private var playerError: PlaybackException? = null

    /** Bumped on every load/stop so stale engine results are ignored. */
    private var loadGeneration = 0

    /** Disconnect recoveries since the engine last started; stops a dead stream looping. */
    private var recoveries = 0

    /** Position to seek to the next time the current item is started. */
    private var resumePositionMs = 0L

    /** Seek target reported until the engine has applied it; -1 when none. */
    private var pendingSeekPositionMs = -1L

    /** Snapshot of the playback queue; emitted when the queue changes. */
    val queueFlow: StateFlow<ImmutableList<ModuleEntity>>
        field = MutableStateFlow<ImmutableList<ModuleEntity>>(persistentListOf())

    private val audioFocus =
        AudioFocusHandler(context, ::onFocusGain, ::onFocusLoss, engine::setDucked)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionUpdateJob: Job? = null

    /** Module files carry no artwork, so the notification gets a static drawable. */
    private val artworkUri = context.drawableUri(R.drawable.aa_album_art)

    init {
        scope.launch {
            prefs.getGlobalSubSongsFlow().collect { engine.playAllSequences = it }
        }
        scope.launch {
            engine.playingFlow.collect { playing ->
                if (playing) {
                    if (phase == Phase.READY) phase = Phase.PLAYING
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    if (phase == Phase.PLAYING) phase = Phase.READY
                }
                invalidateState()
            }
        }
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    EngineEvent.Ended -> onEnded()
                    EngineEvent.Disconnected -> onDisconnected()
                    is EngineEvent.Error -> onEngineError(event.message)
                }
            }
        }
    }

    // --- public API used by the UI --------------------------------------------------------

    /** Switches to sequence [index] of the loaded module. */
    fun setSequence(index: Int) = engine.post { engine.setSequence(index) }

    /** Switches to the next sequence of the loaded module, wrapping to the first. */
    fun nextSequence() {
        val count = engine.modVarsFlow.value.seqData.size
        if (count > 1) setSequence((engine.currentSequenceFlow.value + 1) % count)
    }

    /** Loads [files] into the queue and starts playback at [startAt]. */
    fun loadQueue(
        files: List<ModuleEntity>,
        startAt: Int,
        shuffleMode: Boolean = queue.shuffle,
        repeatMode: Int = queue.repeatMode.toMedia3(),
        startPositionMs: Long = 0L
    ) {
        val repeat = repeatModeFromMedia3(repeatMode)
        engine.post { engine.setRepeatOne(repeat == RepeatMode.ONE) }
        ensureServiceRunning()
        setPlayWhenReadyInternal(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        acquireFocus()

        val step = queue.load(files, startAt, shuffle = shuffleMode, repeatMode = repeat)
        queueChanged()
        perform(step, startPositionMs)
    }

    fun next() = perform(queue.next())

    /** Previous item, or back to the start of this one after more than 3 seconds. */
    fun previous() {
        if (currentPositionForUi() > 3_000L) {
            perform(QueueStep.Restart)
        } else {
            perform(queue.previous())
        }
    }

    fun jumpToIndex(index: Int, startPositionMs: Long = 0L) =
        perform(queue.jumpTo(index), startPositionMs)

    /**
     * Releases audio focus, flushes the playback position and tears down the engine.
     * Called from [PlayerService.onDestroy]. The player is a process singleton shared by
     * the UI and every service instance, so [scope] and the Media3 base are left alone.
     */
    fun releaseEngine() {
        audioFocus.abandon()
        // The queue outlives the service; the next play resumes from here.
        goIdle(if (phase == Phase.ENDED) 0L else currentPositionForUi())
        if (!queue.isEmpty) persistPlaybackState()
        engine.post { engine.release() }
        invalidateState()
    }

    /**
     * Restores the persisted queue without touching the audio device. Playback starts
     * only when [AppPreferences.getAutoResume] is on; otherwise the first play loads.
     *
     * @return true if a non-empty queue was restored.
     */
    suspend fun restoreQueue(): Boolean {
        if (!queue.isEmpty) return false
        val saved = prefs.getQueueState() ?: return false
        val state = saved.playback
        val autoResume = prefs.getAutoResume()
        val files = try {
            withContext(Dispatchers.Default) { saved.decodeModules() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to restore queue")
            prefs.clearQueueState()
            return false
        }
        // Re-check: the UI may have loaded a queue while we were suspended.
        if (files.isEmpty() || !queue.isEmpty) return false

        val repeat = repeatModeFromMedia3(state.repeat)
        engine.post { engine.setRepeatOne(repeat == RepeatMode.ONE) }
        queue.load(files, startAt = state.index, shuffle = state.shuffle, repeatMode = repeat)
        syncPlaylist()
        resumePositionMs = state.positionMs.coerceAtLeast(0L)

        if (autoResume) {
            setPlayWhenReadyInternal(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            acquireFocus()
            loadCurrent()
        } else {
            invalidateState()
        }
        return true
    }

    // --- Media3 ---------------------------------------------------------------------------

    override fun getState(): State {
        val currentIndex = queue.currentIndex
        val durationMs =
            engine.modVarsFlow.value.sequenceDuration(engine.currentSequenceFlow.value).toLong()

        val cached = cachedState
        val builder = if (cached != null && !playlistDirty &&
            currentIndex == cachedIndex && durationMs == cachedDurationMs
        ) {
            cached.buildUpon()
        } else {
            playlistDirty = false
            cachedIndex = currentIndex
            cachedDurationMs = durationMs
            State.Builder()
                .setAvailableCommands(COMMANDS)
                .setSeekForwardIncrementMs(10_000L)
                .setSeekBackIncrementMs(10_000L)
                .setPlaylist(playlistData(currentIndex, durationMs))
        }

        val playbackState = when {
            playlist.isEmpty() -> STATE_IDLE
            phase == Phase.IDLE -> STATE_IDLE
            phase == Phase.LOADING -> STATE_BUFFERING
            phase == Phase.ENDED -> STATE_ENDED
            else -> STATE_READY
        }

        return builder
            .setShuffleModeEnabled(queue.shuffle)
            .setRepeatMode(queue.repeatMode.toMedia3())
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else currentIndex)
            .setPlayWhenReady(playWhenReady, playWhenReadyReason)
            .setPlaybackSuppressionReason(suppressionReason)
            .setPlaybackState(playbackState)
            .setPlayerError(playerError)
            .setContentPositionMs(currentPositionForUi())
            .build()
            .also { cachedState = it }
    }

    private fun playlistData(currentIndex: Int, durationMs: Long) =
        playlist.mapIndexed { i, item ->
            // Original index + path: unique even when a file is queued twice, stable across shuffle.
            val uid = "${queue.originalIndexAt(i)}:${item.mediaId}"
            val durationUs =
                if (i == currentIndex && durationMs > 0) durationMs * 1_000L else C.TIME_UNSET
            MediaItemData.Builder(uid)
                .setMediaItem(item)
                .setIsSeekable(true)
                .setDurationUs(durationUs)
                .build()
        }

    override fun handlePrepare(): ListenableFuture<*> {
        if (phase == Phase.IDLE) loadCurrent()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Timber.d("handleSetPlayWhenReady: $playWhenReady phase=$phase queue=${queue.size}")
        if (!playWhenReady) {
            setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            pauseEngine()
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        playerError = null
        setPlayWhenReadyInternal(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (!acquireFocus()) return Futures.immediateVoidFuture()
        when (phase) {
            Phase.IDLE -> loadCurrent()

            // the load completion starts the engine
            Phase.LOADING -> Unit

            Phase.READY -> startEngineIfWanted()

            Phase.PLAYING -> Unit

            Phase.ENDED -> perform(queue.jumpTo(0))
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val mode = repeatModeFromMedia3(repeatMode)
        queue.setRepeatMode(mode)
        engine.post { engine.setRepeatOne(mode == RepeatMode.ONE) }
        invalidateState()
        persistPlaybackState()
        return Futures.immediateVoidFuture()
    }

    /** Rebuilds the order; the loaded item stays current. */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        queue.setShuffle(shuffleModeEnabled)
        syncPlaylist()
        persistPlaybackState()
        return Futures.immediateVoidFuture()
    }

    /**
     * Next/previous navigate the queue; a seek naming another [mediaItemIndex] jumps to
     * it; anything else seeks within the current item (reloading it when parked).
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
                    seekWithinCurrent(targetMs)
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Media3 contract: stop playback, keep the timeline. The queue is cleared only via the queue commands. */
    override fun handleStop(): ListenableFuture<*> {
        setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        goIdle(0L)
        engine.post { engine.stop() }
        persistPlaybackState(positionOverride = 0L)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val files = mediaItems.toModules()
        if (mediaItems.isEmpty()) {
            clearQueue()
        } else if (files.isNotEmpty()) {
            // Items that all lack a URI are a request we could not resolve, not a clear.
            loadQueue(
                files = files,
                startAt = startIndex.coerceIn(0, files.lastIndex),
                startPositionMs = startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0L,
            )
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        queue.add(index, mediaItems.toModules())
        queueChanged()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        applyEdit(queue.remove(fromIndex, toIndex))
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int
    ): ListenableFuture<*> {
        queue.move(fromIndex, toIndex, newIndex)
        queueChanged()
        return Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    ): ListenableFuture<*> {
        val step = queue.remove(fromIndex, toIndex)
        queue.add(fromIndex, mediaItems.toModules())
        // The remove's Clear is stale once the add put items back.
        applyEdit(
            if (step != QueueStep.None && !queue.isEmpty) {
                QueueStep.Play(queue.currentIndex)
            } else {
                step
            }
        )
        return Futures.immediateVoidFuture()
    }

    // --- transitions ----------------------------------------------------------------------

    /** Applies the result of a queue edit (remove/replace). */
    private fun applyEdit(step: QueueStep) {
        if (step == QueueStep.Clear) return clearQueue()
        queueChanged()
        // The playing item was removed: move on only if something was playing/loaded.
        if (step is QueueStep.Play && phase != Phase.IDLE) perform(step)
    }

    private fun clearQueue() {
        goIdle(0L)
        queue.clear()
        loadedItem = null
        setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        engine.post { engine.stop() }
        queueChanged()
    }

    /** Invalidates in-flight engine results and parks at [resumeMs] with nothing loaded. */
    private fun goIdle(resumeMs: Long) {
        loadGeneration++
        phase = Phase.IDLE
        resumePositionMs = resumeMs
        pendingSeekPositionMs = -1L
        stopPositionUpdates()
    }

    private fun loadCurrent(positionMs: Long = resumePositionMs) {
        if (!queue.isEmpty) perform(QueueStep.Play(queue.currentIndex), positionMs)
    }

    /** Carries out a [QueueStep]. Always on the main thread. */
    private fun perform(step: QueueStep, startPositionMs: Long = 0L) {
        when (step) {
            is QueueStep.Play -> startLoad(step.index, startPositionMs)

            QueueStep.Restart -> when (phase) {
                Phase.READY, Phase.PLAYING, Phase.ENDED -> seekWithinCurrent(0L)
                else -> resumePositionMs = 0L
            }

            QueueStep.Park -> {
                phase = Phase.ENDED
                resumePositionMs = 0L
                // Nothing will auto-start; the UI must show a play button again.
                setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                persistPlaybackState(positionOverride = 0L)
                invalidateState()
            }

            QueueStep.Clear -> clearQueue()

            QueueStep.None -> Unit
        }
    }

    private fun startLoad(index: Int, startPositionMs: Long) {
        val file = queue.items.getOrNull(index) ?: return
        val generation = ++loadGeneration
        phase = Phase.LOADING
        playerError = null
        loadedItem = null
        pendingSeekPositionMs = -1L
        resumePositionMs = startPositionMs.coerceAtLeast(0L)
        stopPositionUpdates()
        invalidateState()
        persistPlaybackState(positionOverride = resumePositionMs)

        engine.post {
            val result = engine.load(file)
            mainHandler.post { onLoadFinished(generation, file, result) }
        }
    }

    private fun onLoadFinished(generation: Int, file: ModuleEntity, result: LoadResult) {
        if (generation != loadGeneration) return // superseded
        when (result) {
            LoadResult.OK -> Unit

            LoadResult.UNPLAYABLE -> {
                perform(queue.onLoadFailed())
                return
            }

            // Not the file's fault: keep the queue rather than skipping through it.
            LoadResult.NO_AUDIO -> {
                failPlayback(context.getString(R.string.error_audio_open))
                return
            }
        }
        queue.onLoadSucceeded()
        phase = Phase.READY

        val mv = engine.modVarsFlow.value
        loadedItem = file.toMediaItem(
            buildMetadata(
                title = mv.modName.ifBlank { file.filename },
                artist = mv.modType.ifBlank { file.fileExtension.uppercase() },
                durationMs = mv.sequenceDuration(engine.currentSequenceFlow.value).toLong(),
            )
        )
        applyLoadedItem()

        startEngineIfWanted()
        invalidateState()
    }

    /** Starts the loaded item if the user wants playback and nothing suppresses it. */
    private fun startEngineIfWanted() {
        if (phase != Phase.READY || !playWhenReady ||
            suppressionReason != PLAYBACK_SUPPRESSION_REASON_NONE
        ) {
            return
        }
        val seekMs = resumePositionMs
        resumePositionMs = 0L
        if (seekMs > 0L) pendingSeekPositionMs = seekMs
        val generation = loadGeneration
        engine.post {
            val started = engine.play()
            if (started && seekMs > 0L) engine.seek(seekMs.toInt())
            mainHandler.post {
                if (started) recoveries = 0
                clearPendingSeek(generation, seekMs)
            }
        }
    }

    private fun seekWithinCurrent(targetMs: Long) {
        when (phase) {
            Phase.IDLE, Phase.LOADING -> resumePositionMs = targetMs

            Phase.ENDED -> loadCurrent(targetMs)

            Phase.READY, Phase.PLAYING -> {
                pendingSeekPositionMs = targetMs
                val generation = loadGeneration
                engine.post {
                    engine.seek(targetMs.toInt())
                    mainHandler.post { clearPendingSeek(generation, targetMs) }
                }
            }
        }
        invalidateState()
    }

    /** The engine applied the seek to [ms], unless a newer load or seek replaced it. */
    private fun clearPendingSeek(generation: Int, ms: Long) {
        if (generation == loadGeneration && pendingSeekPositionMs == ms) {
            pendingSeekPositionMs = -1L
        }
    }

    /** READY counts too: a start may already be posted and the phase not caught up yet. */
    private fun pauseEngine() {
        if (phase == Phase.READY || phase == Phase.PLAYING) engine.post { engine.pause() }
    }

    /** Goes idle with an error, keeping the queue and position. Media3 only allows an error in STATE_IDLE. */
    private fun failPlayback(message: String) {
        playerError = PlaybackException(message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        loadGeneration++
        phase = Phase.IDLE
        setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        invalidateState()
    }

    /** A start is only posted from READY; in any other phase the error is from a superseded one. */
    private fun onEngineError(message: String) {
        if (phase == Phase.IDLE || phase == Phase.LOADING) return
        failPlayback(message)
    }

    private fun onEnded() {
        if (phase == Phase.IDLE || phase == Phase.LOADING) return
        perform(queue.next())
    }

    private fun onDisconnected() {
        if (phase == Phase.IDLE) return
        Timber.w("Audio route lost; reopening on the next start")
        val position = currentPositionForUi()
        goIdle(position)
        val generation = loadGeneration
        if (++recoveries > MAX_RECOVERIES) {
            // The stream keeps failing to start; stop instead of reloading forever.
            playerError = PlaybackException(
                context.getString(R.string.error_audio_start),
                null,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )
            setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }
        invalidateState()
        engine.post {
            engine.recoverFromDisconnect()
            mainHandler.post {
                if (generation == loadGeneration && playWhenReady) loadCurrent(position)
            }
        }
    }

    // --- audio focus ----------------------------------------------------------------------

    /**
     * Requests focus. Returns true when the engine may start now. A delayed grant leaves
     * [playWhenReady] set and suppressed until GAIN; a refusal clears it.
     */
    private fun acquireFocus(): Boolean = when (audioFocus.request()) {
        FocusResult.GRANTED -> {
            suppressionReason = PLAYBACK_SUPPRESSION_REASON_NONE
            true
        }

        FocusResult.DELAYED -> {
            suppressionReason = PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            false
        }

        FocusResult.FAILED -> {
            setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            false
        }
    }

    private fun onFocusGain() {
        suppressionReason = PLAYBACK_SUPPRESSION_REASON_NONE
        if (playWhenReady) {
            when (phase) {
                Phase.READY -> startEngineIfWanted()
                Phase.IDLE -> loadCurrent()
                else -> Unit
            }
        }
        invalidateState()
    }

    private fun onFocusLoss(transient: Boolean) {
        if (transient) {
            suppressionReason = PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        } else {
            setPlayWhenReadyInternal(false, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        }
        pauseEngine()
        invalidateState()
    }

    private fun setPlayWhenReadyInternal(value: Boolean, reason: Int) {
        playWhenReady = value
        playWhenReadyReason = reason
        if (value) suppressionReason = PLAYBACK_SUPPRESSION_REASON_NONE
    }

    // --- helpers --------------------------------------------------------------------------

    private fun currentPositionForUi(): Long = when {
        pendingSeekPositionMs >= 0L -> pendingSeekPositionMs
        phase == Phase.IDLE || phase == Phase.LOADING -> resumePositionMs
        else -> engine.positionMs.value
    }

    /** Media3 Service start, so the session and notification exist before audio does. */
    private fun ensureServiceRunning() {
        try {
            context.startService(Intent(context, PlayerService::class.java))
        } catch (e: IllegalStateException) {
            // Background start restriction; the service is started by the session when it binds.
            Timber.w(e, "Could not start PlayerService")
        }
    }

    private fun List<MediaItem>.toModules(): List<ModuleEntity> = mapNotNull { item ->
        val uri = item.localConfiguration?.uri ?: return@mapNotNull null
        MediaItemExtras.moduleFrom(item) ?: ModuleEntity(
            filePath = uri.toString(),
            filename = item.mediaMetadata.title?.toString()
                ?: uri.lastPathSegment
                ?: context.getString(R.string.unknown),
            fileSize = 0L,
            fileExtension = uri.lastPathSegment?.substringAfterLast('.', "") ?: "",
        )
    }

    /** The queue contents or order changed. */
    private fun queueChanged() {
        syncPlaylist()
        persistQueueList()
    }

    /** Rebuilds [playlist] and publishes [queueFlow]. */
    private fun syncPlaylist() {
        playlist.clear()
        queue.items.mapTo(playlist) { it.toMediaItem() }
        applyLoadedItem()
        queueFlow.value = queue.items.toImmutableList()
        invalidateState()
    }

    /** Puts [loadedItem] at the current index while that is still the loaded file. */
    private fun applyLoadedItem() {
        playlistDirty = true
        val item = loadedItem ?: return
        val index = queue.currentIndex
        if (playlist.getOrNull(index)?.mediaId == item.mediaId) playlist[index] = item
    }

    private fun startPositionUpdates() {
        if (positionUpdateJob?.isActive == true) return
        positionUpdateJob = scope.launch {
            var lastPersist = System.currentTimeMillis()
            while (true) {
                delay(500L.milliseconds)
                invalidateState()
                val now = System.currentTimeMillis()
                if (now - lastPersist > 5_000) {
                    persistPlaybackState()
                    lastPersist = now
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /** Persists the queue contents (or clears them) plus the playback state. Only on queue changes. */
    private fun persistQueueList() {
        if (queue.isEmpty) {
            scope.launch { prefs.clearQueueState() }
            return
        }
        val snapshot = queue.originalItems.toList()
        val state = capturePlaybackState(null)
        scope.launch {
            val json = withContext(Dispatchers.Default) { Json.encodeToString(snapshot) }
            prefs.saveQueueList(json)
            prefs.savePlaybackState(state)
        }
    }

    /** Persists only index / position / modes; cheap enough to run every few seconds. */
    private fun persistPlaybackState(positionOverride: Long? = null) {
        if (queue.isEmpty) return
        val state = capturePlaybackState(positionOverride)
        scope.launch { prefs.savePlaybackState(state) }
    }

    private fun capturePlaybackState(positionOverride: Long?) = PlaybackState(
        index = queue.currentOriginalIndex,
        shuffle = queue.shuffle,
        repeat = queue.repeatMode.toMedia3(),
        positionMs = positionOverride ?: currentPositionForUi(),
    )

    private fun buildMetadata(
        title: String,
        artist: String,
        durationMs: Long? = null
    ): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .apply { durationMs?.let { setDurationMs(it) } }
        .setArtworkUri(artworkUri)
        .setIsPlayable(true)
        .build()

    /** Placeholder item; real metadata replaces it once the module is loaded. */
    private fun ModuleEntity.toMediaItem(
        metadata: MediaMetadata = buildMetadata(name, type)
    ): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(AutoMediaId.file(filePath)) // same id scheme as the Auto browse tree
        .setMediaMetadata(metadata)
        .build()
}

internal fun Context.drawableUri(resId: Int): Uri = "android.resource://$packageName/$resId".toUri()

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
