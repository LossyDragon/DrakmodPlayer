package com.lossydragon.modplayer.data

import com.lossydragon.modplayer.db.dao.DownloadHistoryDao
import com.lossydragon.modplayer.db.entity.DownloadHistoryEntity
import com.lossydragon.modplayer.model.Artist
import com.lossydragon.modplayer.model.ArtistInfo
import com.lossydragon.modplayer.model.Module
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadHistoryRepository(private val dao: DownloadHistoryDao) {

    fun getAllFlow(): Flow<List<Module>> = dao.getAllFlow()
        .map { entities -> entities.map { it.toModule() } } // wow

    suspend fun add(module: Module) = dao.upsert(module.toEntity())

    suspend fun clear() = dao.clearAll()

    private fun DownloadHistoryEntity.toModule() = Module(
        id = id,
        filename = filename,
        songtitle = songTitle,
        format = format,
        bytes = bytes,
        artistInfo = ArtistInfo(
            artist = listOf(Artist(alias = artist))
        ),
    )

    private fun Module.toEntity() = DownloadHistoryEntity(
        id = id,
        filename = filename,
        songTitle = songtitle,
        format = format,
        bytes = bytes,
        artist = artist,
        viewedAt = System.currentTimeMillis(),
    )
}
