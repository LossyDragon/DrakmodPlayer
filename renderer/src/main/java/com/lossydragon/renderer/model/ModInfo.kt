package com.lossydragon.renderer.model

import androidx.compose.runtime.Immutable

/**
 * @see [com.lossydragon.renderer.Player.testFromFd]
 */
@Immutable
data class ModInfo(val name: String = "", val type: String = "")
