package com.lossydragon.modplayer.ui.screens.browser

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.data.IndexKind
import com.lossydragon.modplayer.data.IndexState
import com.lossydragon.modplayer.data.ModuleRepository
import com.lossydragon.modplayer.data.PlaylistRepository
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.util.UiText
import com.lossydragon.modplayer.util.toUiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val files: ImmutableList<ModuleEntity> = persistentListOf(),
    val filterQuery: String = "",
    val hasStorageAccess: Boolean = false,
    val isLoading: Boolean = true,
    val isShuffle: Boolean = false,
    @param:StringRes val loadingReason: Int? = null,
    val sortOrder: BrowserSortOrder = BrowserSortOrder.NAME
)

/** One-shot notifications; the UI shows them as a snackbar/toast. */
sealed interface BrowserEvent {
    /** [skipped] entries were already in the playlist. */
    data class AddedToPlaylist(val playlist: PlaylistEntity, val added: Int, val skipped: Int) :
        BrowserEvent

    data class Error(val message: UiText) : BrowserEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModel(
    private val prefs: AppPreferences,
    private val db: ModuleRepository,
    private val playlistRepo: PlaylistRepository
) : ViewModel() {

    val state: StateFlow<BrowserUiState>
        field = MutableStateFlow(BrowserUiState())

    val playlists: StateFlow<ImmutableList<PlaylistEntity>>
        field = MutableStateFlow<ImmutableList<PlaylistEntity>>(persistentListOf())

    private val _events = Channel<BrowserEvent>(Channel.BUFFERED)
    val events: Flow<BrowserEvent> = _events.receiveAsFlow()

    private val currentPath = MutableStateFlow<String?>(null)
    private val filterQuery = MutableStateFlow("")
    private val sortOrder = MutableStateFlow(BrowserSortOrder.NAME)

    // Stack of (uri string, display name) for breadcrumb navigation
    private val dirStack = ArrayDeque<Pair<String, String>>()

    init {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true, loadingReason = R.string.loading) }

