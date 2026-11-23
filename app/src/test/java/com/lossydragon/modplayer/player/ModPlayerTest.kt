package com.lossydragon.modplayer.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lossydragon.modplayer.db.entity.ModuleEntity
import kotlinx.coroutines.runBlocking
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
 * seek routing, overlapping loads, pause racing a load, unplayable files, stop vs clear,
 * queue edits and audio-route loss.
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
        prefs = FakeAppPreferences(RuntimeEnvironment.getApplication())
        player = newPlayer()
        idle()
    }

    /** A player on a fresh [engine], sharing [prefs]. */
    private fun newPlayer(): ModPlayer {
        engine = FakeEngine()
        return ModPlayer(RuntimeEnvironment.getApplication(), engine, prefs)
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

    /** Runs engine and main-thread work to completion, following hand-offs between the two. */
    private fun settle() = repeat(8) {
        engine.drain()
        idle()
    }

    private fun awaitEnded() = await("ended") { player.playbackState == Player.STATE_ENDED }

    private fun awaitLoads(count: Int) = await("$count loads") {
        engine.calls("load:").size >= count
    }

    private fun awaitPlays(count: Int) = await("$count plays") {
        engine.calls("play").size >= count
    }

    private fun loadQueue(startAt: Int = 0, shuffle: Boolean = false) {
        player.loadQueue(files, startAt = startAt, shuffleMode = shuffle)
        awaitPlays(1)
    }

    private fun loadPath(i: Int) = engine.calls("load:")[i]

    // --- loading ------------------------------------------------------------------------

    @Test
    fun `loadQueue loads and starts the requested item`() {
        loadQueue(startAt = 1)
        assertEquals(listOf("load:content://mods/b"), engine.calls("load:"))
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(3, player.mediaItemCount)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertTrue(player.isPlaying)
    }

    @Test
    fun `loading reports BUFFERING with playWhenReady already true`() {
        engine.loadDelayMs = 200L
        player.loadQueue(files, startAt = 0)
        idle()
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.playWhenReady)
        awaitPlays(1)
        assertEquals(Player.STATE_READY, player.playbackState)
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

    @Test
    fun `media ids use the Auto file scheme`() {
        loadQueue()
        assertEquals(AutoMediaId.file("content://mods/a"), player.currentMediaItem?.mediaId)
    }

    // --- end of module ------------------------------------------------------------------

    @Test
    fun `end of module advances to the next item`() {
        loadQueue()
        engine.endModule()
        awaitLoads(2)
        awaitPlays(2)
        assertEquals("load:content://mods/b", loadPath(1))
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `end of last module parks in STATE_ENDED and keeps the queue`() {
        loadQueue(startAt = 2)
        engine.endModule()
        awaitEnded()
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(3, player.mediaItemCount)
        assertEquals(1, engine.calls("load:").size)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `play after the queue ended restarts from the first item`() {
        loadQueue(startAt = 2)
        engine.endModule()
        awaitEnded()

        player.play()
        awaitLoads(2)
        awaitPlays(2)
        assertEquals("load:content://mods/a", loadPath(1))
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun `seeking inside the ended item reloads it instead of jumping to the first`() {
        loadQueue(startAt = 2)
        engine.endModule()
        awaitEnded()

        player.seekTo(0L)
        await("reload") { engine.calls("load:").size == 2 }
        assertEquals("load:content://mods/c", loadPath(1))
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(Player.STATE_READY, player.playbackState)

        player.play()
        awaitPlays(2)
        assertEquals(2, player.currentMediaItemIndex)
    }

    @Test
    fun `repeat all wraps to the first item`() {
        loadQueue(startAt = 2)
        player.repeatMode = Player.REPEAT_MODE_ALL
        engine.endModule()
        awaitLoads(2)
        assertEquals("load:content://mods/a", loadPath(1))
    }

    // --- seeking ------------------------------------------------------------------------

    @Test
    fun `seekTo with another media item index jumps to that item at the position`() {
        loadQueue()
        player.seekTo(2, 5_000L)
        awaitLoads(2)
        await("seek") { engine.calls.contains("seek:5000") }
        assertEquals("load:content://mods/c", loadPath(1))
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
        await("seek") { engine.calls("seek:").isNotEmpty() }
        assertEquals(listOf("seek:7500"), engine.calls("seek:"))
        assertEquals(1, engine.calls("load:").size)
    }

    @Test
    fun `seek position is reported until the engine applies it`() {
        loadQueue()
        engine.positionMs.value = 1_000L
        player.seekTo(7_500L)
        assertEquals(7_500L, player.currentPosition)
        await("seek") { engine.calls("seek:").isNotEmpty() }
        settle()
        assertEquals(7_500L, player.currentPosition)
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
        await("seek") { engine.calls("seek:").isNotEmpty() }
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(listOf("seek:0"), engine.calls("seek:"))
        assertEquals(1, engine.calls("load:").size)
    }

    @Test
    fun `previous on the first item with repeat off restarts it`() {
        loadQueue()
        engine.positionMs.value = 1_000L
        player.seekToPrevious()
        await("seek") { engine.calls("seek:").isNotEmpty() }
        assertEquals(listOf("seek:0"), engine.calls("seek:"))
        assertEquals(1, engine.calls("load:").size)
    }

    // --- overlapping requests -----------------------------------------------------------

    @Test
    fun `rapid next requests end on the last requested item and start it only once`() {
        loadQueue()
        engine.loadDelayMs = 150L
        player.seekToNext() // b (slow load in progress)
        player.seekToNext() // c, queued behind it
        idle()

        await("c loaded") { engine.calls.contains("load:content://mods/c") }
        awaitPlays(2)
        settle()
        assertEquals(2, player.currentMediaItemIndex)
        // b was loaded but never started; c is what plays.
        val expected = listOf("a", "b", "c").map { "load:content://mods/$it" }
        assertEquals(expected, engine.calls("load:"))
        assertEquals(2, engine.calls("play").size)
        assertTrue(engine.calls.indexOf("load:content://mods/c") < engine.calls.lastIndexOf("play"))
    }

    @Test
    fun `pause during a load is honoured when the load finishes`() {
        loadQueue()
        engine.loadDelayMs = 150L
        player.seekToNext()
        idle()
        player.pause()
        awaitLoads(2)
        await("ready") { player.playbackState == Player.STATE_READY }
        settle()
        assertEquals(1, engine.calls("play").size)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun `play pressed during a load starts it when the load finishes`() {
        engine.loadDelayMs = 150L
        player.loadQueue(files, startAt = 0)
        idle()
        player.pause()
        idle()
        player.play()
        awaitPlays(1)
        assertTrue(player.playWhenReady)
    }

    // --- unplayable files ---------------------------------------------------------------

    @Test
    fun `an unplayable item is skipped in the direction of travel`() {
        engine.failPaths = setOf("content://mods/b")
        loadQueue()
        player.seekToNext()
        awaitLoads(3)
        awaitPlays(2)
        assertEquals("load:content://mods/c", loadPath(2))
        assertEquals(2, player.currentMediaItemIndex)
    }

    @Test
    fun `a queue with nothing playable is cleared`() {
        engine.failPaths = files.map { it.filePath }.toSet()
        player.loadQueue(files, startAt = 0)
        await("cleared") { player.mediaItemCount == 0 }
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertTrue(engine.calls("play").isEmpty())
    }

    @Test
    fun `an unplayable last item parks and keeps the queue`() {
        engine.failPaths = setOf("content://mods/c")
        loadQueue()
        player.seekTo(2, 0L)
        awaitEnded()
        settle()
        assertEquals(3, player.mediaItemCount)
        assertEquals(0, prefs.clearCount)
    }

    @Test
    fun `a stream that cannot be opened keeps the queue and reports an error`() {
        engine.noAudio = true
        player.loadQueue(files, startAt = 0)
        await("error") { player.playerError != null }
        settle()
        assertEquals(1, engine.calls("load:").size)
        assertEquals(3, player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertEquals(0, prefs.clearCount)
    }

    @Test
    fun `a stream that will not start gives up after a retry`() {
        engine.failStart = true
        player.loadQueue(files, startAt = 0)
        await("error") { player.playerError != null }
        settle()
        val loads = engine.calls("load:").size
        assertTrue("loads=$loads", loads <= 3)
        settle()
        assertEquals(loads, engine.calls("load:").size)
        assertFalse(player.playWhenReady)
        assertEquals(3, player.mediaItemCount)
    }

    @Test
    fun `a start error from a superseded item does not cancel the new load`() {
        loadQueue()
        engine.loadDelayMs = 150L
        player.seekToNext()
        idle()
        engine.events.tryEmit(EngineEvent.Error("stale"))
        awaitPlays(2)
        assertNull(player.playerError)
        assertEquals(1, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `a start error on the loaded item goes idle with an error and keeps the queue`() {
        loadQueue()
        engine.events.tryEmit(EngineEvent.Error("boom"))
        await("error") { player.playerError != null }
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertEquals(3, player.mediaItemCount)
    }

    // --- pause / service teardown -------------------------------------------------------

    @Test
    fun `pause right after a posted start still pauses the engine`() {
        loadQueue()
        player.pause()
        await("paused") { engine.calls("pause").size == 1 }
        engine.post { Thread.sleep(200) } // holds the start below in the engine queue
        player.play()
        idle()
        player.pause()
        await("second pause") { engine.calls("pause").size == 2 }
        settle()
        assertFalse(engine.playingFlow.value)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `play after the engine was released resumes at the paused position`() {
        loadQueue()
        player.pause()
        await("paused") { engine.calls("pause").size == 1 }
        engine.positionMs.value = 150_000L
        player.releaseEngine()
        await("release") { engine.calls.contains("release") }
        assertEquals(150_000L, player.currentPosition)

        player.play()
        awaitLoads(2)
        await("seek") { engine.calls.contains("seek:150000") }
    }

    // --- audio route loss ---------------------------------------------------------------

    @Test
    fun `audio disconnect reopens and resumes the current item at the same position`() {
        loadQueue(startAt = 1)
        engine.positionMs.value = 42_000L
        engine.disconnect()

        await("reload") { engine.calls("load:").size == 2 }
        await("seek") { engine.calls.contains("seek:42000") }
        assertTrue(engine.calls.contains("recover"))
        assertEquals("load:content://mods/b", loadPath(1))
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `audio disconnect while paused waits for play, then resumes at the old position`() {
        loadQueue()
        player.pause()
        idle()
        engine.positionMs.value = 30_000L
        engine.disconnect()
        await("recover") { engine.calls.contains("recover") }
        settle()
        assertEquals(1, engine.calls("load:").size)
        assertFalse(player.playWhenReady)
        assertEquals(30_000L, player.currentPosition)

        player.play()
        await("reload") { engine.calls("load:").size == 2 }
        await("seek") { engine.calls.contains("seek:30000") }
    }

    // --- stop vs clear ------------------------------------------------------------------

    @Test
    fun `stop keeps the queue and goes idle`() {
        loadQueue(startAt = 1)
        player.stop()
        await("stop") { engine.calls.contains("stop") }
        assertEquals(3, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        await("persist") { prefs.savedQueueJson != null }
        assertEquals(0, prefs.clearCount)
    }

    @Test
    fun `play after stop reloads the current item`() {
        loadQueue(startAt = 1)
        player.stop()
        await("stop") { engine.calls.contains("stop") }
        player.play()
        awaitLoads(2)
        awaitPlays(2)
        assertEquals("load:content://mods/b", loadPath(1))
    }

    @Test
    fun `clearMediaItems drops the queue and the persisted state`() {
        loadQueue()
        player.clearMediaItems()
        await("cleared") { player.mediaItemCount == 0 && prefs.clearCount > 0 }
        assertNull(prefs.savedQueueJson)
        assertEquals(Player.STATE_IDLE, player.playbackState)
    }

    // --- queue edits --------------------------------------------------------------------

    @Test
    fun `removing a later item keeps playing the current one`() {
        loadQueue(startAt = 0)
        player.removeMediaItem(2)
        idle()
        assertEquals(2, player.mediaItemCount)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(1, engine.calls("load:").size)
    }

    @Test
    fun `removing the playing item moves on to the next`() {
        loadQueue(startAt = 1)
        player.removeMediaItem(1)
        awaitLoads(2)
        assertEquals("load:content://mods/c", loadPath(1))
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(2, player.mediaItemCount)
    }

    @Test
    fun `queue edits keep the loaded item's real metadata`() {
        engine.modVarsFlow.value = engine.modVarsFlow.value.copy(name = "Space Debris", type = "XM")
        loadQueue(startAt = 1)
        await("metadata") { player.currentMediaItem?.mediaMetadata?.title == "Space Debris" }

        player.addMediaItem(MediaItem.fromUri("content://mods/x"))
        idle()
        assertEquals("Space Debris", player.currentMediaItem?.mediaMetadata?.title)

        player.moveMediaItem(1, 0)
        idle()
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals("Space Debris", player.currentMediaItem?.mediaMetadata?.title)

        player.removeMediaItem(3)
        idle()
        assertEquals("Space Debris", player.currentMediaItem?.mediaMetadata?.title)

        player.seekToNext()
        awaitLoads(2)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(3, player.mediaItemCount)
    }

    @Test
    fun `media items carrying a module keep its fields through setMediaItems`() {
        val entity = module("z").copy(fileSize = 4_242L, moduleName = "Zed", moduleType = "IT")
        val item = MediaItem.Builder()
            .setUri(entity.uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setExtras(MediaItemExtras.of(entity))
                    .build()
            )
            .build()
        player.setMediaItems(listOf(item))
        awaitLoads(1)
        assertEquals(listOf(entity), player.queueFlow.value)
    }

    @Test
    fun `moving items keeps the current item current`() {
        loadQueue(startAt = 0)
        player.moveMediaItem(0, 2)
        idle()
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(AutoMediaId.file("content://mods/a"), player.currentMediaItem?.mediaId)
        assertEquals(1, engine.calls("load:").size)
    }

    @Test
    fun `replacing every item keeps the new items`() {
        loadQueue()
        player.replaceMediaItems(0, 3, listOf(MediaItem.fromUri("content://mods/x")))
        awaitLoads(2)
        assertEquals("load:content://mods/x", loadPath(1))
        assertEquals(1, player.mediaItemCount)
        assertEquals(0, prefs.clearCount)
    }

    // --- modes --------------------------------------------------------------------------

    @Test
    fun `shuffle toggle keeps the current item current and preserves the queue`() {
        loadQueue(startAt = 1)
        player.shuffleModeEnabled = true
        idle()
        assertTrue(player.shuffleModeEnabled)
        assertEquals(AutoMediaId.file("content://mods/b"), player.currentMediaItem?.mediaId)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(3, player.mediaItemCount)
        assertEquals(1, engine.calls("load:").size)

        player.shuffleModeEnabled = false
        idle()
        assertEquals(AutoMediaId.file("content://mods/b"), player.currentMediaItem?.mediaId)
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `repeat mode is reflected by the player and the engine`() {
        loadQueue()
        player.repeatMode = Player.REPEAT_MODE_ONE
        await("repeat one") { engine.repeatOneRequested }
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)

        player.repeatMode = Player.REPEAT_MODE_OFF
        await("repeat off") { !engine.repeatOneRequested }
    }

    // --- restore ------------------------------------------------------------------------

    @Test
    fun `restore without auto resume shows the queue but does not touch the engine`() {
        loadQueue(startAt = 1)
        await("persist") { prefs.savedQueueJson != null }
        player.releaseEngine()
        idle()

        val fresh = newPlayer()
        val restored = runBlocking { fresh.restoreQueue() }
        idle()
        assertTrue(restored)
        assertEquals(3, fresh.mediaItemCount)
        assertEquals(1, fresh.currentMediaItemIndex)
        assertEquals(Player.STATE_IDLE, fresh.playbackState)
        settle()
        assertTrue(engine.calls("load:").isEmpty())

        fresh.play()
        awaitLoads(1)
        awaitPlays(1)
        assertEquals("load:content://mods/b", loadPath(0))
        fresh.releaseEngine()
    }
}
