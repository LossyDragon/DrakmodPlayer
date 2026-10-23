package com.lossydragon.modplayer.data

import android.content.Context
import android.net.Uri
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.util.queryDirectoryEntries
import com.lossydragon.modplayer.util.resolveDocId
import com.lossydragon.native.Player
import com.lossydragon.native.model.ModInfo
import kotlinx.coroutines.flow.Flow

class ModuleRepository(
    private val context: Context,
    private val dao: ModuleDao,
    private val prefs: AppPreferences
) {

    private companion object {
        /** How long the index is trusted before a cold start walks the tree again. */
        const val INDEX_MAX_AGE_MS = 15 * 60 * 1000L
    }

    fun getChildren(parentPath: String): Flow<List<ModuleEntity>> = dao.getChildren(parentPath)

    suspend fun getDescendantModules(rootPath: String): List<ModuleEntity> {
        val result = mutableListOf<ModuleEntity>()
        val stack = ArrayDeque<String>()
        stack.addLast(rootPath)
        while (stack.isNotEmpty()) {
            val path = stack.removeLast()
            val children = dao.getChildrenOnce(path)
            children.filter { it.isValidModule && !it.isDirectory }
                .sortedBy { it.filename.lowercase() }
                .forEach { result.add(it) }
            // Push in reverse so the first alphabetically is popped next
            children.filter { it.isDirectory }
                .sortedByDescending { it.filename.lowercase() }
                .forEach { stack.addLast(it.filePath) }
        }
        return result
    }

    suspend fun indexDirectory(uri: Uri) {
        val existingPaths = dao.getAllFilePaths().toHashSet()
        indexInternal(uri, existingPaths)
        prefs.setLastIndexedAt(System.currentTimeMillis())
    }

    /** [indexDirectory], unless one ran within the last [INDEX_MAX_AGE_MS]. */
    suspend fun indexDirectoryIfStale(uri: Uri) {
        if (System.currentTimeMillis() - prefs.getLastIndexedAt() < INDEX_MAX_AGE_MS) return
        indexDirectory(uri)
    }

    suspend fun reindexDirectory(uri: Uri) {
        val allPaths = dao.getAllFilePaths().toHashSet()
        val skipPaths = dao.getConfirmedPaths().toHashSet()
        val seenPaths = mutableSetOf<String>()
        indexInternal(uri, skipPaths, seenPaths)
        val removed = allPaths - seenPaths
        if (removed.isNotEmpty()) dao.deleteByFilePaths(removed.toList())
        prefs.setLastIndexedAt(System.currentTimeMillis())
    }

    private suspend fun indexInternal(
        uri: Uri,
        skipPaths: Set<String>,
        seenPaths: MutableSet<String>? = null
    ) {
        val resolver = context.contentResolver
        val rootDocId = uri.resolveDocId(context)

        // Stack of (docId, parentPath) — parentPath is the URI string used to query children later
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootDocId to uri.toString())

        while (stack.isNotEmpty()) {
            val (docId, parentPath) = stack.removeLast()
            val entries = resolver.queryDirectoryEntries(uri, docId)

            for (entry in entries) {
                val path = entry.childUri.toString()
                seenPaths?.add(path)
                if (entry.isDirectory) {
                    if (path !in skipPaths) {
                        dao.upsert(
                            ModuleEntity(
                                filename = entry.name.trim(),
                                fileExtension = "",
                                filePath = path,
                                parentPath = parentPath,
                                fileSize = 0L,
                                isDirectory = true,
                            )
                        )
                    }
                    stack.addLast(entry.docId to path)
                } else {
                    if (path !in skipPaths) {
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        val modInfo = ModInfo()
                        // Testing reads the whole file into memory; don't do that for
                        // things that are obviously not modules (archives, images, mp3s...).
                        val isValid = ext !in Constants.SKIP_EXTENSIONS &&
                            Player.testFromFd(context, entry.childUri, modInfo)
                        dao.upsert(
                            ModuleEntity(
                                filename = entry.name.trim(),
                                fileExtension = ext,
                                filePath = path,
                                parentPath = parentPath,
                                fileSize = entry.size,
                                isDirectory = false,
                                isValidModule = isValid,
                                moduleName = modInfo.name.trim(),
                                moduleType = modInfo.type.trim(),
                            )
                        )
                    }
                }
            }
        }
    }
}
