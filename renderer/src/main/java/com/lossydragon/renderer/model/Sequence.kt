package com.lossydragon.renderer.model

import androidx.compose.runtime.Immutable

@Immutable
data class Sequence(
    val entryPoint: Int = 0,
    val duration: Int = 0,
)
