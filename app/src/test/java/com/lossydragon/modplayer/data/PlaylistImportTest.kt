package com.lossydragon.modplayer.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistImportTest {

    private fun entry(uri: String, position: Int = 0) =
        PlaylistEntryExport(position = position, uri = uri, name = uri, extension = "mod")

    private val export = PlaylistExport(
        playlists = listOf(
            PlaylistExportItem(
                "Same",
                "",
                1L,
                listOf(entry("content://a/1"), entry("content://a/2", 1))
            ),
            PlaylistExportItem("Same", "", 2L, listOf(entry("content://b/1"))),
            PlaylistExportItem("Empty", "", 3L, emptyList()),
        )
    )

    @Test
    fun `playlists with the same name stay separate`() {
        val batch = export.toRoomEntities()

        assertEquals(3, batch.playlists.size)
        assertEquals(listOf("Same", "Same", "Empty"), batch.playlists.map { it.playlist.name })
        assertEquals(2, batch.playlists[0].entries.size)
        assertEquals(1, batch.playlists[1].entries.size)
        assertTrue(batch.playlists[2].entries.isEmpty())
        assertEquals(
            ImportResult(playlistsImported = 3, entriesImported = 3, skipped = 0),
            batch.result
        )
    }

    @Test
    fun `inaccessible entries are skipped and counted`() {
        val batch = export.toRoomEntities { it != "content://a/2" }

        assertEquals(listOf("content://a/1"), batch.playlists[0].entries.map { it.uri })
        assertEquals(
            ImportResult(playlistsImported = 3, entriesImported = 2, skipped = 1),
            batch.result
        )
    }

    @Test
    fun `export json parses back`() {
        val parsed = parsePlaylistExport(
            """{"version":1,"exportedAt":"x","playlists":[{"name":"P","comment":"","createdAt":5,"entries":[]}]}"""
        )
        assertEquals("P", parsed.playlists.single().name)
    }
}
