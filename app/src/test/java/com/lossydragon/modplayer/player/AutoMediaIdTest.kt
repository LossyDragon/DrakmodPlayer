package com.lossydragon.modplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMediaIdTest {

    private val safUri = "content://com.android.externalstorage.documents/tree/primary%3AMods" +
        "/document/primary%3AMods%2Fa::b.mod"

    @Test
    fun `file ids round trip and are distinguishable`() {
        val id = AutoMediaId.file(safUri)
        assertTrue(AutoMediaId.isFile(id))
        assertFalse(AutoMediaId.isDir(id))
        assertFalse(AutoMediaId.isPlaylistEntry(id))
        assertEquals(safUri, AutoMediaId.uriFromFile(id))
    }

    @Test
    fun `dir ids round trip`() {
        val id = AutoMediaId.dir(safUri)
        assertTrue(AutoMediaId.isDir(id))
        assertEquals(safUri, AutoMediaId.pathFromDir(id))
    }

    @Test
    fun `playlist ids round trip`() {
        val id = AutoMediaId.playlist(42L)
        assertTrue(AutoMediaId.isPlaylist(id))
        assertEquals(42L, AutoMediaId.playlistIdFrom(id))
        assertNull(AutoMediaId.playlistIdFrom("playlist::not-a-number"))
    }

    @Test
    fun `playlist entry ids survive separators inside the uri`() {
        val id = AutoMediaId.playlistEntry(7L, safUri)
        assertTrue(AutoMediaId.isPlaylistEntry(id))
        assertEquals(7L to safUri, AutoMediaId.playlistEntryParts(id))
    }

    @Test
    fun `malformed playlist entry ids are rejected`() {
        assertNull(AutoMediaId.playlistEntryParts("plentry::"))
        assertNull(AutoMediaId.playlistEntryParts("plentry::abc::uri"))
        assertNull(AutoMediaId.playlistEntryParts("plentry::12"))
    }

    @Test
    fun `static ids do not collide with prefixed ids`() {
        val static = listOf(
            AutoMediaId.ROOT,
            AutoMediaId.HOME,
            AutoMediaId.PLAYLISTS,
            AutoMediaId.FILE_BROWSER,
            AutoMediaId.PLAY_ALL,
            AutoMediaId.SHUFFLE_ALL,
            AutoMediaId.PLAYLISTS_EMPTY,
        )
        static.forEach { id ->
            assertFalse(id, AutoMediaId.isFile(id))
            assertFalse(id, AutoMediaId.isDir(id))
            assertFalse(id, AutoMediaId.isPlaylist(id))
            assertFalse(id, AutoMediaId.isPlaylistEntry(id))
        }
        assertEquals(static.size, static.toSet().size)
    }
}
