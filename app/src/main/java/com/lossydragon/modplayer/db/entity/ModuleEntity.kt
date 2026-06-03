package com.lossydragon.modplayer.db.entity

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Immutable
@Serializable
@Entity(tableName = "modules")
data class ModuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val filename: String,
    val fileExtension: String,
    val filePath: String,
    @ColumnInfo(defaultValue = "") val parentPath: String = "", // TODO why ColumnInfo
    val fileSize: Long,
    @ColumnInfo(defaultValue = "0") val isDirectory: Boolean = false, // TODO why ColumnInfo
    val isValidModule: Boolean = false,
    val moduleName: String = "",
    val moduleType: String = ""
) {
    val name: String get() = moduleName.ifBlank { filename.ifBlank { "<untitled>" } }

    val type: String get() = moduleType.ifBlank { fileExtension.ifBlank { "..." } }

    val uri: Uri get() = filePath.toUri()
}
