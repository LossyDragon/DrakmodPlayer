package com.lossydragon.modplayer.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lossydragon.modplayer.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleDao {

    @Upsert
    suspend fun upsert(item: ModuleEntity)

    @Query("UPDATE modules SET isValidModule = :isValid WHERE filePath = :uri")
    suspend fun markValidModule(uri: String, isValid: Boolean)

    @Query("SELECT * FROM modules")
    fun getAllModules(): Flow<List<ModuleEntity>>

    @Query(
        "SELECT * FROM modules WHERE parentPath = :parentPath ORDER BY isDirectory DESC, filename ASC"
    )
    fun getChildren(parentPath: String): Flow<List<ModuleEntity>>

    @Query(
        "SELECT * FROM modules WHERE parentPath = :parentPath ORDER BY isDirectory DESC, filename ASC"
    )
    suspend fun getChildrenOnce(parentPath: String): List<ModuleEntity>

    @Query("SELECT * FROM modules WHERE isValidModule = 1 AND isDirectory = 0")
    suspend fun getAllValidModules(): List<ModuleEntity>

    @Query("SELECT * FROM modules WHERE filePath IN (:paths)")
    suspend fun getByFilePaths(paths: List<String>): List<ModuleEntity>

    @Query(
        """
        SELECT * FROM modules
        WHERE moduleName LIKE '%' || :query || '%'
        OR fileName LIKE '%' || :query || '%'
        """
    )
    suspend fun searchBy(query: String): List<ModuleEntity>
}
