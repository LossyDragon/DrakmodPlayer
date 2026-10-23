package com.lossydragon.modplayer.player

import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.native.model.AudioStats
import com.lossydragon.native.model.FrameInfo
import com.lossydragon.native.model.ModVars
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [Engine]: records every call, can fail specific files, can be slowed down,
 * and lets a test simulate a natural end of module or an audio disconnect.
 */
class FakeEngine : Engine {
    override val frameInfoFlow = MutableStateFlow(FrameInfo())
    override val isPlaying = MutableStateFlow(false)
    override val positionMs = MutableStateFlow(0L)
    override val currentSequenceFlow = MutableStateFlow(0)
    override val modVarsFlow = MutableStateFlow(ModVars())
    override val audioDisconnectedFlow = MutableStateFlow(false)

    override var playAllSequences = false

    @Volatile
    override var paused = false

    @Volatile
    override var initialized = false

    @Volatile
    override var endedNaturally = false

    /** Every engine call, in order, e.g. `load:content://a`, `start`, `seek:5000`. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** File paths whose [loadNext] should fail. */
    @Volatile
    var failPaths: Set<String> = emptySet()

    /** Artificial load time, to provoke overlapping requests. */
    @Volatile
    var loadDelayMs = 0L

    @Volatile
    var repeatOne = false

    @Volatile
    var duckRequested = false

    fun calls(prefix: String) = calls.filter { it.startsWith(prefix) }

    /** Simulates the render loop reaching the end of the module. */
    fun endModule() {
        endedNaturally = true
        isPlaying.value = false
    }

    override fun isRepeatingOne(value: Boolean) {
        repeatOne = value
    }

    override fun setSequence(index: Int): Boolean = false

    override fun loadNext(file: ModuleEntity): Boolean {
        calls += "load:${file.filePath}"
        if (loadDelayMs > 0) Thread.sleep(loadDelayMs)
        isPlaying.value = false
        endedNaturally = false
        paused = false
        initialized = true
        return file.filePath !in failPaths
    }

    override fun start() {
        calls += "start"
        paused = false
        endedNaturally = false
        isPlaying.value = true
    }

    override fun pause() {
        calls += "pause"
        paused = true
        isPlaying.value = false
    }

    override fun resume() {
        calls += "resume"
        paused = false
        isPlaying.value = true
    }

    override fun seek(posMs: Int) {
        calls += "seek:$posMs"
        positionMs.value = posMs.toLong()
    }

    override fun stop() {
        calls += "stop"
        endedNaturally = false
        isPlaying.value = false
    }

    override fun handleAudioDisconnect() {
        calls += "handleAudioDisconnect"
        initialized = false
        audioDisconnectedFlow.value = false
    }

    override fun release() {
        calls += "release"
        initialized = false
    }

    override fun getPatternData(patternIndex: Int) = PatternData()

    override fun getAudioStats(stats: AudioStats) = Unit

    override fun setDucked(value: Boolean) {
        duckRequested = value
    }
}
