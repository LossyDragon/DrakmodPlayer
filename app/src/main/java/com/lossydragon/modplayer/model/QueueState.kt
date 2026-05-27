package com.lossydragon.modplayer.model

data class QueueState(
    val json: String,
    val index: Int,
    val shuffle: Boolean,
    val repeat: Int,
    val positionMs: Long
)
