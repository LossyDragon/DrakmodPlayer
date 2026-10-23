package com.lossydragon.modplayer.db.entity

import androidx.room.Entity

// Junction Table
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val uri: String,
    val name: String,
    val extension: String
)

/** The playable form of an entry; size/metadata are unknown until the module is loaded. */
fun PlaylistEntryEntity.toModuleEntity(): ModuleEntity = ModuleEntity(
    filename = name,
    fileExtension = extension,
    filePath = uri,
    fileSize = 0L,
)
