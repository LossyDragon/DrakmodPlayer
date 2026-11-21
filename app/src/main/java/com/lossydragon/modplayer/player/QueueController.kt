package com.lossydragon.modplayer.player

import kotlin.random.Random

enum class RepeatMode { OFF, ONE, ALL }

/** What the player should do after a queue transition. The caller applies every step. */
sealed interface QueueStep {
    /** Load and play the item now at [index]. */
    data class Play(val index: Int) : QueueStep

    /** Seek the current item back to its start. */
    data object Restart : QueueStep

    /** End of queue reached with repeat off: stay on the current item, stopped. */
    data object Park : QueueStep

    /** Nothing playable is left; the caller should [QueueController.clear]. */
    data object Clear : QueueStep

    /** Nothing to do (empty queue, index out of range). */
    data object None : QueueStep
}

/**
 * Pure queue logic for [ModPlayer]: ordering, shuffle, repeat, navigation, editing and
 * unplayable-item skipping. Knows nothing about Media3 or the engine so it can be
 * unit-tested; every mutation returns the [QueueStep] the player should carry out.
 *
 * [originalItems] is the order items were added in; [items] is the playback order
 * (identical unless [shuffle] is on). Persisted indices refer to [originalItems].
 */
class QueueController<T>(private val random: Random = Random.Default) {

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    var shuffle: Boolean = false
        private set

    private val original = mutableListOf<T>()

    /** Playback order as indices into [original]. */
    private val order = mutableListOf<Int>()

    /** [order] resolved to items; rebuilt on every mutation. */
    private val ordered = mutableListOf<T>()

    val originalItems: List<T> get() = original
    val items: List<T> get() = ordered
    val size: Int get() = order.size
    val isEmpty: Boolean get() = order.isEmpty()

    var currentIndex: Int = 0
        private set

    val current: T? get() = ordered.getOrNull(currentIndex)

    /** Index of [current] within [originalItems]; what gets persisted. */
    val currentOriginalIndex: Int get() = order.getOrNull(currentIndex) ?: 0

    /** Original index of the item at playback [index], or -1. */
    fun originalIndexAt(index: Int): Int = order.getOrElse(index) { -1 }

    /** +1 after forward navigation, -1 after backward; decides which way a failed load skips. */
    private var lastDirection = 1

    /** Consecutive failed loads; reset by [onLoadSucceeded]. */
    private var skipAttempts = 0

