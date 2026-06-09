package com.lossydragon.modplayer.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lossydragon.modplayer.db.dao.DownloadHistoryDao
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.dao.PlaylistDao
import com.lossydragon.modplayer.db.entity.DownloadHistoryEntity
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntryEntity

@Database(
    entities = [
        ModuleEntity::class,
        DownloadHistoryEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun moduleMetadataDao(): ModuleDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun playlistDao(): PlaylistDao
}
