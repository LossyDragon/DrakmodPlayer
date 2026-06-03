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

    fun getAllModules(): Flow<List<ModuleEntity>> = dao.getAllModules()

    fun getChildren(parentPath: String): Flow<List<ModuleEntity>> = dao.getChildren(parentPath)

    suspend fun getByFilePaths(paths: List<String>): List<ModuleEntity> =
        dao.getByFilePaths(paths)

    suspend fun indexDirectory(uri: Uri) {
        val resolver = context.contentResolver
        val rootDocId = uri.resolveDocId(context)

        // Stack of (docId, parentPath) — parentPath is the URI string used to query children later
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootDocId to uri.toString())

        while (stack.isNotEmpty()) {
            val (docId, parentPath) = stack.removeLast()
            val entries = resolver.queryDirectoryEntries(uri, docId)

            for (entry in entries) {
                if (entry.isDirectory) {
                    dao.upsert(
                        ModuleEntity(
                            filename = entry.name,
                            fileExtension = "",
                            filePath = entry.childUri.toString(),
                            parentPath = parentPath,
                            fileSize = 0L,
                            isDirectory = true,
                        )
                    )
                    stack.addLast(entry.docId to entry.childUri.toString())
                } else {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    val modInfo = ModInfo()
                    val isValid = Xmp.testFromFd(context, entry.childUri, modInfo)
                    dao.upsert(
                        ModuleEntity(
                            filename = entry.name,
                            fileExtension = ext,
                            filePath = entry.childUri.toString(),
                            parentPath = parentPath,
                            fileSize = entry.size,
                            isDirectory = false,
                            isValidModule = isValid,
                            moduleName = modInfo.name,
                            moduleType = modInfo.type,
                        )
                    )
                }
            }
        }
    }
}
