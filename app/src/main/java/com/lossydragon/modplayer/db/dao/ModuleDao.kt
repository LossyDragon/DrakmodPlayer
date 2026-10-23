package com.lossydragon.modplayer.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lossydragon.modplayer.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleDao {

    /**
     * Insert-or-replace keyed on the unique `filePath` index. (`@Upsert` would key on the
     * auto-generated `id`, which is always 0 for a freshly scanned entity, and so always
     * inserts a duplicate.) REPLACE assigns a new `id`; nothing references it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ModuleEntity)

    @Query(
        "SELECT * FROM modules WHERE parentPath = :parentPath " +
            "ORDER BY isDirectory DESC, filename ASC"
    )
    fun getChildren(parentPath: String): Flow<List<ModuleEntity>>

    @Query(
        "SELECT * FROM modules WHERE parentPath = :parentPath " +
            "ORDER BY isDirectory DESC, filename ASC"
    )
    suspend fun getChildrenOnce(parentPath: String): List<ModuleEntity>

    @Query("SELECT * FROM modules WHERE isValidModule = 1 AND isDirectory = 0")
    suspend fun getAllValidModules(): List<ModuleEntity>

    @Query("SELECT filePath FROM modules")
    suspend fun getAllFilePaths(): List<String>

    @Query("SELECT filePath FROM modules WHERE isDirectory = 1 OR isValidModule = 1")
    suspend fun getConfirmedPaths(): List<String>

    @Query("DELETE FROM modules WHERE filePath IN (:paths)")
    suspend fun deleteByFilePaths(paths: List<String>)

    @Query("SELECT * FROM modules WHERE filePath IN (:paths)")
    suspend fun getByFilePaths(paths: List<String>): List<ModuleEntity>

    @Query(
        "SELECT * FROM modules WHERE moduleName LIKE " +
            "'%' || :query || '%' OR fileName LIKE '%' || :query || '%'"
    )
    suspend fun searchBy(query: String): List<ModuleEntity>
}
