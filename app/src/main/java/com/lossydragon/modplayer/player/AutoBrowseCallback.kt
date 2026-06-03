package com.lossydragon.modplayer.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import com.lossydragon.modplayer.db.dao.ModuleMetadataDao
import com.lossydragon.modplayer.db.dao.PlaylistDao
import com.lossydragon.modplayer.model.ModuleFile
import com.lossydragon.modplayer.util.queryDirectoryEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ModInfo
import timber.log.Timber

/**
 * [MediaLibrarySession.Callback] implementation for Android Auto integration.
 */

@OptIn(UnstableApi::class)
class AutoBrowseCallback(
    private val context: Context,
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
    private val metadataDao: ModuleMetadataDao,
    private val scope: CoroutineScope
) : MediaLibrarySession.Callback {

    // TODO add more known types
    //  ... or better yet, maybe the db can handle this?!
    /** fast pass filtering instead of testFromFd. */
    private val moduleExtensions = setOf(
        "mod", "xm", "it", "s3m", "far", "med", "okt", "mmd", "669", "stm", "psm",
    )

    /** Placeholder Uri */
    private val artworkUri: Uri by lazy {
        "android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}".toUri()
    }

    /** Holds search results between [onSearch] and [onGetSearchResult]. */
    private val searchResultsCache = mutableMapOf<String, List<MediaItem>>()

    /** The currently running search coroutine; canceled on each new keystroke. */
    private var activeSearchJob: Job? = null

    /** Returns the root browse item that AA uses as the top-level entry point. */
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
        val libraryResult = LibraryResult.ofItem(browseItem, params)
        return Futures.immediateFuture(libraryResult)
    }

    /**
     * Returns children for [parentId]. Runs on [Dispatchers.IO] so SAF / DB calls
     * don't block the binder thread.
     */
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
     * Intercepts Play All / Shuffle All action items and resolves file-browser taps
     * into a full sorted sibling queue. Falls through to default handling for all other cases.
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

        // Only expand a single file-browser tap into its directory siblings.
        if (mediaItems.size != 1 || !AutoMediaId.isFile(mediaItems[0].mediaId)) {
            return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs
            )
        }

        val selectedUri = AutoMediaId.uriFromFile(mediaItems[0].mediaId).toUri()
        val treeDocId = runCatching {
            DocumentsContract.getTreeDocumentId(selectedUri)
        }.getOrNull() ?: return super.onSetMediaItems(
            mediaSession,
            controller,
            mediaItems,
            startIndex,
            startPositionMs
        )

        val treeUri = Uri.Builder()
            .scheme("content")
            .authority(selectedUri.authority)
            .appendPath("tree")
            .appendPath(treeDocId)
            .build()
        val parentDocId = DocumentsContract
            .getDocumentId(selectedUri)
            .substringBeforeLast('/', treeDocId)

        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(Dispatchers.IO) {
            try {
                val allItems = buildAllItemsFlat(treeUri, parentDocId)
                val startIdx = allItems
                    .indexOfFirst { it.mediaId == mediaItems[0].mediaId }
                    .takeIf { it >= 0 }
                    ?: 0
                val mediaItems = MediaSession.MediaItemsWithStartPosition(allItems, startIdx, 0L)
                future.set(mediaItems)
            } catch (e: Exception) {
                Timber.e(e, "Failed to build traversal queue for Auto")
                future.setException(e)
            }
        }
        return future
    }

    /**
     * Resolves `file::` media IDs to real SAF URIs so Media3 can open the files.
     * Called by the framework before items reach the player.
     */
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

    /**
     * Performs a search across playlist entries and the indexed file tree.
     * Cancels the previous in-flight search so rapid keystrokes don't pile up.
     * Notifies AA of the result count when done so it can call [onGetSearchResult].
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        Timber.d("onSearch: query='$query' from=${browser.packageName}")
        val future = SettableFuture.create<LibraryResult<Void>>()
        activeSearchJob?.cancel()
        activeSearchJob = scope.launch(Dispatchers.IO) {
            val playlistItems = playlistDao.searchByName(query).map { entry ->
                val id = AutoMediaId.file(entry.uri)
                buildPlayableItem(id, entry.name, entry.uri.toUri())
            }
            val matchedFileNames = metadataDao
                .searchByNameOrFileName(query)
                .map { it.fileName }
                .toSet()
            val fileItems = resolveFileNamesInTree(query, matchedFileNames)

            // Deduplicate by mediaId (playlist + file tree can overlap).
            val seen = mutableSetOf<String>()
            val items = (playlistItems + fileItems).filter { seen.add(it.mediaId) }

            searchResultsCache[query] = items

            Timber.d("onSearch: found ${items.size} results for '$query'")
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid().also(future::set)
        }
        return future
    }

    /** Returns a page of results cached by the preceding [onSearch] call. */
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
        val libraryResult = LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
        return Futures.immediateFuture(libraryResult)
    }

    /**
     * Restores the persisted queue state so AA can resume playback on car start.
     * Returns a failed future (signal to AA that there is nothing to resume) when
     * there is no saved state, or it is empty.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Timber.d("onPlaybackResumption: isForPlayback=$isForPlayback")
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(Dispatchers.IO) {
            val state = prefs.getQueueState() ?: run {
                val ex = UnsupportedOperationException("No saved queue")
                future.setException(ex)
                return@launch
            }

            val files = try {
                Json.decodeFromString<List<ModuleFile>>(state.json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to decode queue for resumption")
                future.setException(e)
                return@launch
            }

            if (files.isEmpty()) {
                val ex = UnsupportedOperationException("Queue is empty")
                future.setException(ex)
                return@launch
            }

            val mediaItems = files.map { file ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(file.name)
                    .setIsPlayable(true)
                    .setArtworkUri(artworkUri)
                    .build()

                MediaItem.Builder()
                    .setUri(file.uri)
                    .setMediaId(file.uri.toString())
                    .setMediaMetadata(metadata)
                    .build()
            }

            MediaSession.MediaItemsWithStartPosition(
                mediaItems,
                state.index,
                state.positionMs
            ).also(future::set)
        }
        return future
    }

    /** Top-level categories shown when AA opens the app. */
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

    // TODO: implement real playlist browsing once playlists are fully persisted.

    /** Returns a stub playlist category until real playlist browsing is implemented. */
    private fun getPlaylists(): ImmutableList<MediaItem> = ImmutableList.of(
        buildBrowsableItem(
            "playlists_coming_soon",
            "Coming Soon",
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        )
    )

    /**
     * Returns sorted children of the last SAF tree the user opened.
     * Folders appear first (alphabetical), then files sorted by resolved module title.
     */
    private suspend fun getFileBrowserRoot(): ImmutableList<MediaItem> {
        val treeUri = prefs.getLastDirectoryUri()?.toUri() ?: return ImmutableList.of()
        return try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            buildChildItems(
                treeUri = treeUri,
                docId = DocumentsContract.getTreeDocumentId(treeUri)
            )
        } catch (e: SecurityException) {
            Timber.e(e, "No permission for $treeUri")
            ImmutableList.of()
        }
    }

    /** Returns sorted children of the encoded directory [parentId]. */
    private suspend fun getDirectoryChildren(parentId: String): ImmutableList<MediaItem> =
        buildChildItems(
            treeUri = AutoMediaId.treeUriFromDir(parentId),
            docId = AutoMediaId.docIdFromDir(parentId)
        )

    /**
     * Queries [docId] and returns a sorted [ImmutableList]:
     * - Subdirectories first, sorted by name (case-insensitive).
     * - Module files second, sorted by resolved module title from the DB
     *   (falling back to filename when not yet indexed).
     *
     * Uses [testFromFd] to validate files - only runs for the visible page,
     * so the cost is bounded to directory size.
     */
    private suspend fun buildChildItems(treeUri: Uri, docId: String): ImmutableList<MediaItem> {
        val entries = context.contentResolver.queryDirectoryEntries(treeUri, docId)
        val dirs = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = entries.filter {
            !it.isDirectory && Xmp.testFromFd(context, it.childUri, ModInfo())
        }

        val resolvedNames: Map<String, String> = if (files.isNotEmpty()) {
            metadataDao.getByFileNames(files.map { it.name })
                .associate { it.fileName to it.name.ifBlank { null }.orEmpty() }
        } else {
            emptyMap()
        }

        val sortedFiles = files.sortedBy { entry ->
            resolvedNames[entry.name]?.ifBlank { null }?.lowercase() ?: entry.name.lowercase()
        }

        val items = mutableListOf<MediaItem>()
        dirs.forEach {
            val item = buildBrowsableItem(
                id = AutoMediaId.dir(treeUri, it.docId),
                title = it.name,
                type = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            )
            items.add(item)
        }
        sortedFiles.forEach {
            val item = buildPlayableItem(
                id = AutoMediaId.file(it.childUri.toString()),
                title = it.name,
                uri = it.childUri
            )
            items.add(item)
        }
        return ImmutableList.copyOf(items)
    }

    /**
     * Builds a flat queue of every module in the SAF tree without calling [testFromFd].
     * Files known to the DB are matched by name; all others are matched by extension.
     * Pass [shuffle]=true to randomise the queue before returning.
     */
    private fun buildPlayAllFuture(
        shuffle: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch(Dispatchers.IO) {
            try {
                // Empty query to entry.name.contains("") is always true,
                // so every file whose extension is in moduleExtensions is included.
                val knownFileNames = metadataDao.getAllFileNames().toSet()
                val items = resolveFileNamesInTree("", knownFileNames).toMutableList()

                if (shuffle) items.shuffle()

                MediaSession.MediaItemsWithStartPosition(items, 0, 0L)
                    .also(future::set)
            } catch (e: Exception) {
                Timber.e(e, "buildPlayAllFuture failed")
                future.setException(e)
            }
        }
        return future
    }

    /**
     * Walks the SAF tree and returns items matching either:
     * - [fileNames] (DB-known, extension-independent), or
     * - [query] substring in the filename AND a recognised [moduleExtensions].
     *
     * An empty [query] matches every file with a known extension, which is used
     * by [buildPlayAllFuture] to collect all modules without [testFromFd].
     */
    private suspend fun resolveFileNamesInTree(
        query: String,
        fileNames: Set<String>
    ): List<MediaItem> {
        val treeUri = prefs.getLastDirectoryUri()?.toUri() ?: return emptyList()
        return try {
            resolveInDirectory(
                treeUri = treeUri,
                docId = DocumentsContract.getTreeDocumentId(treeUri),
                query = query,
                fileNames = fileNames
            )
        } catch (e: Exception) {
            Timber.e(e, "resolveFileNamesInTree failed for query='$query'")
            emptyList()
        }
    }

    /** Recursive helper for [resolveFileNamesInTree]. */
    private fun resolveInDirectory(
        treeUri: Uri,
        docId: String,
        query: String,
        fileNames: Set<String>
    ): List<MediaItem> {
        val results = mutableListOf<MediaItem>()
        val subdirDocIds = mutableListOf<String>()

        val entries = context.contentResolver
            .queryDirectoryEntries(treeUri, docId)
            .sortedBy { it.name }
        for (entry in entries) {
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            when {
                entry.isDirectory -> subdirDocIds.add(entry.docId)

                entry.name in fileNames ||
                    (entry.name.contains(query, ignoreCase = true) && ext in moduleExtensions) ->
                    buildPlayableItem(
                        id = AutoMediaId.file(entry.childUri.toString()),
                        title = entry.name,
                        uri = entry.childUri
                    ).also(results::add)

            }
        }
        subdirDocIds.forEach {
            results += resolveInDirectory(
                treeUri = treeUri,
                docId = it,
                query = query,
                fileNames = fileNames
            )
        }
        return results
    }

    /**
     * Recursively collects all valid module files under [docId] using [testFromFd].
     * Used only for the file-browser tap path (expanding a tapped file into siblings).
     * Results are sorted by name.
     */
    private fun buildAllItemsFlat(treeUri: Uri, docId: String): List<MediaItem> {
        val files = mutableListOf<MediaItem>()
        val subdirDocIds = mutableListOf<String>()
        val entries = context.contentResolver
            .queryDirectoryEntries(treeUri, docId)
            .sortedBy { it.name }
        for (entry in entries) {
            when {
                entry.isDirectory -> subdirDocIds.add(entry.docId)

                Xmp.testFromFd(context, entry.childUri, ModInfo()) ->
                    buildPlayableItem(
                        id = AutoMediaId.file(entry.childUri.toString()),
                        title = entry.name,
                        uri = entry.childUri
                    ).also(files::add)

            }
        }
        subdirDocIds.forEach { files += buildAllItemsFlat(treeUri = treeUri, docId = it) }
        return files
    }

    /** Builds a browsable (folder) item for the browse tree. */
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

    /** Builds a playable (leaf) item backed by a real SAF URI. */
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

    /**
     * Builds a playable action item (e.g. Play All, Shuffle All).
     * Has no backing URI - playback is handled entirely in [onSetMediaItems].
     */
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
