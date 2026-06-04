package com.lossydragon.modplayer.ui.screens.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lossydragon.modplayer.data.DownloadHistoryRepository
import com.lossydragon.modplayer.data.ModArchiveService
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.model.ArtistResult
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.model.ModuleResult
import com.lossydragon.modplayer.model.SearchListResult
import com.lossydragon.modplayer.util.findDownloadedModule
import com.lossydragon.modplayer.util.getOrCreateOutputFile
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

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
    val isLoading: Boolean = false,
    val isRandom: Boolean = false,
    val moduleExists: Boolean = false,
    val result: ModuleResult? = null,
    val softError: String? = null
)

class DownloadsViewModel(
    private val appContext: Context,
    private val service: ModArchiveService,
    private val httpClient: HttpClient,
    private val historyRepo: DownloadHistoryRepository,
    prefs: AppPreferences
) : ViewModel() {

    private var downloadJob: Job? = null
    private val lastDirectory: Flow<String?> = prefs.getLastDirectoryFlow()

    val moduleResultState: StateFlow<ModuleResultState>
        field = MutableStateFlow(ModuleResultState())

    val historyState: StateFlow<ImmutableList<Module>> = historyRepo.getAllFlow()
        .map { it.toPersistentList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val searchState: StateFlow<DownloadSearchState>
        field = MutableStateFlow(DownloadSearchState())

    fun getModuleById(id: Int) {
        if (id < 0) {
            getRandomModule()
            return
        }
        viewModelScope.launch {
            moduleResultState.update { it.copy(isLoading = true, isRandom = false) }
            service.getModuleById(id).fold(
                onSuccess = { result ->
                    moduleResultState.update {
                        it.copy(
                            result = result,
                            moduleExists = checkExists(result.module),
                            isLoading = false,
                            softError = null,
                        )
                    }
                    historyRepo.add(result.module)
                },
                onFailure = ::handleFailure
            )
        }
    }

    fun getRandomModule() {
        viewModelScope.launch {
            moduleResultState.update { it.copy(isLoading = true, isRandom = true) }
            service.getRandomModule().fold(
                onSuccess = { result ->
                    moduleResultState.update {
                        it.copy(
                            result = result,
                            moduleExists = checkExists(result.module),
                            isLoading = false,
                            softError = null,
                        )
                    }

                    historyRepo.add(result.module)
                },
                onFailure = ::handleFailure
            )
        }
    }

    fun downloadModule(module: Module) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                moduleResultState.update { it.copy(downloadStatus = DownloadStatus.Loading) }

                val rootUri = lastDirectory.firstOrNull()?.toUri() ?: run {
                    moduleResultState.update {
                        it.copy(downloadStatus = DownloadStatus.Error("No folder selected"))
                    }
                    return@launch
                }

                val outputFile = appContext.getOrCreateOutputFile(
                    rootUri = rootUri,
                    module = module
                ) ?: run {
                    moduleResultState.update {
                        it.copy(
                            downloadStatus = DownloadStatus.Error("Could not create output file")
                        )
                    }
                    return@launch
                }
                val response = httpClient.get(module.downloadUrl)
                val totalBytes = response.contentLength() ?: 0L
                val channel = response.bodyAsChannel()
                var bytesRead = 0L
                val buf = ByteArray(8192)

                outputFile.use { out ->
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buf, 0, buf.size)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            moduleResultState.update {
                                it.copy(
                                    downloadStatus = DownloadStatus.Progress(
                                        bytesRead * 100f / totalBytes
                                    )
                                )
                            }
                        }
                    }
                }
                moduleResultState.update {
                    it.copy(
                        downloadStatus = DownloadStatus.Success,
                        moduleExists = true
                    )
                }
            } catch (e: Exception) {
                Timber.e(e)
                moduleResultState.update {
                    it.copy(
                        downloadStatus = DownloadStatus.Error(
                            e.message ?: "Download failed"
                        )
                    )
                }
            }
        }
    }

    fun deleteModule(module: Module) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileUri = findModuleUri(module) ?: return@launch
                DocumentsContract.deleteDocument(appContext.contentResolver, fileUri)
                moduleResultState.update { it.copy(moduleExists = false) }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepo.clear()
        }
    }

    fun searchFileOrTitle(query: String) = viewModelScope.launch {
        searchState.update { it.copy(title = "Results: $query", isLoading = true, error = null) }
        service.searchByFileNameOrTitle(query).fold(
            onSuccess = { data ->
                searchState.update {
                    it.copy(
                        result = SearchResult.Modules(data),
                        isLoading = false
                    )
                }
            },
            onFailure = {
                Timber.e(it)
                searchState.update { s -> s.copy(error = it.message, isLoading = false) }
            }
        )
    }

    fun searchArtist(query: String) = viewModelScope.launch {
        searchState.update { it.copy(title = "Artists: $query", isLoading = true, error = null) }
        service.getArtistSearch(query).fold(
            onSuccess = { data ->
                searchState.update {
                    it.copy(
                        result = SearchResult.Artists(data),
                        isLoading = false
                    )
                }
            },
            onFailure = {
                Timber.e(it)
                searchState.update { s -> s.copy(error = it.message, isLoading = false) }
            }
        )
    }

    fun getArtistById(id: Int) = viewModelScope.launch {
        searchState.update { it.copy(isLoading = true, error = null) }
        service.getArtistById(id).fold(
            onSuccess = { data ->
                searchState.update {
                    it.copy(
                        result = SearchResult.Modules(data),
                        isLoading = false
                    )
                }
            },
            onFailure = {
                Timber.e(it)
                searchState.update { s -> s.copy(error = it.message, isLoading = false) }
            }
        )
    }

    private fun handleFailure(t: Throwable) {
        Timber.e(t)
        moduleResultState.update { it.copy(softError = t.message, isLoading = false) }
    }

    private suspend fun checkExists(module: Module) = findModuleUri(module) != null

    private suspend fun findModuleUri(module: Module): Uri? {
        val rootUri = (lastDirectory.firstOrNull() ?: return null).toUri()
        return appContext.findDownloadedModule(
            rootUri = rootUri,
            artist = module.artist,
            filename = module.url.substringAfterLast('#'),
        )
    }
}
