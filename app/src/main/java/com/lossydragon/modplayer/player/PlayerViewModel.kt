package com.lossydragon.modplayer.player

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.native.Player as NativePlayer
import com.lossydragon.native.RenderingBackend
import com.lossydragon.native.model.AudioStats
import com.lossydragon.native.model.FrameInfo
import com.lossydragon.native.model.ModVars
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/** UI state and domain models for the module player. */

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED }

private const val SCREEN_COUNT = 3

@Immutable
data class PlayerUiState(
    val currentModule: ModuleEntity? = null,
    val errorMessage: String? = null,
    val playAllSequences: Boolean = false,
    val queue: ImmutableList<ModuleEntity> = persistentListOf(),
    val showRowNumbers: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val playerView: Int = 0,
    val modVars: ModVars = ModVars(),
    val frameInfo: FrameInfo = FrameInfo(),
    val currentSequence: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentQueueIndex: Int = 0,
    val isShuffle: Boolean = false
)

/** Bridges [Player] state to the UI via [PlayerUiState]. */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val appContext: Context,
    private val player: ModPlayer,
    private val prefs: AppPreferences
) : ViewModel() {

    val state: StateFlow<PlayerUiState>
        field = MutableStateFlow(PlayerUiState())

    val needsBackendSetup: StateFlow<Boolean>
        field = MutableStateFlow(false)

    init {
        prefs.getRenderingBackendFlow().onEach { backend ->
            needsBackendSetup.value = (backend == RenderingBackend.INVALID)
        }.launchIn(viewModelScope)
        prefs.getRowNumbersFlow().onEach { show ->
            state.update { it.copy(showRowNumbers = show) }
        }.launchIn(viewModelScope)

        combine(player.modVarsFlow, player.frameInfoFlow) { mv, fi -> mv to fi }
            .onEach { (mv, fi) ->
                state.update { it.copy(modVars = mv, frameInfo = fi) }
            }.launchIn(viewModelScope)

        player.currentSequenceFlow.onEach { seq ->
            Timber.d("currentSequenceFlow fired, seq=$seq")
            state.update { it.copy(currentSequence = seq) }
        }.launchIn(viewModelScope)

        player.isPlaying.onEach { playing ->
            Timber.d("isPlaying fired, isPlaying=$playing")
            state.update {
                it.copy(
                    status = when {
                        playing -> PlaybackStatus.PLAYING
                        it.currentModule != null -> PlaybackStatus.PAUSED
                        else -> PlaybackStatus.IDLE
                    }
                )
            }
        }.launchIn(viewModelScope)

        player.queueFlow.onEach { queue ->
            Timber.d("queueFlow fired, size=${queue.size}")
            val empty = queue.isEmpty()
            state.update {
                it.copy(
                    queue = queue.toImmutableList(),
                    currentModule = if (empty) null else it.currentModule,
                    status = if (empty) PlaybackStatus.IDLE else it.status,
                )
            }
        }.launchIn(viewModelScope)

        player.currentIndexFlow.onEach { index ->
            val file = player.queueFlow.value.getOrNull(index) ?: return@onEach
            state.update {
                it.copy(
                    currentModule = file,
                    currentQueueIndex = index,
                    currentSequence = if (player.isReordering) it.currentSequence else 0,
                )
            }
        }.launchIn(viewModelScope)

        player.moduleLoadedFlow.onEach {
            Timber.d("moduleLoadedFlow fired")
            syncModuleInfo()
        }.launchIn(viewModelScope)

        prefs.getPlayerViewFlow().onEach { view ->
            state.update { it.copy(playerView = view) }
        }.launchIn(viewModelScope)

        runBlocking {
            // Meh...
            val view = prefs.getPlayerViewFlow().first()
            val subsongs = prefs.getGlobalShuffleFlow().first()
            player.setPlayAllSequences(subsongs)
            state.update { it.copy(playerView = view, playAllSequences = subsongs) }
        }
    }

    /** Returns a formatted string of current Oboe audio stream statistics. */
    fun getAudioStats(): String {
        val stats = AudioStats()
        player.getAudioStats(stats)
        return """
                Audio API: ${stats.audioApi}
                Audio Format: ${stats.audioFormat}
                Audio Glitches: ${stats.xrunCount} (system), ${stats.underrunCount} (app)
                Buffer: ${stats.bufferSize} / ${stats.bufferCapacity} frames
                Frames Per Burst: ${stats.framesPerBurst}
                Performance Mode: ${stats.perfMode}
                Sample Rate: ${stats.sampleRate} Hz
                Sharing Mode: ${stats.sharingMode}
        """.trimIndent()
    }

    /** Loads [file] as a single-item queue and starts playback. */
    fun play(file: ModuleEntity) {
        state.update {
            it.copy(
                status = PlaybackStatus.LOADING,
                currentModule = file,
            )
        }
        ensureServiceRunning()
        player.loadQueue(listOf(file), startAt = 0)
    }

    /** Loads [files] as a queue, optionally shuffled, and starts playback at [startAt]. */
    fun playAll(
        files: ImmutableList<ModuleEntity>,
        startAt: Int,
        isShuffle: Boolean,
        repeatMode: Int = state.value.repeatMode
    ) {
        if (files.isEmpty()) return

        val startIndex = startAt.coerceIn(0, files.lastIndex)

        state.update {
            it.copy(
                status = PlaybackStatus.LOADING,
                currentModule = files[startIndex],
                isShuffle = isShuffle,
                repeatMode = repeatMode,
            )
        }

        ensureServiceRunning()

        player.loadQueue(
            files = files.toList(),
            startAt = startIndex,
            shuffleMode = isShuffle,
            repeatMode = repeatMode
        )
    }

    fun togglePlayPause() = if (state.value.status == PlaybackStatus.PLAYING) {
        player.pause()
    } else {
        player.play()
    }

    fun seek(posMs: Long) = player.seekTo(player.currentMediaItemIndex, posMs)

    fun next() = player.next()

    fun previous() = player.previous()

    fun stop() = player.stop()

    fun playAtIndex(index: Int) = player.jumpToIndex(index)

    fun toggleShuffle() {
        val newShuffle = !state.value.isShuffle
        player.shuffleModeEnabled = newShuffle
        state.update { it.copy(isShuffle = newShuffle) }
    }

    fun toggleLoop() {
        val newMode = when (state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.setRepeatMode(newMode)
        state.update { it.copy(repeatMode = newMode) }
    }

    fun setSequence(index: Int) = player.setSequence(index)

    fun toggleAllSequences() {
        val playAllSequences = !state.value.playAllSequences
        player.setPlayAllSequences(playAllSequences)
        viewModelScope.launch { prefs.setGlobalSubSongs(playAllSequences) }
        state.update { it.copy(playAllSequences = playAllSequences) }
    }

    fun onPlayerView() {
        val next = (state.value.playerView + 1) % SCREEN_COUNT
        viewModelScope.launch { prefs.setPlayerView(next) }
    }

    fun getPatternData(patternIndex: Int) = player.getPatternData(patternIndex)

    suspend fun getLastDirectoryUri(): String? = prefs.getLastDirectoryUri()

    private fun ensureServiceRunning() =
        Intent(appContext, PlayerService::class.java).also(appContext::startService)

    fun selectBackend(backend: RenderingBackend) {
        viewModelScope.launch {
            prefs.setRenderingBackend(backend)
            NativePlayer.switchBackend(backend)
        }
    }

    private fun syncModuleInfo() {
        state.update { it.copy(currentSequence = 0) }
    }
}
