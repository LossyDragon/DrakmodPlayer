package com.lossydragon.modplayer.data

import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntryEntity
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class PlaylistExport(
    val version: Int = 1,
    val exportedAt: String = Instant.now().toString(),
    val playlists: List<PlaylistExportItem>
)

@Serializable
data class PlaylistExportItem(
    val name: String,
    val comment: String,
    val createdAt: Long,
    val entries: List<PlaylistEntryExport>
)

@Serializable
data class PlaylistEntryExport(
    val position: Int,
    val uri: String,
    val name: String,
    val extension: String
)

data class ImportResult(
    val playlistsImported: Int,
    val entriesImported: Int,
    val skipped: Int // entries whose URIs are no longer accessible
)

/** One playlist to insert with its (not yet renumbered) entries; `playlistId` is unset. */
data class ImportedPlaylist(
    val playlist: PlaylistEntity,
    val entries: List<PlaylistEntryEntity>
)

data class ImportBatch(
    val playlists: List<ImportedPlaylist>,
    val result: ImportResult
)

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Serializes [playlists] and their [entries] to a JSON string.
 * @param playlists List of [PlaylistEntity] to export.
 * @param entries Map of playlistId to list of [PlaylistEntryEntity].
 */
fun exportPlaylistsToJson(
    playlists: List<PlaylistEntity>,
    entries: Map<Long, List<PlaylistEntryEntity>>
): String {
    val export = PlaylistExport(
        playlists = playlists.map { playlist ->
            PlaylistExportItem(
                name = playlist.name,
                comment = playlist.comment,
                createdAt = playlist.createdAt,
                entries = (entries[playlist.id] ?: emptyList()).map { entry ->
                    PlaylistEntryExport(
                        position = entry.position,
                        uri = entry.uri,
                        name = entry.name,
                        extension = entry.extension,
                    )
                },
            )
        }
    )
    return json.encodeToString(export)
}

fun parsePlaylistExport(jsonString: String): PlaylistExport = json.decodeFromString(jsonString)

/**
 * Converts a [PlaylistExport] into entities ready to insert, in file order (duplicate names
 * stay separate). Entries failing [isAccessible] are skipped; pass null to import everything.
 */
fun PlaylistExport.toRoomEntities(isAccessible: ((String) -> Boolean)? = null): ImportBatch {
    var totalEntries = 0
    var skipped = 0

    val imported = playlists.map { importedPlaylist ->
        val playlist = PlaylistEntity(
            name = importedPlaylist.name,
            comment = importedPlaylist.comment,
            createdAt = importedPlaylist.createdAt,
        )
        val validEntries = importedPlaylist.entries.mapNotNull { entry ->
            totalEntries++
            if (isAccessible != null && !isAccessible(entry.uri)) {
                Timber.w("Skipping inaccessible URI: ${entry.uri}")
                skipped++
                return@mapNotNull null
            }
            PlaylistEntryEntity(
                playlistId = 0L,
                position = entry.position,
                uri = entry.uri,
                name = entry.name,
                extension = entry.extension,
            )
        }
        ImportedPlaylist(playlist, validEntries)
    }

    return ImportBatch(
        playlists = imported,
        result = ImportResult(
            playlistsImported = imported.size,
            entriesImported = totalEntries - skipped,
            skipped = skipped,
        )
    )
}
