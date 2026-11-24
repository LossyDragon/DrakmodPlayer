package com.lossydragon.modplayer.player

import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.renderer.model.AudioStats
import com.lossydragon.renderer.model.FrameInfo
import com.lossydragon.renderer.model.ModVars
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Things the engine reports on its own, without a command having asked for them. */
sealed interface EngineEvent {
    /** The loaded module (and, with sub-songs on, every sequence) reached its end. */
    data object Ended : EngineEvent

    /** The audio route died (headphones/BT gone). The engine is no longer loaded. */
    data object Disconnected : EngineEvent

    /** A command failed in a way the user should hear about. */
    data class Error(val message: String) : EngineEvent
}

enum class LoadResult {
    OK,

    /** The file could not be opened or is not a module. */
    UNPLAYABLE,

    /** The audio stream could not be opened; says nothing about the file. */
    NO_AUDIO
}

/**
 * What [ModPlayer] needs from the audio engine. [PlayerEngine] is the real one; tests
 * substitute a fake so the player can run on the JVM without the native library.
 *
 * Threading contract: every command (the functions after [post]) runs on the engine's
 * single thread, reached through [post]; the caller never invokes them directly from
 * another thread. Flows and the read-only queries may be used from any thread.
 */
interface Engine {
    val frameInfoFlow: StateFlow<FrameInfo>

    /** True while audio is being rendered (loaded, started and not paused). */
    val playingFlow: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val currentSequenceFlow: StateFlow<Int>
    val modVarsFlow: StateFlow<ModVars>
    val events: SharedFlow<EngineEvent>

    var playAllSequences: Boolean

    /** Runs [block] on the engine thread, after everything posted before it. */
    fun post(block: () -> Unit)

    /** Loads [file], opening the audio stream if needed. */
    fun load(file: ModuleEntity): LoadResult

    /** Starts or resumes rendering. False if nothing is loaded or the stream is dead. */
    fun play(): Boolean

    fun pause()
    fun seek(posMs: Int)

    /** Unloads the module but keeps the stream open for the next [load]. */
    fun stop()

    /** Cleans up after [EngineEvent.Disconnected]; the next [load] opens a fresh stream. */
    fun recoverFromDisconnect()

    /** Full teardown of module and stream. */
    fun release()

    fun setSequence(index: Int): Boolean
    fun setRepeatOne(value: Boolean)

    /** Thread-safe; lowers the output while another app holds transient focus. */
    fun setDucked(value: Boolean)

    // Read-only, any thread.
    fun getPatternData(patternIndex: Int): PatternData
    fun getAudioStats(stats: AudioStats)
}
