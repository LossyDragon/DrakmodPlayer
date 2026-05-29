package com.lossydragon.modplayer.player.model

import androidx.compose.runtime.Immutable

@Immutable
data class ChannelSnapshot(
    val finalVol: Int,
    val instrument: Int,
    val note: Int,
    val pan: Int,
    val period: Int,
    val volume: Int
)
