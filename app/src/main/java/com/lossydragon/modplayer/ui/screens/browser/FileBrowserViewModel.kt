package com.lossydragon.modplayer.ui.screens.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.data.ModuleMetadataRepository
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.model.BrowserSortOrder
import com.lossydragon.modplayer.model.BrowserUiState
import com.lossydragon.modplayer.model.FileItem
import com.lossydragon.modplayer.model.ModuleFile
import com.lossydragon.modplayer.util.queryDirectoryEntries
import com.lossydragon.modplayer.util.resolveDocId
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FileBrowserViewModel(
    private val appContext: Context,
    private val prefs: AppPreferences,
    private val db: ModuleMetadataRepository
) : ViewModel() {

    val state: StateFlow<BrowserUiState>
        field = MutableStateFlow(BrowserUiState())

    private val dirStack = ArrayDeque<Uri>()
    private var rootTreeUri: Uri? = null

    init {
        viewModelScope.launch {
            prefs.getLastDirectoryUri()?.let { savedUri ->
                state.value = state.value.copy(isLoading = true)
                onRootFolderPicked(savedUri.toUri())
            } ?: run {
                state.value = state.value.copy(isLoading = false)
            }
        }
    }

    fun onRootFolderPicked(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        viewModelScope.launch { prefs.setLastDirectoryUri(uri.toString()) }
        state.update { it.copy(isLoading = true) }

        rootTreeUri = uri
        dirStack.clear()
        dirStack.addLast(uri)

        // Wait until we're fully done.
        // loadDirectory(uri)

        // Background index entire tree
        viewModelScope.launch(Dispatchers.IO) {
            indexDirectory(uri)
            loadDirectory(uri)
        }
    }

    fun navigateInto(item: FileItem) {
        dirStack.addLast(item.uri)
        state.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            indexDirectory(item.uri)
            loadDirectory(item.uri)
        }
    }

    fun navigateUp(): Boolean {
        if (dirStack.size <= 1) return false
        dirStack.removeLast()
        loadDirectory(dirStack.last())
        return true
    }

    fun canNavigateUp() = dirStack.size > 1

    fun setShuffle(value: Boolean) {
        state.value = state.value.copy(isShuffle = value)
    }

    fun setRepeatMode(mode: Int) {
        state.value = state.value.copy(repeatMode = mode)
    }

    fun navigateToBreadcrumb(index: Int) {
        while (dirStack.size > index + 1) dirStack.removeLast()
        loadDirectory(dirStack.last())
    }

    fun setSortOrder(order: BrowserSortOrder) {
        state.value = state.value.copy(sortOrder = order)
        dirStack.lastOrNull()?.let { loadDirectory(it) }
    }

    fun setFilter(query: String) {
        state.value = state.value.copy(filterQuery = query)
        dirStack.lastOrNull()?.let { loadDirectory(it) }
    }

    fun clearFilter() {
        state.value = state.value.copy(filterQuery = "")
        dirStack.lastOrNull()?.let { loadDirectory(it) }
    }

    private fun loadDirectory(uri: Uri) {
        state.value = state.value.copy(isLoading = true, error = null)
        val filterQuery = state.value.filterQuery
        val sortOrder = state.value.sortOrder

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val treeRoot = rootTreeUri ?: uri
                val entries = appContext.contentResolver
                    .queryDirectoryEntries(treeRoot, uri.resolveDocId(appContext))

                data class RawFile(val uri: Uri, val name: String, val size: Long, val ext: String)

                val directories = mutableListOf<FileItem>()
                val rawFiles = mutableListOf<RawFile>()

                for (entry in entries) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    val prefix = entry.name.substringBefore('.').lowercase()
                    when {
                        entry.isDirectory ->
                            directories.add(
                                FileItem(
                                    name = entry.name,
                                    uri = entry.childUri,
                                    isDirectory = true,
                                    size = 0L
                                )
                            )

                        ext in Constants.UNSUPPORTED_EXTENSIONS ||
                            prefix in Constants.UNSUPPORTED_EXTENSIONS -> Unit

                        ext !in Constants.SKIP_EXTENSIONS &&
                            prefix !in Constants.SKIP_EXTENSIONS ->
                            rawFiles.add(
                                RawFile(
                                    uri = entry.childUri,
                                    name = entry.name,
                                    size = entry.size,
                                    ext = ext.ifEmpty { prefix }
                                )
                            )
                    }
                }

                // Single batch DB query instead of N individual queries
                val cachedMap = db.getByFileNames(rawFiles.map { it.name })
                    .associateBy { it.fileName }

                val modules = rawFiles.map { raw ->
                    val cached = cachedMap[raw.name]
                    ModuleFile(
                        uri = raw.uri,
                        name = raw.name,
                        sizeBytes = raw.size,
                        extension = raw.ext,
                        resolvedName = cached?.name ?: "",
                        resolvedType = cached?.type ?: "",
                    )
                }

                val filtered = modules.filter { file ->
                    val name = file.resolvedName.ifBlank { file.name }
                    filterQuery.isBlank() || name.contains(filterQuery, ignoreCase = true)
                }.sortedWith(
                    when (sortOrder) {
                        BrowserSortOrder.NAME -> compareBy {
                            it.resolvedName.ifBlank { it.name }.lowercase()
                        }

                        BrowserSortOrder.TYPE -> compareBy {
                            it.resolvedType.ifBlank { it.extension }.lowercase()
                        }

                        BrowserSortOrder.SIZE -> compareBy { it.sizeBytes }
                    }
                )

                state.value = state.value.copy(
                    currentPath = uri.lastPathSegment ?: "",
                    files = filtered.toImmutableList(),
                    directories = directories.sortedBy { it.name.lowercase() }.toImmutableList(),
                    breadcrumbs = dirStack.map {
                        it.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                            ?: "Root"
                    }.toImmutableList(),
                    isLoading = false,
                    hasStorageAccess = true,
                )
            } catch (e: Exception) {
                state.value = state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private suspend fun indexDirectory(uri: Uri) {
        val treeRoot = rootTreeUri ?: uri
        val entries = appContext.contentResolver
            .queryDirectoryEntries(treeRoot, uri.resolveDocId(appContext))

        for (entry in entries) {
            if (entry.isDirectory) continue
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            val prefix = entry.name.substringBefore('.').lowercase()
            when {
                ext in Constants.UNSUPPORTED_EXTENSIONS ||
                    prefix in Constants.UNSUPPORTED_EXTENSIONS -> Unit

                ext !in Constants.SKIP_EXTENSIONS &&
                    prefix !in Constants.SKIP_EXTENSIONS -> {
                    if (!db.exists(entry.name, entry.size)) {
                        db.fetchAndCache(
                            entry.childUri,
                            entry.name,
                            entry.size,
                            ext.ifEmpty {
                                prefix
                            }
                        )
                    }
                }
            }
        }
    }
}
