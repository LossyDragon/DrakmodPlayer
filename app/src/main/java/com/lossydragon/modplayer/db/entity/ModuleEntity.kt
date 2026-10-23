package com.lossydragon.modplayer.db.entity

import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One indexed SAF entry (file or directory). [filePath] is the natural key; [id] only
 * exists so Room has a rowid-style PK and is never referenced elsewhere.
 */
@Immutable
@Serializable
@Entity(
    tableName = "modules",
    indices = [
        Index(value = ["filePath"], unique = true),
        Index(value = ["parentPath"]),
    ],
)
data class ModuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val filename: String = "",
    val fileExtension: String = "",
    val filePath: String = "",
    val parentPath: String = "",
    val fileSize: Long = 0L,
    val isDirectory: Boolean = false,
    val isValidModule: Boolean = false,
    val moduleName: String = "",
    val moduleType: String = ""
) {
    val name: String get() = moduleName.ifBlank { filename.ifBlank { "<untitled>" } }

    val type: String get() = moduleType.ifBlank { fileExtension.ifBlank { "..." } }

    val uri: Uri get() = filePath.toUri()
}
