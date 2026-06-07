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
import com.lossydragon.modplayer.player.model.FrameSnapshot
import java.nio.charset.StandardCharsets
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.helllabs.libxmp.Xmp
import timber.log.Timber

/** UI state and domain models for the module player. */

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED }

@Immutable
data class PlayerUiState(
    val currentModule: ModuleEntity? = null,
    val currentQueueIndex: Int = 0,
    val currentSequence: Int = 0,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val frame: FrameSnapshot = FrameSnapshot(),
    val isShuffle: Boolean = false,
    val moduleName: String = "",
    val moduleType: String = "",
    val numChannels: Int = 0,
    val numInstruments: Int = 0,
    val numPatterns: Int = 0,
    val numSamples: Int = 0,
    val numSequences: Int = 0,
    val playAllSequences: Boolean = false,
    val positionMs: Long = 0L,
    val queue: ImmutableList<ModuleEntity> = persistentListOf(),
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sequenceDurations: ImmutableList<Int> = persistentListOf(),
    val showRowNumbers: Boolean = false,
    val songInstruments: ImmutableList<String> = persistentListOf(),
    val songMessage: String = "",
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val playerView: Int = 0
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

    init {
        prefs.getRowNumbersFlow().onEach { show ->
            state.update { it.copy(showRowNumbers = show) }
        }.launchIn(viewModelScope)

        player.frameFlow.onEach { frame ->
            frame ?: return@onEach
            state.update {
                it.copy(
                    positionMs = frame.timeMs.toLong(),
                    durationMs = frame.totalTimeMs.toLong(),
                    frame = frame,
                )
            }
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
                    frame = if (empty) FrameSnapshot() else it.frame,
                )
            }
        }.launchIn(viewModelScope)

        player.currentIndexFlow.onEach { index ->
            val file = player.queueFlow.value.getOrNull(index) ?: return@onEach
            state.update {
                it.copy(
                    currentModule = file,
                    currentQueueIndex = index,
                    moduleName = if (player.isReordering) it.moduleName else file.displayName(),
                    moduleType = if (player.isReordering) it.moduleType else file.displayType(),
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
            state.update { it.copy(playerView = view) }
        }
    }

    /** Returns a formatted string of current Oboe audio stream statistics. */
    fun getAudioStats(): String = Xmp.getAudioStats().let { stats ->
        """
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
                moduleName = file.displayName(),
                moduleType = file.displayType(),
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
                moduleName = files[startIndex].displayName(),
                moduleType = files[startIndex].displayType(),
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
        state.update { it.copy(playAllSequences = playAllSequences) }
    }

    fun getModComment(): Boolean {
        val songMessageText = String(Xmp.getComment(), StandardCharsets.UTF_8)
        state.update { it.copy(songMessage = songMessageText) }
        return songMessageText.isNotBlank()
    }

    fun onPlayerView() {
        val next = (state.value.playerView + 1) % 2
        viewModelScope.launch { prefs.setPlayerView(next) }
    }

    fun closeModComment() = state.update { it.copy(songMessage = "") }

    fun getPatternData(patternIndex: Int) = player.getPatternData(patternIndex)

    suspend fun getLastDirectoryUri(): String? = prefs.getLastDirectoryUri()

    private fun ModuleEntity.displayName() =
        moduleName.ifBlank { filename.ifBlank { "(Untitled)" } }

    private fun ModuleEntity.displayType() =
        moduleType.ifBlank { fileExtension.uppercase().ifBlank { "???" } }

    private fun ensureServiceRunning() =
        Intent(appContext, PlayerService::class.java).also(appContext::startService)

    private fun syncModuleInfo() {
        state.update {
            it.copy(
                sequenceDurations = player.sequenceDurations.toImmutableList(),
                currentSequence = 0,
                numPatterns = player.numPatterns,
                numChannels = player.numChannels,
                numInstruments = player.numInstruments,
                numSamples = player.numSamples,
                numSequences = player.numSequences,
                songInstruments = Xmp.getInstruments().toPersistentList(),
            )
        }
    }
}
