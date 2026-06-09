package com.lossydragon.modplayer.ui.screens.browser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.lossydragon.modplayer.data.ModuleRepository
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.util.takeReadWritePermission
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    val loadingReason: String? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sortOrder: BrowserSortOrder = BrowserSortOrder.NAME
)

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModel(
    private val appContext: Context,
    private val prefs: AppPreferences,
    private val db: ModuleRepository
) : ViewModel() {

    val state: StateFlow<BrowserUiState>
        field = MutableStateFlow(BrowserUiState())

    private val currentPath = MutableStateFlow<String?>(null)
    private val currentChildren = MutableStateFlow<List<ModuleEntity>>(emptyList())

    // Stack of (uri string, display name) for breadcrumb navigation
    private val dirStack = ArrayDeque<Pair<String, String>>()

    init {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true, loadingReason = "Loading...") }

            val savedUri = prefs.getLastDirectoryUri()
            if (savedUri == null) {
                state.update { it.copy(isLoading = false, hasStorageAccess = false) }
                return@launch
            }

            pushDir(savedUri, displayName(savedUri))
            state.update { it.copy(hasStorageAccess = true) }

            launch(Dispatchers.IO) {
                db.indexDirectory(savedUri.toUri())
                state.update { it.copy(isLoading = false) }
            }
        }

        currentPath
            .filterNotNull()
            .flatMapLatest { path -> db.getChildren(path) }
            .onEach { children ->
                currentChildren.value = children
                applyFilters()
            }
            .launchIn(viewModelScope)

        prefs.getGlobalShuffleFlow().onEach { isShuffle ->
            state.update { it.copy(isShuffle = isShuffle) }
        }.launchIn(viewModelScope)

        runBlocking {
            // Meh...
            val isShuffle = prefs.getGlobalShuffleFlow().first()
            state.update { it.copy(isShuffle = isShuffle) }
        }
    }

    fun onRootFolderPicked(uri: Uri) {
        state.update {
            it.copy(
                isLoading = true,
                loadingReason = "Indexing...",
                hasStorageAccess = true
            )
        }

        appContext.takeReadWritePermission(uri)
        viewModelScope.launch { prefs.setLastDirectoryUri(uri.toString()) }

        dirStack.clear()
        pushDir(uri.toString(), displayName(uri.toString()))

        viewModelScope.launch(Dispatchers.IO) {
            db.indexDirectory(uri)
            state.update { it.copy(isLoading = false) }
        }
    }

    fun onRefresh() {
        val rootUri = dirStack.firstOrNull()?.first?.toUri() ?: return
        state.update { it.copy(isLoading = true, loadingReason = "Reindexing...") }
        viewModelScope.launch(Dispatchers.IO) {
            db.reindexDirectory(rootUri)
            state.update { it.copy(isLoading = false) }
        }
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

    fun setRepeatMode(mode: Int) {
        state.update { it.copy(repeatMode = mode) }
    }

    fun setSortOrder(order: BrowserSortOrder) {
        state.update { it.copy(sortOrder = order) }
        applyFilters()
    }

    fun setFilter(query: String) {
        state.update { it.copy(filterQuery = query) }
        applyFilters()
    }

    private fun pushDir(path: String, name: String) {
        dirStack.addLast(path to name)
        currentPath.value = path
        updateBreadcrumbs()
    }

    private fun updateBreadcrumbs() {
        state.update {
            it.copy(breadcrumbs = dirStack.map { (_, name) -> name }.toImmutableList())
        }
    }

    private fun applyFilters() {
        val children = currentChildren.value
        val query = state.value.filterQuery
        val order = state.value.sortOrder

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

        state.update { it.copy(directories = dirs, files = files) }
    }

    suspend fun getRecursiveModules(): List<ModuleEntity> {
        val path = currentPath.value ?: return emptyList()
        return db.getDescendantModules(path)
    }

    private fun displayName(path: String): String = Uri.decode(path)
        .substringAfterLast('/')
        .substringAfterLast(':')
        .ifBlank { "Root" }
}
