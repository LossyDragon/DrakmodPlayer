package com.lossydragon.modplayer.player

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueueControllerTest {

    private val abc = listOf("a", "b", "c")

    private fun loaded(
        files: List<String> = abc,
        startAt: Int = 0,
        shuffle: Boolean = false,
        repeat: RepeatMode = RepeatMode.OFF,
        seed: Int = 1
    ) = QueueController<String>(Random(seed)).also { it.load(files, startAt, shuffle, repeat) }

    // --- load ---------------------------------------------------------------------------

    @Test
    fun `load keeps order and starts at requested index`() {
        val q = loaded(startAt = 1)
        assertEquals(abc, q.items)
        assertEquals(1, q.currentIndex)
        assertEquals("b", q.current)
    }

    @Test
    fun `load clamps out of range start index`() {
        assertEquals(2, loaded(startAt = 99).currentIndex)
        assertEquals(0, loaded(startAt = -5).currentIndex)
    }

    @Test
    fun `load of empty list yields None and empty queue`() {
        val q = QueueController<String>()
        assertEquals(QueueStep.None, q.load(emptyList(), 0))
        assertTrue(q.isEmpty)
        assertNull(q.current)
    }

    @Test
    fun `load returns Play for the start index`() {
        val q = QueueController<String>()
        assertEquals(QueueStep.Play(2), q.load(abc, 2))
    }

    // --- shuffle ------------------------------------------------------------------------

    @Test
    fun `shuffled load puts the chosen item first and keeps every item exactly once`() {
        val files = (1..50).map { "t$it" }
        val q = loaded(files, startAt = 7, shuffle = true)
        assertEquals("t8", q.items.first())
        assertEquals(0, q.currentIndex)
        assertEquals(files.toSet(), q.items.toSet())
        assertEquals(files.size, q.items.size)
        assertEquals(files, q.originalItems)
    }

    @Test
    fun `shuffle is deterministic for a given seed`() {
        val files = (1..20).map { "t$it" }
        assertEquals(
            loaded(files, 0, shuffle = true, seed = 42).items,
            loaded(files, 0, shuffle = true, seed = 42).items,
        )
    }

    @Test
    fun `toggling shuffle on keeps the current item current`() {
        val q = loaded(startAt = 1)
        q.setShuffle(true)
        assertTrue(q.shuffle)
        assertEquals("b", q.current)
        assertEquals(0, q.currentIndex)
        assertEquals(abc.toSet(), q.items.toSet())
    }

    @Test
    fun `toggling shuffle off restores original order at the current item`() {
        val q = loaded((1..10).map { "t$it" }, startAt = 4, shuffle = true)
        val playing = q.current
        q.setShuffle(false)
        assertEquals(q.originalItems, q.items)
        assertEquals(playing, q.current)
        assertEquals(4, q.currentIndex)
    }

    @Test
    fun `currentOriginalIndex maps back to the loaded order under shuffle`() {
        val files = (1..10).map { "t$it" }
        val q = loaded(files, startAt = 3, shuffle = true)
        q.next()
        assertEquals(files.indexOf(q.current), q.currentOriginalIndex)
    }

    @Test
    fun `duplicate items keep distinct original indices`() {
        val q = loaded(listOf("x", "x", "y"), startAt = 1, shuffle = true)
        assertEquals(1, q.currentOriginalIndex)
        assertEquals(setOf(0, 1, 2), (0..2).map { q.originalIndexAt(it) }.toSet())
    }

    @Test
    fun `setShuffle on an empty queue is harmless`() {
        val q = QueueController<String>()
        q.setShuffle(true)
        assertTrue(q.shuffle)
        assertTrue(q.isEmpty)
    }

    // --- next / previous ----------------------------------------------------------------

    @Test
    fun `next walks forward then parks with repeat off`() {
        val q = loaded()
        assertEquals(QueueStep.Play(1), q.next())
        assertEquals(QueueStep.Play(2), q.next())
        assertEquals(QueueStep.Park, q.next())
        assertEquals(2, q.currentIndex)
        assertFalse(q.isEmpty)
    }

    @Test
    fun `next wraps with repeat all`() {
        val q = loaded(startAt = 2, repeat = RepeatMode.ALL)
        assertEquals(QueueStep.Play(0), q.next())
    }

    @Test
    fun `next replays the same item with repeat one`() {
        val q = loaded(startAt = 1, repeat = RepeatMode.ONE)
        assertEquals(QueueStep.Play(1), q.next())
        assertEquals(QueueStep.Play(1), q.next())
    }

    @Test
    fun `previous walks back then restarts the first item`() {
        val q = loaded(startAt = 1)
        assertEquals(QueueStep.Play(0), q.previous())
        assertEquals(QueueStep.Restart, q.previous())
        assertEquals(0, q.currentIndex)
    }

    @Test
    fun `previous wraps to the end with repeat all`() {
        val q = loaded(startAt = 0, repeat = RepeatMode.ALL)
        assertEquals(QueueStep.Play(2), q.previous())
    }

    @Test
    fun `next and previous on an empty queue are None`() {
        val q = QueueController<String>()
        assertEquals(QueueStep.None, q.next())
        assertEquals(QueueStep.None, q.previous())
    }

    // --- jumpTo -------------------------------------------------------------------------

    @Test
    fun `jumpTo plays the index and rejects out of range`() {
        val q = loaded()
        assertEquals(QueueStep.Play(2), q.jumpTo(2))
        assertEquals(2, q.currentIndex)
        assertEquals(QueueStep.None, q.jumpTo(3))
        assertEquals(QueueStep.None, q.jumpTo(-1))
        assertEquals(2, q.currentIndex)
    }

    // --- unplayable items ---------------------------------------------------------------

    @Test
    fun `a failed load skips forward when travelling forward`() {
        val q = loaded()
        q.next() // now at b, direction forward
        assertEquals(QueueStep.Play(2), q.onLoadFailed())
    }

    @Test
    fun `a failed load skips backward when travelling backward`() {
        val q = loaded(startAt = 2)
        q.previous() // now at b, direction backward
        assertEquals(QueueStep.Play(0), q.onLoadFailed())
    }

    @Test
    fun `a failed first item while travelling backward skips forward instead`() {
        val q = loaded(startAt = 1)
        q.previous() // now at a
        assertEquals(QueueStep.Play(1), q.onLoadFailed())
    }

    @Test
    fun `a failed last item parks and keeps the queue`() {
        val q = loaded()
        q.jumpTo(2)
        assertEquals(QueueStep.Park, q.onLoadFailed())
        assertEquals(3, q.size)
    }

    @Test
    fun `a failed item never replays itself under repeat one`() {
        val q = loaded(startAt = 1, repeat = RepeatMode.ONE)
        assertEquals(QueueStep.Play(2), q.onLoadFailed())
    }

    @Test
    fun `every item failing in a row clears the queue`() {
        val q = loaded(repeat = RepeatMode.ALL) // ALL so it never parks
        assertEquals(QueueStep.Play(1), q.onLoadFailed())
        assertEquals(QueueStep.Play(2), q.onLoadFailed())
        assertEquals(QueueStep.Clear, q.onLoadFailed())
    }

    @Test
    fun `a successful load resets the failure counter`() {
        val q = loaded(repeat = RepeatMode.ALL)
        q.onLoadFailed()
        q.onLoadFailed()
        q.onLoadSucceeded()
        // Two more failures must not reach the size-3 give-up threshold.
        assertEquals(QueueStep.Play(0), q.onLoadFailed())
        assertEquals(QueueStep.Play(1), q.onLoadFailed())
        assertFalse(q.isEmpty)
    }

    @Test
    fun `failures spread across a session do not accumulate`() {
        val q = loaded((1..6).map { "t$it" })
        repeat(4) {
            q.onLoadFailed()
            q.onLoadSucceeded()
        }
        // Without the reset the fifth failure would have hit the give-up threshold.
        assertEquals(QueueStep.Play(5), q.onLoadFailed())
        assertFalse(q.isEmpty)
    }

    @Test
    fun `a single unplayable item clears immediately`() {
        val q = loaded(listOf("only"))
        assertEquals(QueueStep.Clear, q.onLoadFailed())
    }

    // --- editing ------------------------------------------------------------------------

    @Test
    fun `add appends to the original order and inserts in playback order`() {
        val q = loaded(startAt = 1)
        q.add(0, listOf("x"))
        assertEquals(listOf("x", "a", "b", "c"), q.items)
        assertEquals(listOf("a", "b", "c", "x"), q.originalItems)
        assertEquals("b", q.current)
        assertEquals(2, q.currentIndex)
    }

    @Test
    fun `add to an empty queue starts at the first item`() {
        val q = QueueController<String>()
        q.add(0, listOf("x", "y"))
        assertEquals(0, q.currentIndex)
        assertEquals("x", q.current)
    }

    @Test
    fun `remove before the current item shifts the index`() {
        val q = loaded(startAt = 2)
        assertEquals(QueueStep.None, q.remove(0, 1))
        assertEquals(listOf("b", "c"), q.items)
        assertEquals(listOf("b", "c"), q.originalItems)
        assertEquals(1, q.currentIndex)
        assertEquals("c", q.current)
    }

    @Test
    fun `remove of the current item plays what takes its place`() {
        val q = loaded(startAt = 1)
        assertEquals(QueueStep.Play(1), q.remove(1, 2))
        assertEquals("c", q.current)
    }

    @Test
    fun `remove of the current last item plays the new last item`() {
        val q = loaded(startAt = 2)
        assertEquals(QueueStep.Play(1), q.remove(2, 3))
        assertEquals("b", q.current)
    }

    @Test
    fun `remove of everything clears`() {
        val q = loaded()
        assertEquals(QueueStep.Clear, q.remove(0, 3))
        assertTrue(q.isEmpty)
    }

    @Test
    fun `remove under shuffle keeps original indices consistent`() {
        val files = (1..8).map { "t$it" }
        val q = loaded(files, startAt = 0, shuffle = true)
        val current = q.current
        q.remove(3, 5)
        assertEquals(current, q.current)
        assertEquals(q.items.toSet(), q.originalItems.toSet())
        for (i in q.items.indices) assertEquals(q.items[i], q.originalItems[q.originalIndexAt(i)])
    }

    @Test
    fun `move keeps the current item current`() {
        val q = loaded(startAt = 0)
        q.move(0, 1, 2)
        assertEquals(listOf("b", "c", "a"), q.items)
        assertEquals("a", q.current)
        assertEquals(2, q.currentIndex)
        assertEquals(abc, q.originalItems)
    }

    // --- clear --------------------------------------------------------------------------

    @Test
    fun `clear empties everything and resets the index`() {
        val q = loaded(startAt = 2)
        q.clear()
        assertTrue(q.isEmpty)
        assertEquals(0, q.currentIndex)
        assertEquals(0, q.currentOriginalIndex)
        assertTrue(q.originalItems.isEmpty())
    }

    @Test
    fun `load after clear starts fresh`() {
        val q = loaded()
        q.clear()
        assertEquals(QueueStep.Play(0), q.load(listOf("x", "y"), 0))
        assertEquals(listOf("x", "y"), q.items)
    }
}
