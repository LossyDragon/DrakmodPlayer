package com.lossydragon.modplayer.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lossydragon.modplayer.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

private const val CHILDREN_SQL =
    "SELECT * FROM modules WHERE parentPath = :parentPath ORDER BY isDirectory DESC, filename ASC"

@Dao
interface ModuleDao {

    /**
     * Insert-or-replace keyed on the unique `filePath` index, in one transaction. (`@Upsert`
     * would key on the auto-generated `id`, which is always 0 for a freshly scanned entity,
     * and so always inserts a duplicate.) REPLACE assigns a new `id`; nothing references it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ModuleEntity>)

    @Query(CHILDREN_SQL)
    fun getChildren(parentPath: String): Flow<List<ModuleEntity>>

    @Query(CHILDREN_SQL)
    suspend fun getChildrenOnce(parentPath: String): List<ModuleEntity>

    /**
     * Every valid module at any depth below [rootPath], in path order. Walks `parentPath`
     * links so it works for providers whose document ids are not path-shaped.
     */
    @Query(
        "WITH RECURSIVE dirs(path) AS (" +
            "SELECT :rootPath " +
            "UNION ALL " +
            "SELECT m.filePath FROM modules m JOIN dirs ON m.parentPath = dirs.path " +
            "WHERE m.isDirectory = 1) " +
            "SELECT * FROM modules WHERE parentPath IN (SELECT path FROM dirs) " +
            "AND isValidModule = 1 AND isDirectory = 0 ORDER BY filePath"
    )
    suspend fun getDescendantModules(rootPath: String): List<ModuleEntity>

    @Query("SELECT * FROM modules WHERE isValidModule = 1 AND isDirectory = 0")
    suspend fun getAllValidModules(): List<ModuleEntity>

    @Query("SELECT COUNT(*) FROM modules")
    suspend fun count(): Int

    /** Emits after every write to the table; the value means nothing. */
    @Query("SELECT COUNT(*) FROM modules")
    fun changes(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM modules WHERE filePath = :path)")
    suspend fun exists(path: String): Boolean

    @Query("SELECT filePath FROM modules")
    suspend fun getAllFilePaths(): List<String>

    @Query("SELECT filePath FROM modules WHERE isDirectory = 1 OR isValidModule = 1")
    suspend fun getConfirmedPaths(): List<String>

    /** Callers chunk [paths]; one statement binds one variable per path. */
    @Query("DELETE FROM modules WHERE filePath IN (:paths)")
    suspend fun deleteByFilePaths(paths: List<String>)

    @Query("SELECT * FROM modules WHERE filePath IN (:paths)")
    suspend fun getByFilePaths(paths: List<String>): List<ModuleEntity>

    /** Valid modules whose title or filename contains [query]. */
    @Query(
        "SELECT * FROM modules WHERE isValidModule = 1 AND isDirectory = 0 AND " +
            "(moduleName LIKE '%' || :query || '%' OR filename LIKE '%' || :query || '%')"
    )
    suspend fun searchBy(query: String): List<ModuleEntity>
}
