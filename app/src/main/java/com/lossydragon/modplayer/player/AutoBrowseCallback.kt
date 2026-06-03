package com.lossydragon.modplayer.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.dao.PlaylistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * [MediaLibrarySession.Callback] implementation for Android Auto integration.
 * All file-browser content is served from the Room database — no SAF scanning at browse time.
 */
@OptIn(UnstableApi::class)
class AutoBrowseCallback(
    private val context: Context,
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
    private val modulesDao: ModuleDao,
    private val scope: CoroutineScope
) : MediaLibrarySession.Callback {

    private val artworkUri: Uri by lazy {
        "android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}".toUri()
    }

    private val searchResultsCache = mutableMapOf<String, List<MediaItem>>()
    private var activeSearchJob: Job? = null

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Timber.d("onGetLibraryRoot: browser=${browser.packageName}")
        val browseItem = buildBrowsableItem(
            id = AutoMediaId.ROOT,
            title = context.getString(R.string.app_name),
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        )
        return Futures.immediateFuture(LibraryResult.ofItem(browseItem, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Timber.d("onGetChildren: parentId=$parentId page=$page")
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch(Dispatchers.IO) {
            val items = when {
                parentId == AutoMediaId.ROOT -> getRootChildren()
                parentId == AutoMediaId.FILE_BROWSER -> getFileBrowserRoot()
                parentId == AutoMediaId.PLAYLISTS -> getPlaylists()
                AutoMediaId.isDir(parentId) -> getDirectoryChildren(parentId)
                else -> ImmutableList.of()
            }
            LibraryResult.ofItemList(items, params).also(future::set)
        }
        return future
    }

    /**
     * For PLAY_ALL / SHUFFLE_ALL, queries all valid modules from the DB.
     * For a single file tap, resolves its siblings from the DB using parentPath.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val singleId = mediaItems.singleOrNull()?.mediaId
        if (singleId == AutoMediaId.PLAY_ALL || singleId == AutoMediaId.SHUFFLE_ALL) {
            return buildPlayAllFuture(shuffle = singleId == AutoMediaId.SHUFFLE_ALL)
        }

        if (mediaItems.size != 1 || !AutoMediaId.isFile(mediaItems[0].mediaId)) {
            return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs
            )
        }

        val selectedFilePath = AutoMediaId.uriFromFile(mediaItems[0].mediaId)
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(Dispatchers.IO) {
            try {
                val parentPath = modulesDao.getByFilePaths(listOf(selectedFilePath))
                    .firstOrNull()?.parentPath
                    ?: run {
                        future.setException(IllegalStateException("File not found in DB"))
                        return@launch
                    }

                val siblings = modulesDao.getChildrenOnce(parentPath)
                    .filter { !it.isDirectory && it.isValidModule }
                    .map { buildPlayableItem(AutoMediaId.file(it.filePath), it.name, it.uri) }

                val startIdx = siblings
                    .indexOfFirst { it.mediaId == mediaItems[0].mediaId }
                    .takeIf { it >= 0 } ?: 0

                MediaSession.MediaItemsWithStartPosition(siblings, startIdx, 0L)
                    .also(future::set)
            } catch (e: Exception) {
                Timber.e(e, "Failed to resolve siblings for Auto file tap")
                future.setException(e)
            }
        }
        return future
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        val resolved = mediaItems.map { item ->
            val uri = when {
                AutoMediaId.isFile(item.mediaId) -> AutoMediaId.uriFromFile(item.mediaId).toUri()
                item.localConfiguration?.uri != null -> item.localConfiguration!!.uri
                else -> null
            }
            if (uri != null) item.buildUpon().setUri(uri).build() else item
        }
        return Futures.immediateFuture(resolved)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        Timber.d("onSearch: query='$query'")
        val future = SettableFuture.create<LibraryResult<Void>>()
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch(Dispatchers.IO) {
            val playlistItems = playlistDao.searchByName(query).map { entry ->
                buildPlayableItem(AutoMediaId.file(entry.uri), entry.name, entry.uri.toUri())
            }
            val fileItems = modulesDao.searchBy(query)
                .filter { it.isValidModule }
                .map { buildPlayableItem(AutoMediaId.file(it.filePath), it.name, it.uri) }

            val seen = mutableSetOf<String>()
            val items = (playlistItems + fileItems).filter { seen.add(it.mediaId) }
            searchResultsCache[query] = items

            Timber.d("onSearch: found ${items.size} results for '$query'")
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid().also(future::set)
        }
        return future
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Timber.d("onGetSearchResult: query='$query' page=$page pageSize=$pageSize")
        val all = searchResultsCache[query] ?: emptyList()
        val paged = all.drop(page * pageSize).take(pageSize)
        return Futures.immediateFuture(
            LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
        )
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Timber.d("onPlaybackResumption: isForPlayback=$isForPlayback")
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(Dispatchers.IO) {
            val state = prefs.getQueueState() ?: run {
                future.setException(UnsupportedOperationException("No saved queue"))
                return@launch
            }

            val files = try {
                Json.decodeFromString<List<com.lossydragon.modplayer.db.entity.ModuleEntity>>(
                    state.json
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to decode queue for resumption")
                future.setException(e)
                return@launch
            }

            if (files.isEmpty()) {
                future.setException(UnsupportedOperationException("Queue is empty"))
                return@launch
            }

            val mediaItems = files.map { file ->
                MediaItem.Builder()
                    .setUri(file.uri)
                    .setMediaId(file.filePath)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(file.filename)
                            .setIsPlayable(true)
                            .setArtworkUri(artworkUri)
                            .build()
                    )
                    .build()
            }

            MediaSession.MediaItemsWithStartPosition(mediaItems, state.index, state.positionMs)
                .also(future::set)
        }
        return future
    }

    private fun getRootChildren(): ImmutableList<MediaItem> = ImmutableList.of(
        buildBrowsableItem(
            id = AutoMediaId.FILE_BROWSER,
            title = "File Browser",
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        ),
        buildBrowsableItem(
            id = AutoMediaId.PLAYLISTS,
            title = "Playlists",
            type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        ),
        buildActionItem(id = AutoMediaId.PLAY_ALL, title = "Play All"),
        buildActionItem(id = AutoMediaId.SHUFFLE_ALL, title = "Shuffle All"),
    )

    private fun getPlaylists(): ImmutableList<MediaItem> = ImmutableList.of(
        buildBrowsableItem(
            "playlists_coming_soon",
            "Coming Soon",
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        )
    )

    private suspend fun getFileBrowserRoot(): ImmutableList<MediaItem> {
        val savedUri = prefs.getLastDirectoryUri() ?: return ImmutableList.of()
        return buildChildItems(savedUri)
    }

    private suspend fun getDirectoryChildren(parentId: String): ImmutableList<MediaItem> =
        buildChildItems(AutoMediaId.pathFromDir(parentId))

    /**
     * Queries the DB for all direct children of [parentPath].
     * Directories become browsable items; valid module files become playable items.
     */
    private suspend fun buildChildItems(parentPath: String): ImmutableList<MediaItem> {
        val entries = modulesDao.getChildrenOnce(parentPath)
        val items = mutableListOf<MediaItem>()
        entries.forEach { entity ->
            if (entity.isDirectory) {
                items.add(
                    buildBrowsableItem(
                        id = AutoMediaId.dir(entity.filePath),
                        title = entity.filename,
                        type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                    )
                )
            } else if (entity.isValidModule) {
                items.add(
                    buildPlayableItem(
                        id = AutoMediaId.file(entity.filePath),
                        title = entity.name,
                        uri = entity.uri,
                    )
                )
            }
        }
        return ImmutableList.copyOf(items)
    }

    private fun buildPlayAllFuture(
        shuffle: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(Dispatchers.IO) {
            try {
                val items = modulesDao.getAllValidModules()
                    .map { buildPlayableItem(AutoMediaId.file(it.filePath), it.name, it.uri) }
                    .toMutableList()

                if (shuffle) items.shuffle()

                MediaSession.MediaItemsWithStartPosition(items, 0, 0L).also(future::set)
            } catch (e: Exception) {
                Timber.e(e, "buildPlayAllFuture failed")
                future.setException(e)
            }
        }
        return future
    }

    private fun buildBrowsableItem(id: String, title: String, type: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()

    private fun buildPlayableItem(id: String, title: String, uri: Uri): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()

    private fun buildActionItem(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
}
