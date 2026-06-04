package com.lossydragon.modplayer.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lossydragon.modplayer.db.entity.DownloadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadHistoryDao {

    @Query("SELECT * FROM download_history ORDER BY viewedAt DESC")
    fun getAllFlow(): Flow<List<DownloadHistoryEntity>>

    @Upsert
    suspend fun upsert(entity: DownloadHistoryEntity)

    @Query("DELETE FROM download_history")
    suspend fun clearAll()
}
