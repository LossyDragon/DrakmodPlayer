package com.lossydragon.modplayer.data

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.util.DirectoryEntry
import com.lossydragon.renderer.model.ModInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

enum class IndexKind { INITIAL, FULL, REFRESH }

sealed interface IndexState {
    data object Idle : IndexState
    data class Running(val kind: IndexKind) : IndexState

    /** The root grant is gone (revoked, or the folder was removed). */
    data object NoAccess : IndexState
    data class Error(val message: String) : IndexState
}

class ModuleRepository(
    private val saf: SafDataSource,
    private val dao: ModuleDao,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope
) {

    private companion object {
        /** How long the index is trusted before a cold start walks the tree again. */
        const val INDEX_MAX_AGE_MS = 15 * 60 * 1000L
        const val SQL_CHUNK = 900
    }

    private val _indexState = MutableStateFlow<IndexState>(IndexState.Idle)

    /** Progress of the background tree walk; survives ViewModel recreation. */
    val indexState: StateFlow<IndexState> = _indexState.asStateFlow()

    private var indexJob: Job? = null

    fun getChildren(parentPath: String): Flow<List<ModuleEntity>> = dao.getChildren(parentPath)

    suspend fun getDescendantModules(rootPath: String): List<ModuleEntity> =
        dao.getDescendantModules(rootPath)

    /**
     * Makes [uri] the music root: persists the grant (releasing the previous root's), stores
     * it and starts a full index.
     */
    suspend fun setRootFolder(uri: Uri) {
        val previous = prefs.getLastDirectoryUri()?.toUri()
        saf.replaceRootGrant(uri, previous)
        prefs.setLastDirectoryUri(uri.toString())
        indexDirectory(uri, restart = true)
    }

    /** @param restart Cancel a running index (of the old root) instead of deferring to it. */
    fun indexDirectory(uri: Uri, restart: Boolean = false) =
        startIndex(uri, IndexKind.FULL, restart) { fullIndex(uri) }

    /** [indexDirectory], unless one ran within [INDEX_MAX_AGE_MS] and the table is non-empty. */
    fun indexDirectoryIfStale(uri: Uri) = startIndex(uri, IndexKind.INITIAL) {
        val fresh = System.currentTimeMillis() - prefs.getLastIndexedAt() < INDEX_MAX_AGE_MS
        if (fresh && dao.count() > 0) false else fullIndex(uri)
    }

    fun reindexDirectory(uri: Uri) = startIndex(uri, IndexKind.REFRESH) {
        val allPaths = dao.getAllFilePaths().toHashSet()
        val seenPaths = HashSet<String>()
        indexInternal(uri, dao.getConfirmedPaths().toHashSet(), seenPaths)
        val removed = allPaths - seenPaths
        deletePaths(removed)
        true
    }

    private suspend fun fullIndex(uri: Uri): Boolean {
        indexInternal(uri, dao.getAllFilePaths().toHashSet())
        return true
    }

    /** Chunked: older SQLite caps a statement at 999 bound variables. */
    private suspend fun deletePaths(paths: Collection<String>) =
        paths.chunked(SQL_CHUNK).forEach { dao.deleteByFilePaths(it) }

    /**
     * Runs [block] on the app scope unless an index is already running; with [restart] the
     * running one is cancelled first. [block] returns false when it did nothing, which
     * leaves `lastIndexedAt` untouched.
     */
    private fun startIndex(
        uri: Uri,
        kind: IndexKind,
        restart: Boolean = false,
        block: suspend () -> Boolean
    ) {
        val running = indexJob?.takeIf { it.isActive }
        if (running != null && !restart) return
        if (!saf.hasPersistedGrant(uri)) {
            _indexState.value = IndexState.NoAccess
            return
        }
        running?.cancel()
        _indexState.value = IndexState.Running(kind)
        indexJob = scope.launch(Dispatchers.IO) {
            if (running != null) {
                running.join()
                _indexState.value = IndexState.Running(kind) // the cancelled job set Idle
            }
            try {
                if (block()) prefs.setLastIndexedAt(System.currentTimeMillis())
                _indexState.value = IndexState.Idle
            } catch (e: CancellationException) {
                _indexState.value = IndexState.Idle
                throw e
            } catch (e: SecurityException) {
                Timber.w(e, "Lost access to $uri")
                _indexState.value = IndexState.NoAccess
            } catch (e: Exception) {
                Timber.e(e, "Indexing $uri failed")
                _indexState.value = IndexState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Re-reads just [dirUri] (e.g. after a download). Falls back to a full index when the
     * directory itself is unknown, since its ancestors would be missing too.
     */
    suspend fun indexDirectoryShallow(rootUri: Uri, dirUri: Uri) {
        val parentPath = dirUri.toString()
        if (!dao.exists(parentPath)) {
            indexDirectory(rootUri)
            return
        }
        val known = dao.getChildrenOnce(parentPath)
        val skip = known.filter {
            it.isDirectory || it.isValidModule
        }.map { it.filePath }.toHashSet()
        val entries = saf.queryDirectoryEntries(rootUri, DocumentsContract.getDocumentId(dirUri))
        upsertEntries(entries, parentPath, skip)
        val gone = known.map { it.filePath } - entries.map { it.childUri.toString() }.toSet()
        deletePaths(gone)
    }

    private suspend fun indexInternal(
        uri: Uri,
        skipPaths: Set<String>,
        seenPaths: MutableSet<String>? = null
    ) {
        // Stack of (docId, parentPath) — parentPath is the URI string used to query children later
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(saf.resolveDocId(uri) to uri.toString())

        while (stack.isNotEmpty()) {
            val (docId, parentPath) = stack.removeLast()
            val entries = saf.queryDirectoryEntries(uri, docId)
            for (entry in entries) {
                seenPaths?.add(entry.childUri.toString())
                if (entry.isDirectory) stack.addLast(entry.docId to entry.childUri.toString())
            }
            upsertEntries(entries, parentPath, skipPaths)
        }
    }

    /** One batched write per directory. */
    private suspend fun upsertEntries(
        entries: List<DirectoryEntry>,
        parentPath: String,
        skip: Set<String>
    ) {
        val rows = entries.mapNotNull { entry ->
            val path = entry.childUri.toString()
            if (path in skip) return@mapNotNull null
            if (entry.isDirectory) {
                ModuleEntity(
                    filename = entry.name.trim(),
                    filePath = path,
                    parentPath = parentPath,
                    isDirectory = true,
                )
            } else {
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                val modInfo = ModInfo()
                // Testing reads the whole file into memory; don't do that for
                // things that are obviously not modules (archives, images, mp3s...).
                val isValid = ext !in Constants.SKIP_EXTENSIONS &&
                    saf.testModule(entry.childUri, modInfo)
                ModuleEntity(
                    filename = entry.name.trim(),
                    fileExtension = ext,
                    filePath = path,
                    parentPath = parentPath,
                    fileSize = entry.size,
                    isValidModule = isValid,
                    moduleName = modInfo.name.trim(),
                    moduleType = modInfo.type.trim(),
                )
            }
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }
}
