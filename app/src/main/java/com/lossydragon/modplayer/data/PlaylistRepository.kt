package com.lossydragon.modplayer.data

import android.net.Uri
import androidx.core.net.toUri
import com.lossydragon.modplayer.db.dao.PlaylistDao
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntryEntity
import com.lossydragon.modplayer.db.entity.toModuleEntity
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(
    private val dao: PlaylistDao,
    private val saf: SafDataSource
) {

    val playlists: Flow<List<PlaylistEntity>> = dao.getAllPlaylists()

    suspend fun createPlaylist(name: String, comment: String): Long =
        dao.createPlaylist(PlaylistEntity(name = name, comment = comment))

    /** Appends [file] unless its URI is already in the playlist. Returns 1 or 0. */
    suspend fun addToPlaylist(playlistId: Long, file: ModuleEntity): Int =
        addAllToPlaylist(playlistId, listOf(file))

    /** Appends [files], skipping URIs already present. Returns how many were added. */
    suspend fun addAllToPlaylist(playlistId: Long, files: List<ModuleEntity>): Int =
        dao.appendEntries(playlistId, files.map { it.toEntry(playlistId) })

    suspend fun getPlaylistFiles(playlistId: Long): List<ModuleEntity> =
        dao.getEntries(playlistId).map { it.toModuleEntity() }

    suspend fun deletePlaylist(id: Long) = dao.deletePlaylistWithEntries(id)

    suspend fun renamePlaylist(id: Long, name: String, comment: String) =
        dao.updatePlaylist(id, name, comment)

    suspend fun removeFromPlaylist(playlistId: Long, uri: String) =
        dao.removeEntry(playlistId, uri)

    suspend fun reorderEntries(playlistId: Long, files: List<ModuleEntity>) =
        dao.replaceEntries(playlistId, files.map { it.toEntry(playlistId) })

    /** Writes every playlist as JSON to [outputUri]. */
    suspend fun exportTo(outputUri: Uri) {
        val all = dao.getAllPlaylistsOnce()
        val entries = dao.getAllEntries().groupBy { it.playlistId }
        saf.writeText(outputUri, exportPlaylistsToJson(all, entries))
    }

    /** Reads JSON from [inputUri]; each playlist is inserted in its own transaction. */
    suspend fun importFrom(inputUri: Uri, validateUris: Boolean): ImportResult {
        val export = parsePlaylistExport(saf.readText(inputUri))
        val check: ((String) -> Boolean)? =
            if (validateUris) { uri -> saf.isAccessible(uri.toUri()) } else null
        val batch = export.toRoomEntities(check)
        batch.playlists.forEach { dao.createPlaylistWithEntries(it.playlist, it.entries) }
        return batch.result
    }

    private fun ModuleEntity.toEntry(playlistId: Long) = PlaylistEntryEntity(
        playlistId = playlistId,
        position = 0,
        uri = filePath,
        name = name,
        extension = fileExtension,
    )
}
