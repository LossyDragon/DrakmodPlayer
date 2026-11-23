package com.lossydragon.modplayer.ui.screens.downloads

import android.net.Uri
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
import com.lossydragon.modplayer.data.DownloadHistoryItem
import com.lossydragon.modplayer.data.DownloadHistoryRepository
import com.lossydragon.modplayer.data.ModArchiveService
import com.lossydragon.modplayer.data.ModuleRepository
import com.lossydragon.modplayer.data.ResultPagingSource
import com.lossydragon.modplayer.data.SafDataSource
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.model.ModuleResult
import com.lossydragon.modplayer.model.ResultItem
import com.lossydragon.modplayer.util.UiText
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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

/** Loading is derived from `LazyPagingItems.loadState`, not tracked here. */
@Immutable
data class DownloadSearchState(
    val title: UiText? = null,
    val activeType: SearchResultType = SearchResultType.NONE
)

@Immutable
sealed class DownloadStatus {
    data class Error(val message: UiText) : DownloadStatus()
    data class Progress(val percent: Float) : DownloadStatus()
    data object Loading : DownloadStatus()
    data object None : DownloadStatus()
    data object Success : DownloadStatus()
}

/** One-shot notifications; the UI shows them as a snackbar. */
sealed interface DownloadsEvent {
    data class Error(val message: UiText) : DownloadsEvent
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
    private val service: ModArchiveService,
    private val httpClient: HttpClient,
    private val historyRepo: DownloadHistoryRepository,
    private val moduleRepo: ModuleRepository,
    private val saf: SafDataSource,
    prefs: AppPreferences
) : ViewModel() {

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val DOWNLOAD_REQUEST_TIMEOUT_MS = 10 * 60 * 1000L
        const val DOWNLOAD_SOCKET_TIMEOUT_MS = 60 * 1000L
    }

    private var downloadJob: Job? = null
    private val lastDirectory: Flow<String?> = prefs.getLastDirectoryFlow()
    private val searchCommand = MutableStateFlow<SearchCommand>(SearchCommand.Idle)
    private val pagingConfig = PagingConfig(pageSize = 25, enablePlaceholders = false)

    val moduleResultState: StateFlow<ModuleResultState>
        field = MutableStateFlow(ModuleResultState())

    val history: StateFlow<ImmutableList<DownloadHistoryItem>> = historyRepo.getAllFlow()
        .map { it.toPersistentList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val _events = Channel<DownloadsEvent>(Channel.BUFFERED)
    val events: Flow<DownloadsEvent> = _events.receiveAsFlow()

    val searchState: StateFlow<DownloadSearchState>
        field = MutableStateFlow(DownloadSearchState())

    val pagingData: Flow<PagingData<ResultItem>> = searchCommand
        .flatMapLatest { command ->
            when (command) {
                is SearchCommand.FileOrTitle -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.searchByFileNameOrTitle(command.query, page) },
                        extract = { it.module }
                    )
                }.flow

                is SearchCommand.ArtistById -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.getArtistById(command.id, page) },
                        extract = { it.module }
                    )
                }.flow

                is SearchCommand.Artist -> Pager(pagingConfig) {
                    ResultPagingSource(
                        fetch = { page -> service.getArtistSearch(command.query, page) },
                        extract = { it.listItems }
                    )
                }.flow

                SearchCommand.Idle -> flowOf(PagingData.empty())
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
        val previous = downloadJob
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            // Its cleanup deletes the .part file, which may be the one this job creates.
            previous?.cancelAndJoin()
            moduleResultState.update { it.copy(downloadStatus = DownloadStatus.Loading) }

            val rootUri = lastDirectory.firstOrNull()?.toUri() ?: run {
                setDownloadError(R.string.download_error_no_folder)
                return@launch
            }
            val download = saf.createPartialDownload(rootUri, module) ?: run {
                setDownloadError(R.string.download_error_create_file)
                return@launch
            }

            try {
                val response = httpClient.get(module.downloadUrl) {
                    timeout {
                        requestTimeoutMillis = DOWNLOAD_REQUEST_TIMEOUT_MS
                        socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MS
                    }
                }
                val totalBytes = response.contentLength() ?: 0L
                val channel = response.bodyAsChannel()
                var bytesRead = 0L
                var lastPercent = -1
                val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)

                // Opened only once there is a body, so a failed request can't leak the stream.
                val out = saf.openPartialDownload(download)
                    ?: error("Could not open ${download.partUri}")
                out.use {
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

                if (saf.finalizeDownload(rootUri, download) == null) {
                    saf.discardPartialDownload(download)
                    setDownloadError(R.string.download_error_create_file)
                    return@launch
                }
                // Make it show up in the file browser without a manual refresh.
                moduleRepo.indexDirectoryShallow(rootUri, download.dirUri)
                moduleResultState.update {
                    it.copy(downloadStatus = DownloadStatus.Success, moduleExists = true)
                }
            } catch (e: CancellationException) {
                saf.discardPartialDownload(download)
                moduleResultState.update { it.copy(downloadStatus = DownloadStatus.None) }
                throw e
            } catch (e: Exception) {
                Timber.e(e)
                saf.discardPartialDownload(download)
                setDownloadError(R.string.download_error_failed, e.message)
            }
        }
    }

    private fun setDownloadError(@StringRes fallback: Int, detail: String? = null) {
        val message = detail?.let { UiText.Raw(it) } ?: UiText.Res(fallback)
        moduleResultState.update { it.copy(downloadStatus = DownloadStatus.Error(message)) }
        _events.trySend(DownloadsEvent.Error(message))
    }

    /** The downloaded file for [module] as a playable entity; reports why not through [events]. */
    suspend fun findDownloaded(module: Module): ModuleEntity? {
        val error = when {
            lastDirectory.firstOrNull().isNullOrBlank() -> R.string.download_error_no_folder

            else -> {
                val uri = findModuleUri(module)
                if (uri != null) {
                    val filename = module.downloadFilename
                    return ModuleEntity(
                        filePath = uri.toString(),
                        filename = filename,
                        fileExtension = filename.substringAfterLast('.', ""),
                        fileSize = module.bytes.toLong(),
                        isValidModule = true,
                        moduleName = module.songtitle,
                        moduleType = module.format,
                    )
                }
                R.string.snack_module_not_found
            }
        }
        _events.trySend(DownloadsEvent.Error(UiText.Res(error)))
        return null
    }

    fun deleteModule(module: Module) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileUri = findModuleUri(module) ?: return@launch
                saf.deleteDocument(fileUri)
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

    // The search functions ignore a repeat of the current command so a results screen can
    // re-issue its request on re-entry without dropping the loaded pages.

    fun searchFileOrTitle(query: String) {
        val command = SearchCommand.FileOrTitle(query)
        if (searchCommand.value == command) return
        searchState.update {
            it.copy(
                title = UiText.Res(R.string.download_title_results, query),
                activeType = SearchResultType.MODULES,
            )
        }
        searchCommand.value = command
    }

    fun searchArtist(query: String) {
        val command = SearchCommand.Artist(query)
        if (searchCommand.value == command) return
        searchState.update {
            it.copy(
                title = UiText.Res(R.string.download_title_artists, query),
                activeType = SearchResultType.ARTISTS,
            )
        }
        searchCommand.value = command
    }

    fun getArtistById(id: Int, alias: String) {
        val command = SearchCommand.ArtistById(id)
        if (searchCommand.value == command) return
        searchState.update {
            it.copy(
                title = UiText.Res(R.string.download_title_results, alias),
                activeType = SearchResultType.MODULES,
            )
        }
        searchCommand.value = command
    }

    private fun handleFailure(t: Throwable) {
        Timber.e(t)
        moduleResultState.update { it.copy(softError = t.message, isLoading = false) }
    }

    private suspend fun checkExists(module: Module) = findModuleUri(module) != null

    /** SAF queries: keep off the main thread. */
    private suspend fun findModuleUri(module: Module): Uri? = withContext(Dispatchers.IO) {
        val rootUri = (lastDirectory.firstOrNull() ?: return@withContext null).toUri()
        saf.findDownloadedModule(
            rootUri = rootUri,
            artist = module.artist,
            filename = module.downloadFilename,
        )
    }
}
