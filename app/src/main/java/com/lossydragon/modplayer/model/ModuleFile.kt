package com.lossydragon.modplayer.model

import android.net.Uri
import androidx.compose.runtime.*
import com.lossydragon.modplayer.core.UriSerializer
import kotlinx.serialization.Serializable

/**
 * Represents a tracker module file on device storage.
 * [resolvedName] and [resolvedType] are populated from the Room cache
 * via libxmp's test result — empty until indexed.
 */

@Serializable
@Immutable
data class ModuleFile(
    @Serializable(with = UriSerializer::class)
    val uri: Uri,
    val extension: String,
    val name: String,
    val resolvedName: String = "",
    val resolvedType: String = "",
    val sizeBytes: Long
)
