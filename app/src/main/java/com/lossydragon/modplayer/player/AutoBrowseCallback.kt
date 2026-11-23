package com.lossydragon.modplayer.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.dao.PlaylistDao
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.toModuleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    private val contentStyleExtras = Bundle().apply {
        val grid = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, grid)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, grid)
    }

    private val fileArtwork = context.drawableUri(R.drawable.aa_file)
    private val folderArtwork = context.drawableUri(R.drawable.aa_folder)

    /** Results of the most recent searches, served page by page from [onGetSearchResult]. */
    private val searchResultsCache = object : LinkedHashMap<String, List<MediaItem>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>?) =
            size > MAX_CACHED_SEARCHES
    }
    private var activeSearchJob: Job? = null

    private companion object {
        const val MAX_CACHED_SEARCHES = 8
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Timber.d("onGetLibraryRoot: browser=${browser.packageName}")
        val root = buildBrowsableItem(
            id = AutoMediaId.ROOT,
            title = context.getString(R.string.app_name),
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        )
        return Futures.immediateFuture(LibraryResult.ofItem(root, null))
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
        return ioFuture {
            val items = when {
                parentId == AutoMediaId.ROOT -> getRootChildren()
                parentId == AutoMediaId.HOME -> getHomeChildren()
                parentId == AutoMediaId.FILE_BROWSER -> getFileBrowserRoot()
                parentId == AutoMediaId.PLAYLISTS -> getPlaylists()
                AutoMediaId.isPlaylist(parentId) -> getPlaylistEntries(parentId)
                AutoMediaId.isDir(parentId) -> getDirectoryChildren(parentId)
                else -> ImmutableList.of()
            }
            LibraryResult.ofItemList(items.page(page, pageSize), params)
        }
    }

    /**
     * For PLAY_ALL / SHUFFLE_ALL, queries all valid modules from the DB.
     * For a playlist entry tap, queues that playlist.
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

        if (singleId != null && AutoMediaId.isPlaylistEntry(singleId)) {
            return ioFuture {
                val (playlistId, _) = AutoMediaId.playlistEntryParts(singleId)
                    ?: throw IllegalArgumentException("Malformed playlist entry id: $singleId")
                val entries = buildPlaylistEntryItems(playlistId)
                val startIdx = entries.indexOfFirst { it.mediaId == singleId }.coerceAtLeast(0)
                MediaSession.MediaItemsWithStartPosition(entries, startIdx, 0L)
            }
        }

        if (singleId == null || !AutoMediaId.isFile(singleId)) {
            return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs
            )
        }

        return ioFuture {
            val selectedFilePath = AutoMediaId.uriFromFile(singleId)
            val parentPath =
                modulesDao.getByFilePaths(listOf(selectedFilePath)).firstOrNull()?.parentPath
                    ?: throw IllegalStateException("File not found in DB")

            val siblings = modulesDao.getChildrenOnce(parentPath)
                .filter { !it.isDirectory && it.isValidModule }
                .map { fileItem(it) }

            val startIdx = siblings.indexOfFirst { it.mediaId == singleId }.coerceAtLeast(0)
            MediaSession.MediaItemsWithStartPosition(siblings, startIdx, 0L)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        val resolved = mediaItems.map { item ->
            val id = item.mediaId
            val uri = when {
                AutoMediaId.isFile(id) -> AutoMediaId.uriFromFile(id).toUri()

                AutoMediaId.isPlaylistEntry(id) ->
                    AutoMediaId.playlistEntryParts(id)?.second?.toUri()

                else -> item.localConfiguration?.uri
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
        val job = scope.launch(Dispatchers.IO) {
            try {
                val playlistItems = playlistDao.searchByName(query).map { entry ->
                    buildPlayableItem(
                        id = AutoMediaId.file(entry.uri),
                        title = entry.name,
                        uri = entry.uri.toUri(),
                        module = entry.toModuleEntity(),
                    )
                }
                val fileItems = modulesDao.searchBy(query).map { fileItem(it) }

                val seen = mutableSetOf<String>()
                val items = (playlistItems + fileItems).filter { seen.add(it.mediaId) }
                synchronized(searchResultsCache) { searchResultsCache[query] = items }

                Timber.d("onSearch: found ${items.size} results for '$query'")
                session.notifySearchResultChanged(browser, query, items.size, params)
                future.set(LibraryResult.ofVoid())
            } catch (e: Exception) {
                // Includes CancellationException from a newer search: the browser must still get
                // an answer or it waits on this future forever.
                Timber.w(e, "onSearch failed for '$query'")
                future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
            }
        }
        // A scope cancelled before the body ran would otherwise leave the browser waiting.
        job.invokeOnCompletion {
            if (!future.isDone) future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
        }
        activeSearchJob = job
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
        val results = synchronized(searchResultsCache) { searchResultsCache[query] } ?: emptyList()
        return Futures.immediateFuture(
            LibraryResult.ofItemList(results.page(page, pageSize), params)
        )
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Timber.d("onPlaybackResumption: isForPlayback=$isForPlayback")
        return ioFuture {
            val saved = prefs.getQueueState()
                ?: throw UnsupportedOperationException("No saved queue")
            val state = saved.playback
            val files = saved.decodeModules()
            if (files.isEmpty()) throw UnsupportedOperationException("Queue is empty")

            val mediaItems = files.map { file ->
                buildPlayableItem(
                    id = AutoMediaId.file(file.filePath),
                    title = file.filename,
                    uri = file.uri,
                    artworkUri = fileArtwork,
                    module = file,
                )
            }
            MediaSession.MediaItemsWithStartPosition(mediaItems, state.index, state.positionMs)
        }
    }

    private fun getRootChildren(): ImmutableList<MediaItem> = ImmutableList.of(
        buildBrowsableItem(
            id = AutoMediaId.HOME,
            title = context.getString(R.string.auto_home),
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            extras = contentStyleExtras,
            artworkUri = context.drawableUri(R.drawable.aa_home)
        ),
        buildBrowsableItem(
            id = AutoMediaId.PLAYLISTS,
            title = context.getString(R.string.auto_playlists),
            type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            extras = contentStyleExtras,
            artworkUri = context.drawableUri(R.drawable.aa_playlists)
        ),
        buildBrowsableItem(
            id = AutoMediaId.FILE_BROWSER,
            title = context.getString(R.string.auto_file_browser),
            type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            artworkUri = context.drawableUri(R.drawable.aa_browser)
        ),
    )

    /** One browsable node per Room playlist, or a placeholder when there are none. */
    private suspend fun getPlaylists(): ImmutableList<MediaItem> {
        val playlists = playlistDao.getAllPlaylistsOnce()
        if (playlists.isEmpty()) {
            return ImmutableList.of(
                buildBrowsableItem(
                    id = AutoMediaId.PLAYLISTS_EMPTY,
                    title = context.getString(R.string.auto_no_playlists),
                    type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                )
            )
        }
        return ImmutableList.copyOf(
            playlists.map { playlist ->
                buildBrowsableItem(
                    id = AutoMediaId.playlist(playlist.id),
                    title = playlist.name,
                    type = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    artworkUri = context.drawableUri(R.drawable.aa_playlists),
                )
            }
        )
    }

    private suspend fun getPlaylistEntries(parentId: String): ImmutableList<MediaItem> {
        val playlistId = AutoMediaId.playlistIdFrom(parentId) ?: return ImmutableList.of()
        return ImmutableList.copyOf(buildPlaylistEntryItems(playlistId))
    }

    /** Playable items for every entry of [playlistId], in playlist order. */
    private suspend fun buildPlaylistEntryItems(playlistId: Long): List<MediaItem> =
        playlistDao.getEntries(playlistId).map { entry ->
            buildPlayableItem(
                id = AutoMediaId.playlistEntry(playlistId, entry.uri),
                title = entry.name,
                uri = entry.uri.toUri(),
                artworkUri = fileArtwork,
                module = entry.toModuleEntity(),
            )
        }

    private fun getHomeChildren(): ImmutableList<MediaItem> = ImmutableList.of(
        buildPlayableItem(
            id = AutoMediaId.SHUFFLE_ALL,
            title = context.getString(R.string.auto_shuffle_all),
            artworkUri = context.drawableUri(R.drawable.aa_shuffle)
        ),
        buildPlayableItem(
            id = AutoMediaId.PLAY_ALL,
            title = context.getString(R.string.auto_play_all),
            artworkUri = context.drawableUri(R.drawable.aa_play_all)
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
    private suspend fun buildChildItems(parentPath: String): ImmutableList<MediaItem> =
        ImmutableList.copyOf(
            modulesDao.getChildrenOnce(parentPath).mapNotNull { entity ->
                when {
                    entity.isDirectory -> buildBrowsableItem(
                        id = AutoMediaId.dir(entity.filePath),
                        title = entity.filename,
                        type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        artworkUri = folderArtwork
                    )

                    entity.isValidModule -> fileItem(entity, fileArtwork)

                    else -> null
                }
            }
        )

    private fun buildPlayAllFuture(
        shuffle: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = ioFuture {
        val items = modulesDao.getAllValidModules()
            .map { fileItem(it) }
            .toMutableList()
        if (shuffle) items.shuffle()
        MediaSession.MediaItemsWithStartPosition(items, 0, 0L)
    }

    /** Runs [block] on the IO dispatcher, completing the returned future with its result. */
    private fun <T> ioFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch(Dispatchers.IO) {
            try {
                future.set(block())
            } catch (e: Exception) {
                Timber.e(e, "Auto browse future failed")
                future.setException(e)
            }
        }.invokeOnCompletion { cause ->
            if (!future.isDone) future.setException(cause ?: IllegalStateException("cancelled"))
        }
        return future
    }

    /** [pageSize] is Int.MAX_VALUE for browsers that do not page. */
    private fun List<MediaItem>.page(page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val from = page.toLong() * pageSize
        if (from >= size) return ImmutableList.of()
        val to = minOf(size.toLong(), from + pageSize)
        return ImmutableList.copyOf(subList(from.toInt(), to.toInt()))
    }

    private fun buildBrowsableItem(
        id: String,
        title: String,
        type: Int,
        artworkUri: Uri? = null,
        extras: Bundle? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setArtworkUri(artworkUri)
                .setExtras(extras)
                .build()
        )
        .build()

    private fun fileItem(entity: ModuleEntity, artworkUri: Uri? = null): MediaItem =
        buildPlayableItem(
            id = AutoMediaId.file(entity.filePath),
            title = entity.name,
            uri = entity.uri,
            artworkUri = artworkUri,
            module = entity,
        )

    private fun buildPlayableItem(
        id: String,
        title: String,
        uri: Uri? = null,
        artworkUri: Uri? = null,
        module: ModuleEntity? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .apply {
            if (uri != null) {
                setUri(uri)
                setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            }
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setArtworkUri(artworkUri)
                .setExtras(module?.let(MediaItemExtras::of))
                .build()
        )
        .build()
}
