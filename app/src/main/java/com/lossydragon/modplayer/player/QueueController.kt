package com.lossydragon.modplayer.player

import kotlin.random.Random

enum class RepeatMode { OFF, ONE, ALL }

/** What the player should do after a queue transition. */
sealed interface QueueStep {
    /** Load and play the item now at [index]. */
    data class Play(val index: Int) : QueueStep

    /** End of queue reached with repeat off: stay on the current item, stopped. */
    data object Park : QueueStep

    /** Nothing playable is left; the queue has been emptied. */
    data object Clear : QueueStep

    /** Nothing to do (empty queue, index out of range). */
    data object None : QueueStep
}

/**
 * Pure queue logic for [ModPlayer]: ordering, shuffle, repeat, navigation and
 * unplayable-item skipping. Knows nothing about Media3 or the engine so it can be
 * unit-tested; every mutation returns the [QueueStep] the player should carry out.
 *
 * [original] is the order the queue was loaded in; [items] is the playback order
 * (identical unless [shuffle] is on). Persisted indices refer to [original].
 */
class QueueController<T>(private val random: Random = Random.Default) {

    var repeatMode: RepeatMode = RepeatMode.OFF

    var shuffle: Boolean = false
        private set

    private val original = mutableListOf<T>()
    private val order = mutableListOf<T>()

    val originalItems: List<T> get() = original
    val items: List<T> get() = order
    val size: Int get() = order.size
    val isEmpty: Boolean get() = order.isEmpty()

    var currentIndex: Int = 0
        private set

    val current: T? get() = order.getOrNull(currentIndex)

    /** Index of [current] within [originalItems]; what gets persisted. */
    val currentOriginalIndex: Int
        get() = current?.let { original.indexOf(it) }?.coerceAtLeast(0) ?: 0

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

    /** Turns shuffle on or off, keeping the current item current. */
    fun setShuffle(enabled: Boolean) {
        shuffle = enabled
        if (order.isEmpty()) return
        applyOrder(currentOriginalIndex)
    }

    fun clear() {
        original.clear()
        order.clear()
        currentIndex = 0
        skipAttempts = 0
    }

    /** Jumps straight to [index] in playback order. */
    fun jumpTo(index: Int): QueueStep {
        if (index !in order.indices) return QueueStep.None
        lastDirection = if (index >= currentIndex) 1 else -1
        currentIndex = index
        return QueueStep.Play(index)
    }

    /**
     * Advances forward according to [repeatMode]. At the end with repeat off the
     * queue [QueueStep.Park]s — unless this is the unplayable-item path
     * ([fromFailure]), where parking on a dead item would leave a stuck player.
     */
    fun next(fromFailure: Boolean = false): QueueStep {
        if (order.isEmpty()) return QueueStep.None
        lastDirection = 1
        return when {
            repeatMode == RepeatMode.ONE -> play(currentIndex)
            currentIndex + 1 < order.size -> play(currentIndex + 1)
            repeatMode == RepeatMode.ALL -> play(0)
            fromFailure -> QueueStep.Clear.also { clear() }
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
            else -> play(0)
        }
    }

    fun onLoadSucceeded() {
        skipAttempts = 0
    }

    /**
     * The current item failed to load: skip it in the direction of travel. Gives up
     * ([QueueStep.Clear]) once every item has failed in a row.
     */
    fun onLoadFailed(): QueueStep {
        if (order.isEmpty()) return QueueStep.None
        skipAttempts++
        if (skipAttempts >= order.size) {
            clear()
            return QueueStep.Clear
        }
        return if (lastDirection < 0) previous() else next(fromFailure = true)
    }

    private fun play(index: Int): QueueStep {
        currentIndex = index
        return QueueStep.Play(index)
    }

    private fun applyOrder(startAt: Int) {
        order.clear()
        if (original.isEmpty()) {
            currentIndex = 0
            return
        }
        if (shuffle) {
            val rest = original.toMutableList()
            val first = rest.removeAt(startAt.coerceIn(0, rest.lastIndex))
            rest.shuffle(random)
            order.add(first)
            order.addAll(rest)
            currentIndex = 0
        } else {
            order.addAll(original)
            currentIndex = startAt.coerceIn(0, order.lastIndex)
        }
    }
}
