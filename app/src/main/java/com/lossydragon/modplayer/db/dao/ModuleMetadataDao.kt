package com.lossydragon.modplayer.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lossydragon.modplayer.db.entity.ModuleMetadataEntity

@Dao
interface ModuleMetadataDao {

    @Query("SELECT * FROM module_metadata WHERE fileName IN (:fileNames)")
    suspend fun getByFileNames(fileNames: List<String>): List<ModuleMetadataEntity>

    @Query(
        "SELECT * FROM module_metadata WHERE fileName = :fileName AND sizeBytes = :sizeBytes LIMIT 1"
    )
    suspend fun get(fileName: String, sizeBytes: Long): ModuleMetadataEntity?

    @Upsert
    suspend fun upsert(entity: ModuleMetadataEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ModuleMetadataEntity>)

    @Query("DELETE FROM module_metadata WHERE lastSeen < :cutoff")
    suspend fun removeStale(cutoff: Long)

    @Query(
        """
        SELECT * FROM module_metadata
        WHERE name LIKE '%' || :query || '%'
        OR fileName LIKE '%' || :query || '%'
    """
    )
    suspend fun searchByNameOrFileName(query: String): List<ModuleMetadataEntity>

    @Query("SELECT fileName FROM module_metadata")
    suspend fun getAllFileNames(): List<String>
}
