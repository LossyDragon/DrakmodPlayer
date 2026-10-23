package com.lossydragon.modplayer.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import com.lossydragon.modplayer.db.entity.ModuleEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Drives [ModPlayer] through the Media3 [Player] API against a [FakeEngine], covering
 * the paths that produced real bugs: end-of-module advance, park/restart at the end,
 * seek routing, overlapping loads, unplayable files and audio-route loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModPlayerTest {

    private lateinit var engine: FakeEngine
    private lateinit var prefs: FakeAppPreferences
    private lateinit var player: ModPlayer

    private val files = listOf("a", "b", "c").map { module(it) }

    private fun module(name: String) = ModuleEntity(
        filename = "$name.mod",
        fileExtension = "mod",
        filePath = "content://mods/$name",
        fileSize = 1_000L,
        isValidModule = true,
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        engine = FakeEngine()
        prefs = FakeAppPreferences(context)
        player = ModPlayer(context, engine, prefs)
        idle()
    }

    @After
    fun tearDown() {
        player.releaseEngine()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Polls until [condition] holds, idling the main looper between checks. */
    private fun await(what: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for $what; engine calls = ${engine.calls}")
    }

    private fun awaitLoads(count: Int) = await("$count loads") {
        engine.calls("load:").size >= count
    }

    private fun awaitStarts(count: Int) = await("$count starts") {
        engine.calls("start").size >= count
    }

    private fun loadQueue(startAt: Int = 0, shuffle: Boolean = false) {
        player.loadQueue(files, startAt = startAt, shuffleMode = shuffle)
        awaitStarts(1)
    }

    // --- loading ------------------------------------------------------------------------

    @Test
    fun `loadQueue loads and starts the requested item`() {
        loadQueue(startAt = 1)
        assertEquals(listOf("load:content://mods/b"), engine.calls("load:"))
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(3, player.mediaItemCount)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `loadQueue persists the queue and the playback state`() {
        loadQueue(startAt = 2)
        await("persist") { prefs.savedQueueJson != null && prefs.savedState?.index == 2 }
    }

    @Test
    fun `loaded item gets real metadata from the engine`() {
        engine.modVarsFlow.value = engine.modVarsFlow.value.copy(name = "Space Debris", type = "XM")
        loadQueue()
        await("metadata") { player.currentMediaItem?.mediaMetadata?.title == "Space Debris" }
        assertEquals("XM", player.currentMediaItem?.mediaMetadata?.artist)
    }

    // --- end of module ------------------------------------------------------------------

    @Test
    fun `end of module advances to the next item`() {
        loadQueue()
        engine.endModule()
        awaitLoads(2)
        awaitStarts(2)
        assertEquals("load:content://mods/b", engine.calls("load:")[1])
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `end of last module parks in STATE_ENDED and keeps the queue`() {
        loadQueue(startAt = 2)
        engine.endModule()
        await("ended") { player.playbackState == Player.STATE_ENDED }
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(3, player.mediaItemCount)
        assertEquals(1, engine.calls("load:").size)
    }

    @Test
    fun `play after the queue ended restarts from the first item`() {
        loadQueue(startAt = 2)
        engine.endModule()
        await("ended") { player.playbackState == Player.STATE_ENDED }

        player.play()
        awaitLoads(2)
        awaitStarts(2)
        assertEquals("load:content://mods/a", engine.calls("load:")[1])
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun `repeat all wraps to the first item`() {
        loadQueue(startAt = 2)
        player.repeatMode = Player.REPEAT_MODE_ALL
        engine.endModule()
        awaitLoads(2)
        assertEquals("load:content://mods/a", engine.calls("load:")[1])
    }

    // --- seeking ------------------------------------------------------------------------

    @Test
    fun `seekTo with another media item index jumps to that item at the position`() {
        loadQueue()
        player.seekTo(2, 5_000L)
        awaitLoads(2)
        awaitStarts(2)
        assertEquals("load:content://mods/c", engine.calls("load:")[1])
        assertTrue(engine.calls.contains("seek:5000"))
        assertEquals(2, player.currentMediaItemIndex)
    }

    @Test
    fun `seekToDefaultPosition on another item switches items without seeking`() {
        loadQueue()
        player.seekToDefaultPosition(1)
        awaitLoads(2)
        assertEquals(1, player.currentMediaItemIndex)
        assertTrue(engine.calls("seek:").isEmpty())
    }

    @Test
    fun `seekTo within the current item seeks the engine`() {
        loadQueue()
        player.seekTo(7_500L)
        idle()
        assertEquals(listOf("seek:7500"), engine.calls("seek:"))
        assertEquals(1, engine.calls("load:").size)
    }

    @Test
    fun `seekToNext and seekToPrevious navigate the queue`() {
        loadQueue()
        player.seekToNext()
        awaitLoads(2)
        assertEquals(1, player.currentMediaItemIndex)

        engine.positionMs.value = 1_000L // under the 3 s "restart" threshold
        player.seekToPrevious()
        awaitLoads(3)
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun `previous after three seconds restarts the current item instead`() {
        loadQueue(startAt = 1)
        engine.positionMs.value = 10_000L
        player.seekToPrevious()
        idle()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(listOf("seek:0"), engine.calls("seek:"))
        assertEquals(1, engine.calls("load:").size)
    }

    // --- overlapping requests -----------------------------------------------------------

    @Test
    fun `rapid next requests end on the last requested item and start it only once`() {
        loadQueue()
        engine.loadDelayMs = 150L
        player.seekToNext() // b (slow load in progress)
        player.seekToNext() // c, parked as pending
        idle()

        await("c loaded") { engine.calls.contains("load:content://mods/c") }
        awaitStarts(2)
        assertEquals(2, player.currentMediaItemIndex)
        // b was loaded but never started; c is what plays.
        val expected = listOf("a", "b", "c").map { "load:content://mods/$it" }
        assertEquals(expected, engine.calls("load:"))
        assertEquals(2, engine.calls("start").size)
        val cLoadedAt = engine.calls.indexOf("load:content://mods/c")
        assertTrue(cLoadedAt < engine.calls.lastIndexOf("start"))
    }

    // --- unplayable files ---------------------------------------------------------------

    @Test
    fun `an unplayable item is skipped in the direction of travel`() {
        engine.failPaths = setOf("content://mods/b")
        loadQueue()
        player.seekToNext()
        awaitLoads(3)
        awaitStarts(2)
        assertEquals("load:content://mods/c", engine.calls("load:")[2])
        assertEquals(2, player.currentMediaItemIndex)
    }

    @Test
    fun `a queue with nothing playable is cleared`() {
        engine.failPaths = files.map { it.filePath }.toSet()
        player.loadQueue(files, startAt = 0)
        await("cleared") { player.mediaItemCount == 0 }
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertTrue(engine.calls("start").isEmpty())
    }

    // --- audio route loss ---------------------------------------------------------------

    @Test
    fun `audio disconnect reopens and resumes the current item at the same position`() {
        loadQueue(startAt = 1)
        engine.positionMs.value = 42_000L
        engine.isPlaying.value = false
        engine.audioDisconnectedFlow.value = true

        await("reload") { engine.calls("load:").size == 2 }
        awaitStarts(2)
        assertTrue(engine.calls.contains("handleAudioDisconnect"))
        assertEquals("load:content://mods/b", engine.calls("load:")[1])
        assertTrue(engine.calls.contains("seek:42000"))
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `audio disconnect while paused reloads but does not resume`() {
        loadQueue()
        player.pause()
        idle()
        engine.audioDisconnectedFlow.value = true

        await("reload") { engine.calls("load:").size == 2 }
        Thread.sleep(100)
        idle()
        assertEquals(1, engine.calls("start").size)
        assertFalse(player.playWhenReady)
    }

    // --- modes --------------------------------------------------------------------------

    @Test
    fun `shuffle toggle keeps the current item current and preserves the queue`() {
        loadQueue(startAt = 1)
        player.shuffleModeEnabled = true
        idle()
        assertTrue(player.shuffleModeEnabled)
        assertEquals("content://mods/b", player.currentMediaItem?.mediaId)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(3, player.mediaItemCount)
        assertEquals(1, engine.calls("load:").size)

        player.shuffleModeEnabled = false
        idle()
        assertEquals("content://mods/b", player.currentMediaItem?.mediaId)
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `repeat mode is reflected by the player and the engine`() {
        loadQueue()
        player.repeatMode = Player.REPEAT_MODE_ONE
        idle()
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        assertTrue(engine.repeatOne)

        player.repeatMode = Player.REPEAT_MODE_OFF
        idle()
        assertFalse(engine.repeatOne)
    }

    @Test
    fun `stop clears the queue and the persisted state`() {
        loadQueue()
        player.stop()
        await("cleared") { player.mediaItemCount == 0 && prefs.clearCount > 0 }
        assertNull(prefs.savedQueueJson)
        assertEquals(Player.STATE_IDLE, player.playbackState)
    }
}
