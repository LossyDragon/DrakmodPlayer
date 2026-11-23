package com.lossydragon.modplayer.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    abstract fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    abstract suspend fun getEntries(id: Long): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    abstract suspend fun getAllEntries(): List<PlaylistEntryEntity>

    @Query("SELECT uri FROM playlist_entries WHERE playlistId = :id")
    abstract suspend fun getEntryUris(id: Long): List<String>

    @Query("SELECT MAX(position) FROM playlist_entries WHERE playlistId = :id")
    abstract suspend fun getMaxPosition(id: Long): Int?

    @Insert
    abstract suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Insert
    abstract suspend fun addEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND uri = :uri")
    abstract suspend fun removeEntry(playlistId: Long, uri: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    abstract suspend fun clearEntries(id: Long)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    abstract suspend fun getAllPlaylistsOnce(): List<PlaylistEntity>

    @Query("UPDATE playlists SET name = :name, comment = :comment WHERE id = :id")
    abstract suspend fun updatePlaylist(id: Long, name: String, comment: String)

    @Query("SELECT * FROM playlist_entries WHERE name LIKE '%' || :query || '%' ORDER BY name")
    abstract suspend fun searchByName(query: String): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Query(
        "SELECT * FROM playlists WHERE name = :name COLLATE NOCASE ORDER BY createdAt DESC LIMIT 1"
    )
    abstract suspend fun findPlaylistByName(name: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_entries WHERE uri = :uri LIMIT 1")
    abstract suspend fun findEntryByUri(uri: String): PlaylistEntryEntity?

    /** Emits after every write to the playlists or their entries; the value means nothing. */
    @Query("SELECT (SELECT COUNT(*) FROM playlists) + (SELECT COUNT(*) FROM playlist_entries)")
    abstract fun changes(): Flow<Int>

    /**
     * Appends [entries] after the current last position, skipping URIs already in the playlist
     * (and duplicates within [entries]). Incoming positions are ignored. Returns the count added.
     */
    @Transaction
    open suspend fun appendEntries(playlistId: Long, entries: List<PlaylistEntryEntity>): Int {
        val existing = getEntryUris(playlistId).toHashSet()
        val fresh = entries.filter { existing.add(it.uri) }
        if (fresh.isEmpty()) return 0
        var next = (getMaxPosition(playlistId) ?: -1) + 1
        addEntries(fresh.map { it.copy(playlistId = playlistId, position = next++) })
        return fresh.size
    }

    /** Replaces the whole entry list; positions are renumbered from 0 in list order. */
    @Transaction
    open suspend fun replaceEntries(playlistId: Long, entries: List<PlaylistEntryEntity>) {
        clearEntries(playlistId)
        if (entries.isEmpty()) return
        addEntries(entries.mapIndexed { i, e -> e.copy(playlistId = playlistId, position = i) })
    }

    @Transaction
    open suspend fun deletePlaylistWithEntries(id: Long) {
        clearEntries(id)
        deletePlaylist(id)
    }

    /** Creates [playlist] with [entries] (deduplicated, renumbered). Returns the new id. */
    @Transaction
    open suspend fun createPlaylistWithEntries(
        playlist: PlaylistEntity,
        entries: List<PlaylistEntryEntity>
    ): Long {
        val id = createPlaylist(playlist)
        appendEntries(id, entries)
        return id
    }
}
