package com.lossydragon.modplayer.player

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.room.Room
import com.lossydragon.modplayer.db.AppDatabase
import com.lossydragon.modplayer.db.PlaybackState
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntryEntity
import com.lossydragon.renderer.model.Sequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What Android Auto asks the session to play: browse-tree taps and voice requests, resolved
 * against a real (in-memory) database. Also the session buttons Auto draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoBrowseCallbackTest {

    private val root = "content://mods/root"

    private lateinit var db: AppDatabase
    private lateinit var prefs: FakeAppPreferences
    private lateinit var engine: FakeEngine
    private lateinit var player: ModPlayer
    private lateinit var scope: CoroutineScope
    private lateinit var callback: AutoBrowseCallback

    private fun module(name: String, title: String = "", parent: String = root) = ModuleEntity(
        filename = "$name.mod",
        fileExtension = "mod",
        filePath = "$parent/$name.mod",
        parentPath = parent,
        fileSize = 1_000L,
        isValidModule = true,
        moduleName = title,
    )

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = FakeAppPreferences(app)
        engine = FakeEngine()
        player = ModPlayer(app, engine, prefs)
        scope = CoroutineScope(Dispatchers.Unconfined)
        callback = AutoBrowseCallback(
            app,
            player,
            prefs,
            db.playlistDao(),
            db.moduleMetadataDao(),
            scope
        )
        runBlocking {
            db.moduleMetadataDao().upsertAll(
                listOf(
                    module("a", title = "Space Debris"),
                    module("b", title = "Debris Field"),
                    module("c", title = "Stardust Memories"),
                    module("d", title = "Elsewhere", parent = "$root/sub"),
                )
            )
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        player.releaseEngine()
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    private fun resolve(item: MediaItem): MediaItemsWithStartPosition =
        runBlocking { callback.resolvePlayRequest(item) }

    private fun voice(query: String?, extras: Bundle? = null): MediaItem = MediaItem.Builder()
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()
        )
        .build()

    private fun tap(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun MediaItemsWithStartPosition.paths() =
        mediaItems.map { it.localConfiguration?.uri.toString() }

    private fun addPlaylist(name: String, vararg uris: String): Long = runBlocking {
        db.playlistDao().createPlaylistWithEntries(
            PlaylistEntity(name = name),
            uris.mapIndexed { i, uri -> PlaylistEntryEntity(0, i, uri, "Entry $i", "mod") },
        )
    }

    // --- voice ----------------------------------------------------------------------------

    @Test
    fun `voice query queues the title matches, closest first`() {
        val result = resolve(voice("debris"))
        assertEquals(listOf("$root/b.mod", "$root/a.mod"), result.paths())
        assertEquals(0, result.startIndex)
        assertEquals("Debris Field", result.mediaItems[0].mediaMetadata.title)
    }

    @Test
    fun `voice query naming a playlist queues that playlist`() {
        addPlaylist("Road Trip", "$root/c.mod", "$root/a.mod")
        val result = resolve(voice("road trip"))
        assertEquals(listOf("$root/c.mod", "$root/a.mod"), result.paths())
    }

    @Test
    fun `voice query falls back to the assistant's parsed title`() {
        val extras = Bundle().apply { putString(MediaStore.EXTRA_MEDIA_TITLE, "Stardust") }
        val result = resolve(voice("stardust by nobody", extras))
        assertEquals(listOf("$root/c.mod"), result.paths())
    }

    @Test
    fun `voice query with no match plays nothing`() {
        try {
            resolve(voice("no such tune"))
            fail("expected the request to be rejected")
        } catch (_: NoSuchElementException) {
        }
    }

    @Test
    fun `play music with no saved queue shuffles the library`() {
        val result = resolve(voice(null))
        assertEquals(4, result.mediaItems.size)
        assertEquals(4, result.paths().toSet().size)
    }

    @Test
    fun `play music resumes the saved queue`() {
        prefs.savedQueueJson = Json.encodeToString(listOf(module("a"), module("b"), module("c")))
        prefs.savedState = PlaybackState(2, 4_000L, 0, false)
        val result = resolve(voice(""))
        assertEquals(listOf("$root/a.mod", "$root/b.mod", "$root/c.mod"), result.paths())
        assertEquals(2, result.startIndex)
        assertEquals(4_000L, result.startPositionMs)
    }

    // --- taps -----------------------------------------------------------------------------

    @Test
    fun `tapping a file queues its folder from that file`() {
        val result = resolve(tap(AutoMediaId.file("$root/b.mod")))
        assertEquals(listOf("$root/a.mod", "$root/b.mod", "$root/c.mod"), result.paths())
        assertEquals(1, result.startIndex)
    }

    @Test
    fun `a file outside the library plays alone, named by its playlist entry`() {
        val uri = "content://elsewhere/document/primary%3AMusic%2Ftune.xm"
        addPlaylist("Mixed", uri)
        val result = resolve(tap(AutoMediaId.file(uri)))
        assertEquals(listOf(uri), result.paths())
        assertEquals("Entry 0", result.mediaItems[0].mediaMetadata.title)
    }

    @Test
    fun `a file known only by its uri plays alone, named from the uri`() {
        val uri = "content://elsewhere/document/primary%3AMusic%2Ftune.xm"
        val result = resolve(tap(AutoMediaId.file(uri)))
        assertEquals(listOf(uri), result.paths())
        assertEquals("tune.xm", result.mediaItems[0].mediaMetadata.title)
    }

    @Test
    fun `tapping a playlist entry queues the playlist from that entry`() {
        val id = addPlaylist("Road Trip", "$root/c.mod", "$root/a.mod")
        val result = resolve(tap(AutoMediaId.playlistEntry(id, "$root/a.mod")))
        assertEquals(listOf("$root/c.mod", "$root/a.mod"), result.paths())
        assertEquals(1, result.startIndex)
    }

    @Test
    fun `play all on an empty library plays nothing`() {
        runBlocking {
            val dao = db.moduleMetadataDao()
            dao.deleteByFilePaths(dao.getAllFilePaths())
        }
        try {
            resolve(tap(AutoMediaId.PLAY_ALL))
            fail("expected the request to be rejected")
        } catch (_: NoSuchElementException) {
        }
    }

    // --- session buttons ------------------------------------------------------------------

    private fun press(action: String): Boolean {
        val handled = SessionButtons.handle(SessionCommand(action, Bundle.EMPTY), player)
        shadowOf(Looper.getMainLooper()).idle()
        return handled
    }

    @Test
    fun `shuffle and repeat buttons step through the modes`() {
        assertTrue(press("com.lossydragon.modplayer.SHUFFLE"))
        assertTrue(player.shuffleModeEnabled)
        assertTrue(press("com.lossydragon.modplayer.SHUFFLE"))
        assertFalse(player.shuffleModeEnabled)

        val seen = (1..3).map {
            press("com.lossydragon.modplayer.REPEAT")
            player.repeatMode
        }
        assertEquals(
            listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF),
            seen
        )
        assertFalse(press("com.example.OTHER"))
    }

    @Test
    fun `next subsong wraps and does nothing for a single sequence`() {
        press("com.lossydragon.modplayer.NEXT_SUBSONG")
        engine.drain()
        assertTrue(engine.calls("sequence:").isEmpty())

        engine.modVarsFlow.value =
            engine.modVarsFlow.value.copy(seqData = arrayOf(Sequence(), Sequence(), Sequence()))
        engine.currentSequenceFlow.value = 2
        press("com.lossydragon.modplayer.NEXT_SUBSONG")
        engine.drain()
        assertEquals(listOf("sequence:0"), engine.calls("sequence:"))
    }

    @Test
    fun `buttons show the current modes and the subsong button only when useful`() {
        val app = RuntimeEnvironment.getApplication()
        val plain = SessionButtons.build(app, false, Player.REPEAT_MODE_OFF, hasSubsongs = false)
        assertEquals(listOf("Shuffle Off", "Repeat Off"), plain.map { it.displayName.toString() })

        val full = SessionButtons.build(app, true, Player.REPEAT_MODE_ONE, hasSubsongs = true)
        assertEquals(
            listOf("Shuffle On", "Repeating One", "Next subsong"),
            full.map { it.displayName.toString() }
        )
    }
}