    /**
     * Replaces the queue with [files], starting at [startAt] (an index into [files]).
     * With shuffle on, that item goes first and the rest are shuffled behind it.
     */
    fun load(
        files: List<T>,
        startAt: Int,
        shuffle: Boolean = this.shuffle,
        repeatMode: RepeatMode = this.repeatMode
    ): QueueStep {
        this.shuffle = shuffle
        this.repeatMode = repeatMode
        skipAttempts = 0
        lastDirection = 1
        original.clear()
        original.addAll(files)
        applyOrder(startAt)
        return if (order.isEmpty()) QueueStep.None else QueueStep.Play(currentIndex)
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    /** Turns shuffle on or off, keeping the current item current. */
    fun setShuffle(enabled: Boolean) {
        shuffle = enabled
        if (order.isEmpty()) return
        applyOrder(currentOriginalIndex)
    }

    fun clear() {
        original.clear()
        order.clear()
        ordered.clear()
        currentIndex = 0
        skipAttempts = 0
    }

    /** Jumps straight to [index] in playback order. */
    fun jumpTo(index: Int): QueueStep {
        if (index !in order.indices) return QueueStep.None
        lastDirection = if (index >= currentIndex) 1 else -1
        return play(index)
    }

    /** Advances forward according to [repeatMode]; parks at the end with repeat off. */
    fun next(): QueueStep {
        if (order.isEmpty()) return QueueStep.None
        lastDirection = 1
        return when {
            repeatMode == RepeatMode.ONE -> play(currentIndex)
            currentIndex + 1 < order.size -> play(currentIndex + 1)
            repeatMode == RepeatMode.ALL -> play(0)
            else -> QueueStep.Park
        }
    }

    /** Steps backward according to [repeatMode]; restarts the first item when at the start. */
    fun previous(): QueueStep {
        if (order.isEmpty()) return QueueStep.None
        lastDirection = -1
        return when {
            repeatMode == RepeatMode.ONE -> play(currentIndex)
            currentIndex > 0 -> play(currentIndex - 1)
            repeatMode == RepeatMode.ALL -> play(order.lastIndex)
            else -> QueueStep.Restart
        }
    }

    fun onLoadSucceeded() {
        skipAttempts = 0
    }

    /**
     * The current item failed to load: skip it in the direction of travel, never
     * landing back on it. Parks when there is nowhere to skip to; gives up
     * ([QueueStep.Clear]) once every item has failed in a row.
     */
    fun onLoadFailed(): QueueStep {
        if (order.isEmpty()) return QueueStep.None
        skipAttempts++
        if (skipAttempts >= order.size) return QueueStep.Clear
        return when {
            lastDirection < 0 && currentIndex > 0 -> play(currentIndex - 1)
            currentIndex + 1 < order.size -> play(currentIndex + 1)
            repeatMode == RepeatMode.ALL -> play(0)
            else -> QueueStep.Park
        }
    }

    /** Inserts [files] at playback position [index], keeping the current item current. */
    fun add(index: Int, files: List<T>) {
        if (files.isEmpty()) return
        val firstOriginal = original.size
        original.addAll(files)
        val at = index.coerceIn(0, order.size)
        val wasEmpty = order.isEmpty()
        order.addAll(at, files.indices.map { firstOriginal + it })
        if (!wasEmpty && at <= currentIndex) currentIndex += files.size
        rebuild()
    }

    /**
     * Removes playback positions [from, to). Returns [QueueStep.Clear] when nothing is
     * left, [QueueStep.Play] when the current item was removed (the caller decides
     * whether to actually load it), [QueueStep.None] otherwise.
     */
    fun remove(from: Int, to: Int): QueueStep {
        val start = from.coerceIn(0, order.size)
        val end = to.coerceIn(start, order.size)
        if (start == end) return QueueStep.None

        val removedOriginal = order.subList(start, end).toHashSet()
        val currentOriginal = order.getOrNull(currentIndex)
        order.subList(start, end).clear()

        // Compact `original` and remap the surviving indices.
        val remap = IntArray(original.size)
        var kept = 0
        for (i in original.indices) remap[i] = if (i in removedOriginal) -1 else kept++
        val survivors = original.filterIndexed { i, _ -> i !in removedOriginal }
        original.clear()
        original.addAll(survivors)
        for (k in order.indices) order[k] = remap[order[k]]

        if (order.isEmpty()) {
            clear()
            return QueueStep.Clear
        }
        if (currentOriginal != null && currentOriginal !in removedOriginal) {
            currentIndex = order.indexOf(remap[currentOriginal])
            rebuild()
            return QueueStep.None
        }
        currentIndex = start.coerceAtMost(order.lastIndex)
        rebuild()
        return QueueStep.Play(currentIndex)
    }

    /** Moves playback positions [from, to) so the first lands at [newIndex] (Media3 semantics). */
    fun move(from: Int, to: Int, newIndex: Int) {
        val start = from.coerceIn(0, order.size)
        val end = to.coerceIn(start, order.size)
        if (start == end) return
        val currentOriginal = order[currentIndex]
        val moving = order.subList(start, end).toList()
        order.subList(start, end).clear()
        order.addAll(newIndex.coerceIn(0, order.size), moving)
        currentIndex = order.indexOf(currentOriginal)
        rebuild()
    }

    private fun play(index: Int): QueueStep {
        currentIndex = index
        return QueueStep.Play(index)
    }

    private fun applyOrder(startAt: Int) {
        order.clear()
        if (original.isEmpty()) {
            currentIndex = 0
            rebuild()
            return
        }
        val indices = original.indices.toMutableList()
        if (shuffle) {
            val first = indices.removeAt(startAt.coerceIn(0, indices.lastIndex))
            indices.shuffle(random)
            order.add(first)
            order.addAll(indices)
            currentIndex = 0
        } else {
            order.addAll(indices)
            currentIndex = startAt.coerceIn(0, order.lastIndex)
        }
        rebuild()
    }

    private fun rebuild() {
        ordered.clear()
        for (i in order) ordered.add(original[i])
    }
}
