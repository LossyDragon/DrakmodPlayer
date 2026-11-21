package com.lossydragon.modplayer.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lossydragon.modplayer.db.entity.DownloadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY viewedAt DESC LIMIT :limit")
    abstract fun getAllFlow(limit: Int): Flow<List<DownloadHistoryEntity>>

    @Upsert
    abstract suspend fun upsert(entity: DownloadHistoryEntity)

    @Query(
        "DELETE FROM download_history WHERE id NOT IN " +
            "(SELECT id FROM download_history ORDER BY viewedAt DESC LIMIT :keep)"
    )
    abstract suspend fun prune(keep: Int)

    @Transaction
    open suspend fun upsertAndPrune(entity: DownloadHistoryEntity, keep: Int) {
        upsert(entity)
        prune(keep)
    }

    @Query("DELETE FROM download_history")
    abstract suspend fun clearAll()

    companion object {
        const val HISTORY_LIMIT = 100
    }
}
