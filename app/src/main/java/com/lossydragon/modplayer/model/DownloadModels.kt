package com.lossydragon.modplayer.model

import androidx.compose.runtime.Immutable

enum class SearchType { ARTIST, TITLE }

/** UI state and domain models for the ModArchive download feature. */

sealed class SearchResult {
    data class Artists(val data: ArtistResult) : SearchResult()
    data class Modules(val data: SearchListResult) : SearchResult()
}

@Immutable
data class DownloadSearchState(
    val error: String? = null,
    val isLoading: Boolean = false,
    val result: SearchResult? = null,
    val title: String = ""
)

@Immutable
sealed class DownloadStatus {
    data class Error(val message: String) : DownloadStatus()
    data class Progress(val percent: Float) : DownloadStatus()
    data object Loading : DownloadStatus()
    data object None : DownloadStatus()
    data object Success : DownloadStatus()
}

@Immutable
data class ModuleResultState(
    val downloadStatus: DownloadStatus = DownloadStatus.None,
    val hardError: String? = null,
    val isLoading: Boolean = false,
    val isRandom: Boolean = false,
    val moduleExists: Boolean = false,
    val result: ModuleResult? = null,
    val softError: String? = null
)
