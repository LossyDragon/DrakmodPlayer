package com.lossydragon.modplayer.data

import android.content.Context
import android.net.Uri
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.util.queryDirectoryEntries
import com.lossydragon.modplayer.util.resolveDocId
import kotlinx.coroutines.flow.Flow
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ModInfo

class ModuleRepository(
    private val context: Context,
    private val dao: ModuleDao
) {

    fun getChildren(parentPath: String): Flow<List<ModuleEntity>> = dao.getChildren(parentPath)

    suspend fun indexDirectory(uri: Uri) {
        val existingPaths = dao.getAllFilePaths().toHashSet()
        indexInternal(uri, existingPaths)
    }

    suspend fun reindexDirectory(uri: Uri) {
        val allPaths = dao.getAllFilePaths().toHashSet()
        val skipPaths = dao.getConfirmedPaths().toHashSet()
        val seenPaths = mutableSetOf<String>()
        indexInternal(uri, skipPaths, seenPaths)
        val removed = allPaths - seenPaths
        if (removed.isNotEmpty()) dao.deleteByFilePaths(removed.toList())
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
                        val isValid = Xmp.testFromFd(context, entry.childUri, modInfo)
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
