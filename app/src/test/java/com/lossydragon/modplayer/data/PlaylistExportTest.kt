package com.lossydragon.modplayer.data

import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntryEntity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistExportTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val playlists = listOf(
        PlaylistEntity(id = 1L, name = "Chip", comment = "small ones", createdAt = 1_000L),
        PlaylistEntity(id = 2L, name = "Empty", createdAt = 2_000L),
    )

    private val entries = mapOf(
        1L to listOf(
            PlaylistEntryEntity(1L, 0, "content://a/1.mod", "one", "mod"),
            PlaylistEntryEntity(1L, 1, "content://a/2.xm", "two", "xm"),
        ),
    )

    @Test
    fun `export round trips through the import model`() {
        val exported = exportPlaylistsToJson(playlists, entries)
        val decoded = json.decodeFromString<PlaylistExport>(exported)

        assertEquals(1, decoded.version)
        assertEquals(2, decoded.playlists.size)

        val chip = decoded.playlists[0]
        assertEquals("Chip", chip.name)
        assertEquals("small ones", chip.comment)
        assertEquals(1_000L, chip.createdAt)
        assertEquals(listOf(0, 1), chip.entries.map { it.position })
        assertEquals(listOf("content://a/1.mod", "content://a/2.xm"), chip.entries.map { it.uri })

        assertTrue(decoded.playlists[1].entries.isEmpty())
    }

    @Test
    fun `export is pretty printed and carries a timestamp`() {
        val json = exportPlaylistsToJson(playlists, entries)
        assertTrue(json.contains("\n"))
        assertTrue(json.contains("\"exportedAt\""))
    }
}
