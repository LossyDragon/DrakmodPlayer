package com.lossydragon.modplayer.player.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class FrameSnapshot(
    val bpm: Int,
    val channels: ImmutableList<ChannelSnapshot>,
    val numRows: Int,
    val pattern: Int,
    val position: Int,
    val row: Int,
    val speed: Int,
    val timeMs: Int,
    val totalTimeMs: Int
)
