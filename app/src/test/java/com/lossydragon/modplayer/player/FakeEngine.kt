package com.lossydragon.modplayer.player

import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.native.model.AudioStats
import com.lossydragon.native.model.FrameInfo
import com.lossydragon.native.model.ModVars
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [Engine] with its own single thread, like the real one: records every call,
 * can fail specific files, can be slowed down, and lets a test raise engine events.
 */
class FakeEngine : Engine {
    override val frameInfoFlow = MutableStateFlow(FrameInfo())
    override val playingFlow = MutableStateFlow(false)
    override val positionMs = MutableStateFlow(0L)
    override val currentSequenceFlow = MutableStateFlow(0)
    override val modVarsFlow = MutableStateFlow(ModVars())
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)

    @Volatile
    override var playAllSequences = false

    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "FakeEngine").apply { isDaemon = true }
    }

    /** Every engine command, in order, e.g. `load:content://a`, `play`, `seek:5000`. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** File paths whose [load] should fail. */
    @Volatile
    var failPaths: Set<String> = emptySet()

    /** The audio stream cannot be opened: every [load] reports [LoadResult.NO_AUDIO]. */
    @Volatile
    var noAudio = false

    /** The stream opens but will not start: [play] reports a disconnect. */
    @Volatile
    var failStart = false

    /** Artificial load time, to provoke overlapping requests. */
    @Volatile
    var loadDelayMs = 0L

    @Volatile
    var loaded = false

    @Volatile
    var repeatOneRequested = false

    @Volatile
    var duckRequested = false

    fun calls(prefix: String) = synchronized(calls) { calls.filter { it.startsWith(prefix) } }

    /** Simulates the render loop reaching the end of the module. */
    fun endModule() {
        playingFlow.value = false
        events.tryEmit(EngineEvent.Ended)
    }

    /** Simulates the audio route dying. */
    fun disconnect() {
        playingFlow.value = false
        events.tryEmit(EngineEvent.Disconnected)
    }

    override fun post(block: () -> Unit) {
        executor.execute(block)
    }

    /** Blocks until everything posted so far has run. */
    fun drain() {
        executor.submit {}.get()
    }

    override fun load(file: ModuleEntity): LoadResult {
        calls += "load:${file.filePath}"
        if (loadDelayMs > 0) Thread.sleep(loadDelayMs)
        playingFlow.value = false
        if (noAudio) {
            loaded = false
            return LoadResult.NO_AUDIO
        }
        loaded = file.filePath !in failPaths
        return if (loaded) LoadResult.OK else LoadResult.UNPLAYABLE
    }

    override fun play(): Boolean {
        if (!loaded) return false
        if (failStart) {
            events.tryEmit(EngineEvent.Disconnected)
            return false
        }
        calls += "play"
        playingFlow.value = true
        return true
    }

    override fun pause() {
        calls += "pause"
        playingFlow.value = false
    }

    override fun seek(posMs: Int) {
        calls += "seek:$posMs"
        positionMs.value = posMs.toLong()
    }

    override fun stop() = unload("stop")

    override fun recoverFromDisconnect() = unload("recover")

    override fun release() = unload("release")

    private fun unload(call: String) {
        calls += call
        loaded = false
        playingFlow.value = false
    }

    override fun setSequence(index: Int): Boolean {
        calls += "sequence:$index"
        currentSequenceFlow.value = index
        return true
    }

    override fun setRepeatOne(value: Boolean) {
        repeatOneRequested = value
    }

    override fun setDucked(value: Boolean) {
        duckRequested = value
    }

    override fun getPatternData(patternIndex: Int) = PatternData()

    override fun getAudioStats(stats: AudioStats) = Unit
}
