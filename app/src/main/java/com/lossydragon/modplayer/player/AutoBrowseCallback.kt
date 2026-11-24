package com.lossydragon.modplayer.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.dao.PlaylistDao
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.toModuleEntity
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * [MediaLibrarySession.Callback] implementation for Android Auto integration.
 * All file-browser content is served from the Room database — no SAF scanning at browse time.
 */
@OptIn(UnstableApi::class)
class AutoBrowseCallback(
    private val context: Context,
    private val player: ModPlayer,
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

    /** Every parent a browser subscribed to; notifying one nobody watches any more is a no-op. */
    private val subscribedParents = ConcurrentHashMap.newKeySet<String>()

    private companion object {
        const val MAX_CACHED_SEARCHES = 8

        /** Long enough that an indexing run reloads the tree in steps, not per batch. */
        val REFRESH_DEBOUNCE = 1.seconds
    }

    /** Tells subscribed browsers to reload when the library or the playlists change. */
    @kotlin.OptIn(FlowPreview::class)
    fun observeLibrary(session: MediaLibrarySession) {
        fun refresh(changes: Flow<Int>, affected: (String) -> Boolean) = scope.launch {
            // The first value is the current state, not a change.
            changes.drop(1).debounce(REFRESH_DEBOUNCE).collect {
                subscribedParents.filter(affected).forEach { parentId ->
                    session.notifyChildrenChanged(parentId, Int.MAX_VALUE, null)
                }
            }
        }
        refresh(modulesDao.changes()) { it == AutoMediaId.FILE_BROWSER || AutoMediaId.isDir(it) }
        refresh(playlistDao.changes()) { it == AutoMediaId.PLAYLISTS || AutoMediaId.isPlaylist(it) }
    }

    /** Untrusted controllers keep Media3's read-only defaults; the rest also get our buttons. */
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        if (controller.isTrusted) result.setAvailableSessionCommands(SessionButtons.sessionCommands)
        return Futures.immediateFuture(result.build())
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (!SessionButtons.handle(customCommand, player)) {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Timber.d("onGetLibraryRoot: browser=${browser.packageName}")
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
    }

    /** Also what makes subscriptions work: the default [onSubscribe] rejects unknown parents. */
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = ioFuture {
        val item = findItem(mediaId)
        if (item != null) {
            LibraryResult.ofItem(item, null)
        } else {
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        subscribedParents += parentId
        return super.onSubscribe(session, browser, parentId, params)
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
     * A tap in the browse tree or a voice request arrives as one item naming what to play;
     * [resolvePlayRequest] turns it into the queue. Anything else already carries its URIs.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val request = mediaItems.singleOrNull()
        if (request == null || !isPlayRequest(request)) {
            return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs
            )
        }
        return ioFuture { resolvePlayRequest(request) }
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

                else -> item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
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
                val items = search(query)
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
    ): ListenableFuture<MediaItemsWithStartPosition> {
        Timber.d("onPlaybackResumption: isForPlayback=$isForPlayback")
        return ioFuture { savedQueue() ?: throw UnsupportedOperationException("No saved queue") }
    }

    // --- play requests --------------------------------------------------------------------

    /** One of our playable ids, or a bare request (voice search, "play music") with no URI. */
    private fun isPlayRequest(item: MediaItem): Boolean {
        val id = item.mediaId
        return id == AutoMediaId.PLAY_ALL ||
            id == AutoMediaId.SHUFFLE_ALL ||
            AutoMediaId.isFile(id) ||
            AutoMediaId.isPlaylistEntry(id) ||
            (
                id == MediaItem.DEFAULT_MEDIA_ID &&
                    item.localConfiguration == null &&
                    item.requestMetadata.mediaUri == null
                )
    }

    /**
     * The queue and start index for [request]. Throws when there is nothing to play, which
     * makes the session drop the request and leave the current queue alone.
     */
    internal suspend fun resolvePlayRequest(request: MediaItem): MediaItemsWithStartPosition {
        val id = request.mediaId
        return when {
            id == AutoMediaId.PLAY_ALL -> playAll(shuffle = false)
            id == AutoMediaId.SHUFFLE_ALL -> playAll(shuffle = true)
            AutoMediaId.isPlaylistEntry(id) -> playPlaylistEntry(id)
            AutoMediaId.isFile(id) -> playFile(id)
            else -> playFromSearch(request.requestMetadata)
        }
    }

    private suspend fun playAll(shuffle: Boolean): MediaItemsWithStartPosition {
        val items = modulesDao.getAllValidModules().map { fileItem(it) }
        return (if (shuffle) items.shuffled() else items).startingAt(0)
    }

    /** Queues the entry's playlist, starting at the entry. */
    private suspend fun playPlaylistEntry(id: String): MediaItemsWithStartPosition {
        val (playlistId, _) = AutoMediaId.playlistEntryParts(id)
            ?: throw IllegalArgumentException("Malformed playlist entry id: $id")
        val entries = buildPlaylistEntryItems(playlistId)
        return entries.startingAt(entries.indexOfFirst { it.mediaId == id })
    }

    /** Queues the file's folder, starting at the file; a file outside the library plays alone. */
    private suspend fun playFile(id: String): MediaItemsWithStartPosition {
        val path = AutoMediaId.uriFromFile(id)
        val indexed = modulesDao.getByFilePaths(listOf(path)).firstOrNull()
        if (indexed != null) {
            val siblings = modulesDao.getChildrenOnce(indexed.parentPath)
                .filter { !it.isDirectory && it.isValidModule }
                .map { fileItem(it) }
            val index = siblings.indexOfFirst { it.mediaId == id }
            if (index >= 0) return siblings.startingAt(index)
        }
        val module = indexed
            ?: playlistDao.findEntryByUri(path)?.toModuleEntity()
            ?: unindexedModule(path)
        return listOf(fileItem(module)).startingAt(0)
    }

    /**
     * Voice search. No query ("play music") resumes the saved queue or shuffles the library;
     * otherwise a playlist of that name wins over title matches.
     */
    private suspend fun playFromSearch(
        request: MediaItem.RequestMetadata
    ): MediaItemsWithStartPosition {
        val extras = request.extras

        // The assistant's own parse of the query, when it made one.
        @Suppress("DEPRECATION") // EXTRA_MEDIA_PLAYLIST: no replacement, still sent
        val terms = listOfNotNull(
            request.searchQuery,
            extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        Timber.d("playFromSearch: terms=$terms")

        if (terms.isEmpty()) {
            return runCatching { savedQueue() }.getOrNull() ?: playAll(shuffle = true)
        }
        for (term in terms) {
            val playlist = playlistDao.findPlaylistByName(term) ?: continue
            val entries = buildPlaylistEntryItems(playlist.id)
            if (entries.isNotEmpty()) return entries.startingAt(0)
        }
        for (term in terms) {
            val hits = search(term)
            if (hits.isNotEmpty()) return hits.startingAt(0)
        }
        throw NoSuchElementException("Nothing matches $terms")
    }

    /** The persisted queue at its saved index and position, or null when there is none. */
    private suspend fun savedQueue(): MediaItemsWithStartPosition? {
        val saved = prefs.getQueueState() ?: return null
        val items = saved.decodeModules().map { fileItem(it) }
        if (items.isEmpty()) return null
        val state = saved.playback
        return MediaItemsWithStartPosition(
            items,
            state.index.coerceIn(items.indices),
            state.positionMs
        )
    }

    /** Library and playlist-entry matches for [query], best title match first. */
    private suspend fun search(query: String): List<MediaItem> {
        val files = modulesDao.searchBy(query)
        val entries = playlistDao.searchByName(query).map { it.toModuleEntity() }
        val seen = HashSet<String>()
        return (files + entries)
            .filter { seen.add(it.filePath) }
            .sortedBy { module ->
                when {
                    module.name.equals(query, ignoreCase = true) -> 0
                    module.name.startsWith(query, ignoreCase = true) -> 1
                    else -> 2
                }
            }
            .map { fileItem(it) }
    }

    /** An index of -1 (the tapped item is gone) starts at the top. */
    private fun List<MediaItem>.startingAt(index: Int): MediaItemsWithStartPosition {
        if (isEmpty()) throw NoSuchElementException("Nothing to play")
        return MediaItemsWithStartPosition(this, index.coerceAtLeast(0), 0L)
    }

    /** A file known only by its URI; the name is the last segment of the document id. */
    private fun unindexedModule(path: String): ModuleEntity {
        val name = path.toUri().lastPathSegment.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast(':')
        return ModuleEntity(
            filePath = path,
            filename = name,
            fileExtension = name.substringAfterLast('.', ""),
        )
    }

    // --- browse tree ----------------------------------------------------------------------

    private fun rootItem(): MediaItem = buildBrowsableItem(
        id = AutoMediaId.ROOT,
        title = context.getString(R.string.app_name),
        type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )

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

    /** The item behind [id], or null if it no longer exists. */
    private suspend fun findItem(id: String): MediaItem? = when {
        id == AutoMediaId.ROOT -> rootItem()

        id == AutoMediaId.PLAYLISTS_EMPTY -> emptyPlaylistsItem()

        AutoMediaId.isPlaylist(id) ->
            AutoMediaId.playlistIdFrom(id)?.let { playlistDao.getPlaylist(it) }?.let(::playlistItem)

        AutoMediaId.isPlaylistEntry(id) -> AutoMediaId.playlistEntryParts(id)
            ?.let { (playlistId, _) -> buildPlaylistEntryItems(playlistId) }
            ?.firstOrNull { it.mediaId == id }

        AutoMediaId.isDir(id) ->
            modulesDao.getByFilePaths(listOf(AutoMediaId.pathFromDir(id))).firstOrNull()
                ?.takeIf { it.isDirectory }
                ?.let(::directoryItem)

        AutoMediaId.isFile(id) -> {
            val path = AutoMediaId.uriFromFile(id)
            val module = modulesDao.getByFilePaths(listOf(path)).firstOrNull()
                ?: playlistDao.findEntryByUri(path)?.toModuleEntity()
            module?.let { fileItem(it) }
        }

        else -> (getRootChildren() + getHomeChildren()).firstOrNull { it.mediaId == id }
    }

    /** One browsable node per Room playlist, or a placeholder when there are none. */
    private suspend fun getPlaylists(): ImmutableList<MediaItem> {
        val playlists = playlistDao.getAllPlaylistsOnce()
        if (playlists.isEmpty()) return ImmutableList.of(emptyPlaylistsItem())
        return ImmutableList.copyOf(playlists.map(::playlistItem))
    }

    private fun emptyPlaylistsItem(): MediaItem = buildBrowsableItem(
        id = AutoMediaId.PLAYLISTS_EMPTY,
        title = context.getString(R.string.auto_no_playlists),
        type = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
    )

    private fun playlistItem(playlist: PlaylistEntity): MediaItem = buildBrowsableItem(
        id = AutoMediaId.playlist(playlist.id),
        title = playlist.name,
        type = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        artworkUri = context.drawableUri(R.drawable.aa_playlists),
    )

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
                    entity.isDirectory -> directoryItem(entity)
                    entity.isValidModule -> fileItem(entity)
                    else -> null
                }
            }
        )

    private fun directoryItem(entity: ModuleEntity): MediaItem = buildBrowsableItem(
        id = AutoMediaId.dir(entity.filePath),
        title = entity.filename,
        type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        artworkUri = folderArtwork
    )

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

    private fun fileItem(entity: ModuleEntity): MediaItem = buildPlayableItem(
        id = AutoMediaId.file(entity.filePath),
        title = entity.name,
        uri = entity.uri,
        artworkUri = fileArtwork,
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
