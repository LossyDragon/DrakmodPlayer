package com.lossydragon.modplayer.player

import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.native.model.AudioStats
import com.lossydragon.native.model.FrameInfo
import com.lossydragon.native.model.ModVars
import kotlinx.coroutines.flow.StateFlow

/**
 * What [ModPlayer] needs from the audio engine. [PlayerEngine] is the real one; tests
 * substitute a fake so the player can run on the JVM without the native library.
 */
interface Engine {
    val frameInfoFlow: StateFlow<FrameInfo>
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val currentSequenceFlow: StateFlow<Int>
    val modVarsFlow: StateFlow<ModVars>
    val audioDisconnectedFlow: StateFlow<Boolean>

    var playAllSequences: Boolean
    val paused: Boolean
    val initialized: Boolean
    val endedNaturally: Boolean

    fun isRepeatingOne(value: Boolean)
    fun setSequence(index: Int): Boolean
    fun loadNext(file: ModuleEntity): Boolean
    fun start()
    fun pause()
    fun resume()
    fun seek(posMs: Int)
    fun stop()
    fun handleAudioDisconnect()
    fun release()
    fun getPatternData(patternIndex: Int): PatternData
    fun getAudioStats(stats: AudioStats)
    fun setDucked(value: Boolean)
}
