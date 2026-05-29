package com.lossydragon.modplayer.model

data class QueueState(
    val index: Int,
    val json: String,
    val positionMs: Long,
    val repeat: Int,
    val shuffle: Boolean
)
