package com.lossydragon.modplayer.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun moduleMetadataDao(): ModuleDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        /**
         * Versions that predate playlists / have no upgrade path. Anything at or above
         * [LAST_DESTRUCTIVE_VERSION] must migrate so user playlists survive.
         */
        const val LAST_DESTRUCTIVE_VERSION = 6

        /**
         * 7 → 8: `modules` gains a unique index on `filePath` and an index on `parentPath`.
         * Older versions could accumulate duplicate rows per path (see [ModuleDao.upsertAll]),
         * so those are collapsed first or the unique index would fail to build.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "DELETE FROM modules WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM modules GROUP BY filePath)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_modules_filePath` " +
                        "ON `modules` (`filePath`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_modules_parentPath` " +
                        "ON `modules` (`parentPath`)"
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_7_8)
    }
}
