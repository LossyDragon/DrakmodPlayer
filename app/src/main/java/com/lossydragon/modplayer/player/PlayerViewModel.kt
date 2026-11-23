package com.lossydragon.modplayer.player

import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.native.RenderingBackend
import com.lossydragon.native.model.AudioStats
import com.lossydragon.native.model.FrameInfo
import com.lossydragon.native.model.ModVars
import com.lossydragon.native.model.Sequence
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED }

/** The three player visualisations; persisted by ordinal. */
enum class PlayerView { PATTERN, CHANNELS, DEBUG }

@Immutable
data class PlayerUiState(
    val currentModule: ModuleEntity? = null,
    val playAllSequences: Boolean = false,
    val queue: ImmutableList<ModuleEntity> = persistentListOf(),
    val showRowNumbers: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val playerView: PlayerView = PlayerView.PATTERN,
    val modVars: ModVars = ModVars(),
    val instruments: ImmutableList<String> = persistentListOf(),
    val sequences: ImmutableList<Sequence> = persistentListOf(),
    val currentSequence: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentQueueIndex: Int = 0,
    val isShuffle: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Bridges [ModPlayer] to the UI. Playback/queue state comes from the Media3 [Player]
 * surface via a listener; visualisation data (frame info, patterns, stats) straight from
 * the [Engine].
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val player: ModPlayer,
    private val engine: Engine,
    private val prefs: AppPreferences
) : ViewModel() {

    val state: StateFlow<PlayerUiState>
        field = MutableStateFlow(PlayerUiState())

    /**
     * Per-render-frame snapshot (~50 Hz). Kept out of [state] so only the progress /
     * pattern / channel composables recompose at that rate.
     */
    val frameInfo: StateFlow<FrameInfo> by engine::frameInfoFlow

    val needsBackendSetup: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer()
    }

    init {
        player.addListener(playerListener)
        syncFromPlayer()

        prefs.getRenderingBackendFlow().onEach { backend ->
            needsBackendSetup.value = backend == RenderingBackend.INVALID
        }.launchIn(viewModelScope)

        prefs.getRowNumbersFlow().onEach { show ->
            state.update { it.copy(showRowNumbers = show) }
        }.launchIn(viewModelScope)

        prefs.getPlayerViewFlow().onEach { view ->
            val playerView = PlayerView.entries.getOrElse(view) { PlayerView.PATTERN }
            state.update { it.copy(playerView = playerView) }
        }.launchIn(viewModelScope)

        prefs.getGlobalSubSongsFlow().onEach { subsongs ->
            state.update { it.copy(playAllSequences = subsongs) }
        }.launchIn(viewModelScope)

        engine.modVarsFlow.onEach { mv ->
            state.update {
                it.copy(
                    modVars = mv,
                    instruments = mv.instruments.toImmutableList(),
                    sequences = mv.seqData.toImmutableList(),
                )
            }
        }.launchIn(viewModelScope)

        engine.currentSequenceFlow.onEach { seq ->
            state.update { it.copy(currentSequence = seq) }
        }.launchIn(viewModelScope)

        player.queueFlow.onEach { syncFromPlayer() }.launchIn(viewModelScope)
    }

    /** Recomputes everything the Media3 surface knows about; cheap and idempotent. */
    private fun syncFromPlayer() {
        val queue = player.queueFlow.value
        val index = player.currentMediaItemIndex
        val status = when {
            queue.isEmpty() -> PlaybackStatus.IDLE
            player.isPlaying -> PlaybackStatus.PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.LOADING
            else -> PlaybackStatus.PAUSED
        }
        val repeat = player.repeatMode
        state.update {
            it.copy(
                queue = queue,
                currentModule = queue.getOrNull(index),
                currentQueueIndex = index.coerceAtLeast(0),
                status = status,
                isShuffle = player.shuffleModeEnabled,
                repeatMode = repeat,
                hasNext =
                    queue.isNotEmpty() &&
                        (repeat != Player.REPEAT_MODE_OFF || index < queue.lastIndex),
                hasPrevious = queue.isNotEmpty() && (repeat != Player.REPEAT_MODE_OFF || index > 0),
                errorMessage = player.playerError?.message,
            )
        }
    }

    /** Current Oboe stream statistics; the UI formats them. */
    fun getAudioStats(): AudioStats = AudioStats().also(engine::getAudioStats)

    fun play(file: ModuleEntity) = player.loadQueue(listOf(file), startAt = 0)

    fun playAll(
        files: ImmutableList<ModuleEntity>,
        startAt: Int,
        isShuffle: Boolean,
        repeatMode: Int = state.value.repeatMode
    ) {
        if (files.isEmpty()) return
        player.loadQueue(
            files.toList(),
            startAt.coerceIn(0, files.lastIndex),
            isShuffle,
            repeatMode
        )
    }

    fun togglePlayPause() =
        if (state.value.status == PlaybackStatus.PLAYING) player.pause() else player.play()

    fun seek(posMs: Long) = player.seekTo(player.currentMediaItemIndex, posMs)

    fun next() = player.next()

    fun previous() = player.previous()

    /** The UI's stop also drops the queue; a controller/notification stop keeps it. */
    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    fun playAtIndex(index: Int) = player.jumpToIndex(index)

    fun toggleShuffle() {
        player.shuffleModeEnabled = !state.value.isShuffle
    }

    fun toggleLoop() {
        player.repeatMode = when (state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSequence(index: Int) = player.setSequence(index)

    /** The pref flow feeds both the engine and [state]. */
    fun toggleAllSequences() {
        val value = !state.value.playAllSequences
        viewModelScope.launch { prefs.setGlobalSubSongs(value) }
    }

    fun onPlayerView() {
        val next = (state.value.playerView.ordinal + 1) % PlayerView.entries.size
        viewModelScope.launch { prefs.setPlayerView(next) }
    }

    fun getPatternData(patternIndex: Int) = engine.getPatternData(patternIndex)

    /** Only the pref changes here; the engine switches backends before its next load. */
    fun selectBackend(backend: RenderingBackend) {
        viewModelScope.launch { prefs.setRenderingBackend(backend) }
    }

    override fun onCleared() {
        player.removeListener(playerListener)
    }
}
