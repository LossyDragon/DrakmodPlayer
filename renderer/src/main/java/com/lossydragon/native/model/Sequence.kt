package com.lossydragon.native.model

import androidx.compose.runtime.Immutable

@Immutable
data class Sequence(
    val entryPoint: Int = 0,
    val duration: Int = 0,
)
