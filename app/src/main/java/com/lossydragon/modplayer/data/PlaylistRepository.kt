package com.lossydragon.modplayer.data

import com.lossydragon.modplayer.db.dao.PlaylistDao
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntryEntity
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(private val dao: PlaylistDao) {

    val playlists: Flow<List<PlaylistEntity>> = dao.getAllPlaylists()

    suspend fun createPlaylist(entity: PlaylistEntity): Long = dao.createPlaylist(entity)

    suspend fun createPlaylist(name: String, comment: String): Long =
        dao.createPlaylist(PlaylistEntity(name = name, comment = comment))

    suspend fun addToPlaylist(playlistId: Long, file: ModuleEntity) {
        val entries = dao.getEntries(playlistId)
        dao.addEntry(
            PlaylistEntryEntity(
                playlistId = playlistId,
                position = entries.size,
                uri = file.filePath,
                name = file.name,
                extension = file.fileExtension,
            )
        )
    }

    suspend fun getPlaylistFiles(playlistId: Long): List<ModuleEntity> =
        dao.getEntries(playlistId).map {
            ModuleEntity(
                filename = it.name,
                fileExtension = it.extension,
                filePath = it.uri,
                fileSize = 0L,
            )
        }

    suspend fun deletePlaylist(id: Long) {
        dao.clearEntries(id)
        dao.deletePlaylist(id)
    }

    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity> =
        dao.getAllPlaylistsOnce()

    suspend fun getEntriesRaw(playlistId: Long): List<PlaylistEntryEntity> =
        dao.getEntries(playlistId)

    suspend fun addRawEntry(entry: PlaylistEntryEntity) =
        dao.addEntry(entry)

    suspend fun renamePlaylist(id: Long, name: String, comment: String) =
        dao.updatePlaylist(id, name, comment)

    suspend fun removeFromPlaylist(playlistId: Long, uri: String) =
        dao.removeEntry(playlistId, uri)

    suspend fun reorderEntries(playlistId: Long, files: List<ModuleEntity>) {
        dao.clearEntries(playlistId)
        files.forEachIndexed { index, file ->
            dao.addEntry(
                PlaylistEntryEntity(
                    playlistId = playlistId,
                    position = index,
                    uri = file.filePath,
                    name = file.name,
                    extension = file.fileExtension,
                )
            )
        }
    }
}
