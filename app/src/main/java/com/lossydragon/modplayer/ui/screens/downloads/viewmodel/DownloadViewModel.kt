package com.lossydragon.modplayer.ui.screens.downloads.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lossydragon.modplayer.data.ModArchiveService
import com.lossydragon.modplayer.model.ArtistResult
import com.lossydragon.modplayer.model.ModuleResult
import com.lossydragon.modplayer.model.SearchListResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** UI state and domain models for the ModArchive download feature. */

enum class SearchType { ARTIST, TITLE }

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

class DownloadViewModel(
    private val service: ModArchiveService
) : ViewModel() {

    val state: StateFlow<DownloadSearchState>
        field = MutableStateFlow(DownloadSearchState())

    fun searchFileOrTitle(query: String) = viewModelScope.launch {
        state.update { it.copy(title = "Results: $query", isLoading = true, error = null) }
        service.searchByFileNameOrTitle(query).fold(
            onSuccess = {
                state.update { s -> s.copy(result = SearchResult.Modules(it), isLoading = false) }
            },
            onFailure = {
                Timber.e(it)
                state.update { s -> s.copy(error = it.message, isLoading = false) }
            }
        )
    }

    fun searchArtist(query: String) = viewModelScope.launch {
        state.update { it.copy(title = "Artists: $query", isLoading = true, error = null) }
        service.getArtistSearch(query).fold(
            onSuccess = {
                state.update { s -> s.copy(result = SearchResult.Artists(it), isLoading = false) }
            },
            onFailure = {
                Timber.e(it)
                state.update { s -> s.copy(error = it.message, isLoading = false) }
            }
        )
    }

    fun getArtistById(id: Int) = viewModelScope.launch {
        state.update { it.copy(isLoading = true, error = null) }
        service.getArtistById(id).fold(
            onSuccess = {
                state.update { s -> s.copy(result = SearchResult.Modules(it), isLoading = false) }
            },
            onFailure = {
                Timber.e(it)
                state.update { s -> s.copy(error = it.message, isLoading = false) }
            }
        )
    }
}