            val savedUri = prefs.getLastDirectoryUri()
            if (savedUri != null) {
                pushDir(savedUri, displayName(savedUri))
                state.update { it.copy(hasStorageAccess = true) }
                db.indexDirectoryIfStale(savedUri.toUri())
            } else {
                state.update { it.copy(isLoading = false, hasStorageAccess = false) }
            }
            // Subscribed only now so the initial Idle doesn't flash an empty screen.
            db.indexState
                .onEach { indexState -> state.update { it.withIndexState(indexState) } }
                .launchIn(this)
        }

        val children = currentPath
            .filterNotNull()
            .flatMapLatest { path -> db.getChildren(path) }
            .conflate()

        combine(children, filterQuery, sortOrder) { c, q, o -> buildLists(c, q, o) }
            .flowOn(Dispatchers.Default)
            .onEach { (dirs, files) -> state.update { it.copy(directories = dirs, files = files) } }
            .launchIn(viewModelScope)

        prefs.getGlobalShuffleFlow().onEach { isShuffle ->
            state.update { it.copy(isShuffle = isShuffle) }
        }.launchIn(viewModelScope)

        playlistRepo.playlists
            .onEach { playlists.value = it.toImmutableList() }
            .launchIn(viewModelScope)
    }

    fun addFileToPlaylist(playlist: PlaylistEntity, file: ModuleEntity) {
        viewModelScope.launch {
            runCatching { playlistRepo.addToPlaylist(playlist.id, file) }
                .onSuccess { added ->
                    _events.send(BrowserEvent.AddedToPlaylist(playlist, added, 1 - added))
                }
                .onFailure { _events.send(BrowserEvent.Error(it.toUiText())) }
        }
    }

    fun addFolderToPlaylist(playlist: PlaylistEntity, folder: FileItem) {
        viewModelScope.launch {
            runCatching {
                val files = db.getDescendantModules(folder.uri.toString())
                val added = playlistRepo.addAllToPlaylist(playlist.id, files)
                BrowserEvent.AddedToPlaylist(playlist, added, files.size - added)
            }
                .onSuccess { _events.send(it) }
                .onFailure { _events.send(BrowserEvent.Error(it.toUiText())) }
        }
    }

    fun onRootFolderPicked(uri: Uri) {
        state.update {
            it.copy(
                isLoading = true,
                loadingReason = R.string.indexing,
                hasStorageAccess = true,
                error = null,
            )
        }

        dirStack.clear()
        pushDir(uri.toString(), displayName(uri.toString()))

        viewModelScope.launch {
            runCatching { db.setRootFolder(uri) }.onFailure { e ->
                state.update {
                    it.copy(isLoading = false, hasStorageAccess = false, error = e.message)
                }
            }
        }
    }

    fun onRefresh() {
        val rootUri = dirStack.firstOrNull()?.first?.toUri() ?: return
        db.reindexDirectory(rootUri)
    }

    fun navigateInto(item: FileItem) {
        pushDir(item.uri.toString(), item.name)
    }

    fun navigateUp(): Boolean {
        if (dirStack.size <= 1) return false
        dirStack.removeLast()
        currentPath.value = dirStack.last().first
        updateBreadcrumbs()
        return true
    }

    fun navigateToBreadcrumb(index: Int) {
        while (dirStack.size > index + 1) dirStack.removeLast()
        currentPath.value = dirStack.last().first
        updateBreadcrumbs()
    }

    fun setShuffle() {
        val value = !state.value.isShuffle
        viewModelScope.launch { prefs.setGlobalShuffle(value) }
    }

    fun setSortOrder(order: BrowserSortOrder) {
        sortOrder.value = order
        state.update { it.copy(sortOrder = order) }
    }

    fun setFilter(query: String) {
        filterQuery.value = query
        state.update { it.copy(filterQuery = query) }
    }

    suspend fun getRecursiveModules(): List<ModuleEntity> {
        val path = currentPath.value ?: return emptyList()
        return db.getDescendantModules(path)
    }

    private fun pushDir(path: String, name: String) {
        dirStack.addLast(path to name)
        currentPath.value = path
        updateBreadcrumbs()
    }

    private fun updateBreadcrumbs() {
        state.update {
            it.copy(
                currentPath = dirStack.lastOrNull()?.first.orEmpty(),
                breadcrumbs = dirStack.map { (_, name) -> name }.toImmutableList(),
            )
        }
    }

    private fun BrowserUiState.withIndexState(indexState: IndexState) = when (indexState) {
        IndexState.Idle -> copy(isLoading = false, loadingReason = null)

        is IndexState.Running -> copy(
            isLoading = true,
            error = null,
            loadingReason = when (indexState.kind) {
                IndexKind.INITIAL -> R.string.loading
                IndexKind.FULL -> R.string.indexing
                IndexKind.REFRESH -> R.string.reindexing
            },
        )

        IndexState.NoAccess -> copy(
            isLoading = false,
            loadingReason = null,
            hasStorageAccess = false
        )

        is IndexState.Error -> copy(
            isLoading = false,
            loadingReason = null,
            error = indexState.message
        )
    }

    /** Blank for the tree root; the UI substitutes a localised label. */
    private fun displayName(path: String): String = Uri.decode(path)
        .substringAfterLast('/')
        .substringAfterLast(':')

    private companion object {
        /** Pure; runs on [Dispatchers.Default]. */
        fun buildLists(
            children: List<ModuleEntity>,
            query: String,
            order: BrowserSortOrder
        ): Pair<ImmutableList<FileItem>, ImmutableList<ModuleEntity>> {
            val dirs = children
                .filter { it.isDirectory }
                .sortedBy { it.filename.lowercase() }
                .map { FileItem(name = it.filename, uri = it.uri, isDirectory = true, size = 0L) }
                .toImmutableList()

            val files = children
                .filter { !it.isDirectory && it.isValidModule }
                .filter { entity ->
                    query.isBlank() ||
                        entity.moduleName.contains(query, ignoreCase = true) ||
                        entity.filename.contains(query, ignoreCase = true)
                }
                .sortedWith(
                    when (order) {
                        BrowserSortOrder.NAME -> compareBy {
                            it.moduleName.ifBlank { it.filename }.lowercase()
                        }

                        BrowserSortOrder.TYPE -> compareBy { it.moduleType.lowercase() }

                        BrowserSortOrder.SIZE -> compareBy { it.fileSize }
                    }
                )
                .toImmutableList()

            return dirs to files
        }
    }
}
