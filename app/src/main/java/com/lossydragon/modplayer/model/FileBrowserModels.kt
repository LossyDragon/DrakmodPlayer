package com.lossydragon.modplayer.model

import android.net.Uri
import androidx.compose.runtime.*
import androidx.media3.common.Player
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** UI state and domain models for the SAF file browser. */

enum class BrowserSortOrder { NAME, TYPE, SIZE }

@Immutable
data class FileItem(
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val uri: Uri
)

@Immutable
data class BrowserUiState(
    val breadcrumbs: ImmutableList<String> = persistentListOf(),
    val currentPath: String = "",
    val directories: ImmutableList<FileItem> = persistentListOf(),
    val error: String? = null,
    val files: ImmutableList<ModuleFile> = persistentListOf(),
    val filterQuery: String = "",
    val hasStorageAccess: Boolean = false,
    val isFolderTraversal: Boolean = true, // Maybe add an option to turn off traversal.
    val isLoading: Boolean = true,
    val isShuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sortOrder: BrowserSortOrder = BrowserSortOrder.NAME
)
