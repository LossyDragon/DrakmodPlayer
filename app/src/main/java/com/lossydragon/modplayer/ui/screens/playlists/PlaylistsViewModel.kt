package com.lossydragon.modplayer.ui.screens.playlists

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lossydragon.modplayer.data.PlaylistRepository
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.util.UiText
import com.lossydragon.modplayer.util.toUiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [error] is only set when loading playlists/entries fails; operation failures go to [PlaylistsViewModel.events]. */
data class PlaylistsUiState(
    val playlists: ImmutableList<PlaylistEntity> = persistentListOf(),
    val selectedPlaylist: PlaylistEntity? = null,
    val entries: ImmutableList<ModuleEntity> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val importResult: ImportResultUi? = null,
    val exportSuccess: Boolean = false
)

data class ImportResultUi(
    val playlistsImported: Int,
    val entriesImported: Int,
    val skipped: Int
)

/** One-shot notifications; the UI shows them as a snackbar/toast. */
sealed interface PlaylistsEvent {
    data class Error(val message: UiText) : PlaylistsEvent
}

class PlaylistsViewModel(
    private val repo: PlaylistRepository
) : ViewModel() {

    val state: StateFlow<PlaylistsUiState>
        field = MutableStateFlow(PlaylistsUiState())

    private val _events = Channel<PlaylistsEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistsEvent> = _events.receiveAsFlow()

    init {
        repo.playlists
            .onEach { playlists ->
                state.update { it.copy(playlists = playlists.toImmutableList()) }
            }
            .launchIn(viewModelScope)
    }

    /** Creates a new empty playlist with [name]. */
    fun createPlaylist(name: String, comment: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.createPlaylist(name.trim(), comment.trim()) }
                .onFailure { reportError(it) }
        }
    }

    /** Renames [playlist] to [newName]. */
    fun renamePlaylist(playlist: PlaylistEntity, newName: String, newComment: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repo.renamePlaylist(playlist.id, newName.trim(), newComment.trim())
            }.onFailure { reportError(it) }
        }
    }

    /** Deletes [playlist] and all its entries. */
    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            runCatching { repo.deletePlaylist(playlist.id) }
                .onSuccess {
                    if (state.value.selectedPlaylist?.id == playlist.id) deselectPlaylist()
                }
                .onFailure { reportError(it) }
        }
    }

    /** Selects [playlist] and loads its entries. */
    fun selectPlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true, selectedPlaylist = playlist, error = null) }
            runCatching { repo.getPlaylistFiles(playlist.id).toImmutableList() }
                .onSuccess { files ->
                    state.update { it.copy(entries = files, isLoading = false) }
                }
                .onFailure { e ->
                    state.update { it.copy(error = e.message, isLoading = false) }
                }
        }
    }

    /** Adds [file] to [playlist] unless already present. Refreshes entries if [playlist] is selected. */
    fun addToPlaylist(playlist: PlaylistEntity, file: ModuleEntity) {
        viewModelScope.launch {
            runCatching { repo.addToPlaylist(playlist.id, file) }
                .onSuccess { added ->
                    if (added > 0 && state.value.selectedPlaylist?.id == playlist.id) {
                        refreshEntries()
                    }
                }
                .onFailure { reportError(it) }
        }
    }

    /** Removes [file] from the currently selected playlist. */
    fun removeFromPlaylist(file: ModuleEntity) {
        val playlist = state.value.selectedPlaylist ?: return
        viewModelScope.launch {
            runCatching { repo.removeFromPlaylist(playlist.id, file.filePath) }
                .onSuccess { refreshEntries() }
                .onFailure { reportError(it) }
        }
    }

    /** Moves entry at [fromIndex] to [toIndex] in the selected playlist. */
    fun reorderEntry(fromIndex: Int, toIndex: Int) {
        val playlist = state.value.selectedPlaylist ?: return
        val entries = state.value.entries.toMutableList()
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return

        entries.add(toIndex, entries.removeAt(fromIndex))

        // Optimistic update
        state.update { it.copy(entries = entries.toImmutableList()) }

        viewModelScope.launch {
            runCatching { repo.reorderEntries(playlist.id, entries) }
                .onFailure { e ->
                    reportError(e)
                    refreshEntries() // revert on failure
                }
        }
    }

    /** Clears the selected playlist. */
    fun deselectPlaylist() {
        state.update { it.copy(selectedPlaylist = null, entries = persistentListOf()) }
    }

    /**
     * Exports all playlists to [outputUri].
     * Call after the user picks a file via SAF.
     */
    fun exportPlaylists(outputUri: Uri) {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true) }
            runCatching { withContext(Dispatchers.IO) { repo.exportTo(outputUri) } }
                .onSuccess { state.update { it.copy(isLoading = false, exportSuccess = true) } }
                .onFailure { e ->
                    state.update { it.copy(isLoading = false) }
                    reportError(e)
                }
        }
    }

    /**
     * Imports playlists from [inputUri].
     * Call after the user picks a file via SAF.
     * @param validateUris Whether to skip entries whose URIs are no longer accessible.
     */
    fun importPlaylists(inputUri: Uri, validateUris: Boolean = true) {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true) }
            runCatching { withContext(Dispatchers.IO) { repo.importFrom(inputUri, validateUris) } }
                .onSuccess { result ->
                    state.update {
                        it.copy(
                            isLoading = false,
                            importResult = ImportResultUi(
                                playlistsImported = result.playlistsImported,
                                entriesImported = result.entriesImported,
                                skipped = result.skipped,
                            )
                        )
                    }
                }
                .onFailure { e ->
                    state.update { it.copy(isLoading = false) }
                    reportError(e)
                }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    fun dismissImportResult() = state.update { it.copy(importResult = null) }

    fun dismissExportSuccess() = state.update { it.copy(exportSuccess = false) }

    private suspend fun reportError(e: Throwable) {
        _events.send(PlaylistsEvent.Error(e.toUiText()))
    }

    private fun refreshEntries() {
        val playlist = state.value.selectedPlaylist ?: return
        viewModelScope.launch {
            runCatching { repo.getPlaylistFiles(playlist.id).toImmutableList() }
                .onSuccess { files -> state.update { it.copy(entries = files) } }
                .onFailure { e -> state.update { s -> s.copy(error = e.message) } }
        }
    }
}
