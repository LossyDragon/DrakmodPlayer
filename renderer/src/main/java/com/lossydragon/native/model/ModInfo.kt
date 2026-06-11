package com.lossydragon.native.model

import androidx.compose.runtime.Immutable

/**
 * @see [com.lossydragon.native.Player.testFromFd]
 */
@Immutable
data class ModInfo(val name: String = "", val type: String = "")
