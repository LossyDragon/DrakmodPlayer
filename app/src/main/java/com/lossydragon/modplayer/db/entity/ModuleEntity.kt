package com.lossydragon.modplayer.db.entity

import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.net.toUri
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Immutable
@Serializable
@Entity(tableName = "modules")
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
