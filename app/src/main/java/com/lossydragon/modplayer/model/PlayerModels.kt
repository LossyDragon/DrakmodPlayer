package com.lossydragon.modplayer.model

import androidx.compose.runtime.*
import androidx.media3.common.Player
import com.lossydragon.modplayer.player.model.FrameSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** UI state and domain models for the module player. */

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED }

@Immutable
data class PlayerUiState(
    val currentModule: ModuleFile? = null,
    val currentQueueIndex: Int = 0,
    val currentSequence: Int = 0,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val frame: FrameSnapshot? = null,
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
    val queue: ImmutableList<ModuleFile> = persistentListOf(),
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sequenceDurations: ImmutableList<Int> = persistentListOf(),
    val showRowNumbers: Boolean = false,
    val songInstruments: ImmutableList<String> = persistentListOf(),
    val songMessage: String = "",
    val status: PlaybackStatus = PlaybackStatus.IDLE
)
