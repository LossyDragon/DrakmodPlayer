package com.lossydragon.modplayer.data

import androidx.compose.runtime.Immutable
import com.lossydragon.modplayer.db.dao.DownloadHistoryDao
import com.lossydragon.modplayer.db.entity.DownloadHistoryEntity
import com.lossydragon.modplayer.model.Module
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A previously viewed ModArchive module; only what the history list shows. */
@Immutable
data class DownloadHistoryItem(
    val id: Int,
    val filename: String,
    val songtitle: String,
    val format: String,
    val bytes: Int,
    val artist: String,
    val viewedAt: Long
)

class DownloadHistoryRepository(private val dao: DownloadHistoryDao) {

    fun getAllFlow(): Flow<List<DownloadHistoryItem>> = dao.getAllFlow(
        DownloadHistoryDao.HISTORY_LIMIT
    )
        .map { entities -> entities.map { it.toItem() } }

    suspend fun add(module: Module) =
        dao.upsertAndPrune(historyEntityOf(module), DownloadHistoryDao.HISTORY_LIMIT)

    suspend fun clear() = dao.clearAll()

    private fun DownloadHistoryEntity.toItem() = DownloadHistoryItem(
        id = id,
        filename = filename,
        songtitle = songTitle,
        format = format,
        bytes = bytes,
        artist = artist,
        viewedAt = viewedAt,
    )
}

private fun historyEntityOf(module: Module) = DownloadHistoryEntity(
    id = module.id,
    filename = module.filename,
    songTitle = module.songtitle,
    format = module.format,
    bytes = module.bytes,
    artist = module.artist,
    viewedAt = System.currentTimeMillis(),
)
