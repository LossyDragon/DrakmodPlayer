package com.lossydragon.modplayer.ui.screens.downloads

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.data.DownloadHistoryRepository
import com.lossydragon.modplayer.data.ModArchiveService
import com.lossydragon.modplayer.data.ModuleRepository
import com.lossydragon.modplayer.data.ResultPagingSource
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.model.ModuleResult
import com.lossydragon.modplayer.model.ResultItem
import com.lossydragon.modplayer.util.createPartialDownload
import com.lossydragon.modplayer.util.discardPartialDownload
import com.lossydragon.modplayer.util.finalizeDownload
import com.lossydragon.modplayer.util.findDownloadedModule
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class SearchType { ARTIST, TITLE }

enum class SearchResultType { NONE, MODULES, ARTISTS }

private sealed interface SearchCommand {
    data object Idle : SearchCommand
    data class FileOrTitle(val query: String) : SearchCommand
    data class Artist(val query: String) : SearchCommand
    data class ArtistById(val id: Int) : SearchCommand
}

@Immutable
data class DownloadSearchState(
    val title: String = "",
    val activeType: SearchResultType = SearchResultType.NONE,
    val isLoading: Boolean = false
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

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    private val appContext: Context,
    private val service: ModArchiveService,
    private val httpClient: HttpClient,
    private val historyRepo: DownloadHistoryRepository,
    private val moduleRepo: ModuleRepository,
    prefs: AppPreferences
) : ViewModel() {

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }

    private var downloadJob: Job? = null
    private val lastDirectory: Flow<String?> = prefs.getLastDirectoryFlow()
    private val searchCommand = MutableStateFlow<SearchCommand>(SearchCommand.Idle)
    private val pagingConfig = PagingConfig(pageSize = 25, enablePlaceholders = false)

    val moduleResultState: StateFlow<ModuleResultState>
        field = MutableStateFlow(ModuleResultState())

    val historyState: StateFlow<ImmutableList<Module>> = historyRepo.getAllFlow()
        .map { it.toPersistentList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val searchState: StateFlow<DownloadSearchState>
        field = MutableStateFlow(DownloadSearchState())

    val pagingData: Flow<PagingData<ResultItem>> = searchCommand
        .flatMapLatest { command ->
            searchState.update { it.copy(isLoading = true) }
            when (command) {
                is SearchCommand.FileOrTitle -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.searchByFileNameOrTitle(command.query, page) },
                        extract = {
                            searchState.update { state -> state.copy(isLoading = false) }
                            it.module
                        }
                    )
                }.flow

                is SearchCommand.ArtistById -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.getArtistById(command.id, page) },
                        extract = {
                            searchState.update { state -> state.copy(isLoading = false) }
                            it.module
                        }
                    )
                }.flow

                is SearchCommand.Artist -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.getArtistSearch(command.query, page) },
                        extract = {
                            searchState.update { state -> state.copy(isLoading = false) }
                            it.listItems
                        }
                    )
                }.flow

                SearchCommand.Idle -> {
                    searchState.update { state -> state.copy(isLoading = false) }
                    flowOf(PagingData.empty())
                }
            }
        }
        .cachedIn(viewModelScope)

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
            moduleResultState.update { it.copy(downloadStatus = DownloadStatus.Loading) }

            val rootUri = lastDirectory.firstOrNull()?.toUri() ?: run {
                setDownloadError(R.string.download_error_no_folder)
                return@launch
            }
            val download = appContext.createPartialDownload(rootUri, module) ?: run {
                setDownloadError(R.string.download_error_create_file)
                return@launch
            }

            try {
                val response = httpClient.get(module.downloadUrl)
                val totalBytes = response.contentLength() ?: 0L
                val channel = response.bodyAsChannel()
                var bytesRead = 0L
                var lastPercent = -1
                val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)

                download.stream.use { out ->
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buf, 0, buf.size)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        bytesRead += read
                        if (totalBytes > 0) {
                            // Whole-percent steps only; per-chunk updates recompose too often.
                            val percent = (bytesRead * 100 / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                val status = DownloadStatus.Progress(percent.toFloat())
                                moduleResultState.update { it.copy(downloadStatus = status) }
                            }
                        }
                    }
                }

                if (appContext.finalizeDownload(rootUri, download) == null) {
                    appContext.discardPartialDownload(download)
                    setDownloadError(R.string.download_error_create_file)
                    return@launch
                }
                // Make it show up in the file browser without a manual refresh.
                moduleRepo.indexDirectory(rootUri)
                moduleResultState.update {
                    it.copy(downloadStatus = DownloadStatus.Success, moduleExists = true)
                }
            } catch (e: CancellationException) {
                appContext.discardPartialDownload(download)
                moduleResultState.update { it.copy(downloadStatus = DownloadStatus.None) }
                throw e
            } catch (e: Exception) {
                Timber.e(e)
                appContext.discardPartialDownload(download)
                setDownloadError(R.string.download_error_failed, e.message)
            }
        }
    }

    private fun setDownloadError(@StringRes fallback: Int, detail: String? = null) {
        val message = detail ?: appContext.getString(fallback)
        moduleResultState.update { it.copy(downloadStatus = DownloadStatus.Error(message)) }
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

    fun searchFileOrTitle(query: String) {
        searchState.update {
            it.copy(
                title = appContext.getString(R.string.download_title_results, query),
                activeType = SearchResultType.MODULES,
            )
        }
        searchCommand.value = SearchCommand.FileOrTitle(query)
    }

    fun searchArtist(query: String) {
        searchState.update {
            it.copy(
                title = appContext.getString(R.string.download_title_artists, query),
                activeType = SearchResultType.ARTISTS,
            )
        }
        searchCommand.value = SearchCommand.Artist(query)
    }

    fun getArtistById(id: Int) {
        searchState.update { it.copy(activeType = SearchResultType.MODULES) }
        searchCommand.value = SearchCommand.ArtistById(id)
    }

    private fun handleFailure(t: Throwable) {
        Timber.e(t)
        moduleResultState.update { it.copy(softError = t.message, isLoading = false) }
    }

    private suspend fun checkExists(module: Module) = findModuleUri(module) != null

    /** SAF queries: keep off the main thread. */
    private suspend fun findModuleUri(module: Module): Uri? = withContext(Dispatchers.IO) {
        val rootUri = (lastDirectory.firstOrNull() ?: return@withContext null).toUri()
        appContext.findDownloadedModule(
            rootUri = rootUri,
            artist = module.artist,
            filename = module.url.substringAfterLast('#'),
        )
    }
}
