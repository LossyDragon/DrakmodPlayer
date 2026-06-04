package com.lossydragon.modplayer.player.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class FrameSnapshot(
    val bpm: Int = 0,
    val channels: ImmutableList<ChannelSnapshot> = persistentListOf(),
    val numRows: Int = 0,
    val pattern: Int = 0,
    val position: Int = 0,
    val row: Int = 0,
    val speed: Int = 0,
    val timeMs: Int = 0,
    val totalTimeMs: Int = 0
)
