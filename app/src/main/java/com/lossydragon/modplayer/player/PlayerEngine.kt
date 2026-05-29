package com.lossydragon.modplayer.player

import android.content.Context
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.model.ModuleFile
import com.lossydragon.modplayer.player.model.ChannelSnapshot
import com.lossydragon.modplayer.player.model.FrameSnapshot
import com.lossydragon.modplayer.player.model.NoteCell
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.modplayer.player.model.emptyPatternData
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ChannelInfo
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModInfo
import org.helllabs.libxmp.model.ModVars
import timber.log.Timber

class PlayerEngine(
    private val context: Context,
    private val prefs: AppPreferences
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var renderThread: Thread? = null

    val frameFlow: StateFlow<FrameSnapshot?>
        field = MutableStateFlow<FrameSnapshot?>(null)

    val isPlaying: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val positionMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    val durationMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    val currentSequenceFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    private val patternCache = mutableMapOf<Int, PatternData>()
    private val modVars = ModVars()
    private val frameInfo = FrameInfo()
    private val channelInfo = ChannelInfo()
    private var currentSequence = 0

    var playAllSequences = false

    val numPatterns: Int
        get() = modVars.numPatterns
    val numChannels: Int
        get() = modVars.numChannels
    val numInstruments: Int
        get() = modVars.numInstruments
    val numSamples: Int
        get() = modVars.numSamples
    val numSequences: Int
        get() = modVars.numSequence

    private var channelSnapshots = Array(64) {
        ChannelSnapshot(0, 0, 0, 0, 0, 0)
    }
    private var channelSnapshotList = channelSnapshots.toImmutableList()
    private var lastNumCh = -1

    @Volatile
    var paused = false
        private set

    @Volatile
    var initialized = false
        private set

    @Volatile
    var endedNaturally: Boolean = false
        private set

    @Volatile
    var stopRequest = false
        private set

    init {
        prefs.getVolumeBoostFlow().distinctUntilChanged().onEach {
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_AMP, it)
        }.launchIn(scope)
        prefs.getStereoMixFlow().distinctUntilChanged().onEach {
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_MIX, it)
        }.launchIn(scope)
        prefs.getDspEffectFlow().distinctUntilChanged().onEach {
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_DSP, it)
        }.launchIn(scope)
        prefs.getPlayerVolumeFlow().distinctUntilChanged().onEach {
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_VOLUME, it)
        }.launchIn(scope)
        prefs.getInterpolationTypeFlow().distinctUntilChanged().onEach {
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_INTERP, it)
        }.launchIn(scope)
        prefs.getPlayerFlagsFlow().distinctUntilChanged().onEach {
            if (initialized) {
                val cflags = Xmp.getPlayer(Xmp.XMP_PLAYER_CFLAGS)
                val newCflags = if ((it and Xmp.XMP_FLAGS_A500) != 0) {
                    cflags or Xmp.XMP_FLAGS_A500
                } else {
                    cflags and Xmp.XMP_FLAGS_A500.inv()
                }
                Xmp.setPlayer(Xmp.XMP_PLAYER_CFLAGS, newCflags)
            }
        }.launchIn(scope)
    }

    /** Switches to sequence [index]. Updates duration and resets position. Returns false if invalid. */
    fun setSequence(index: Int): Boolean {
        val result = Xmp.setSequence(index)
        if (result) {
            Xmp.getModVars(modVars)
            durationMs.value = modVars.seqDuration.toLong()
            positionMs.value = 0L
            currentSequenceFlow.value = index
        }
        return result
    }

    /** Returns duration in ms for each sequence. Call after [load]. */
    fun getSequenceDurations(): List<Int> = Xmp.getSeqVars().asList()

    /** Loads [file] into the engine. Returns false on failure. */
    fun load(file: ModuleFile): Boolean {
        clearPatternCache()

        endedNaturally = false
        stopRequest = false
        currentSequence = 0
        currentSequenceFlow.value = 0

        if (initialized) {
            // Shouldn't happen — caller should use loadNext()
            softStop()
            Xmp.releaseModule()
        } else {
            val sampleRate = runBlocking { prefs.getSampleRateFlow().first() }
            val bufferMs = runBlocking { prefs.getBufferMsFlow().first() }
            val format = runBlocking { prefs.getPlayerFormatFlow().first() }
            val perfMode = runBlocking { prefs.getOboePerfModeFlow().first() }
            val audioApi = runBlocking { prefs.getOboeAudioApiFlow().first() }
            val isMono = format and Xmp.XMP_FORMAT_MONO != 0

            Timber.d(
                "load: sampleRate=$sampleRate bufferMs=$bufferMs format=$format perfMode=$perfMode audioApi=$audioApi"
            )
            val result = Xmp.init(
                rate = sampleRate,
                ms = bufferMs,
                perfMode = perfMode,
                channels = if (isMono) Xmp.OBOE_CHANNELS_MONO else Xmp.OBOE_CHANNELS_STEREO,
                audioApi = audioApi,
                flags = format,
            )

            if (!result) {
                Timber.e("Xmp.init() failed")
                return false
            }
            initialized = true
        }

        val playerFlags = runBlocking { prefs.getPlayerFlagsFlow().first() }
        val defaultPan = runBlocking { prefs.getDefaultPanFlow().first() }
        val preLoadMask = Xmp.XMP_FLAGS_VBLANK or Xmp.XMP_FLAGS_FX9BUG or Xmp.XMP_FLAGS_FIXLOOP
        Xmp.setPlayer(Xmp.XMP_PLAYER_FLAGS, playerFlags and preLoadMask)
        Xmp.setPlayer(Xmp.XMP_PLAYER_DEFPAN, defaultPan)

        val modInfo = ModInfo()
        val result = Xmp.loadFromFd(context, file.uri, modInfo)
        if (result != 0) {
            Timber.e("Xmp.loadFromFd() returned $result")
            Xmp.deinit()
            initialized = false
            return false
        }

        Xmp.getModVars(modVars)

        durationMs.value = modVars.seqDuration.toLong()
        positionMs.value = 0L

        return true
    }

    /** Loads next [file] without tearing down audio. Use between tracks. */
    fun loadNext(file: ModuleFile): Boolean {
        clearPatternCache()

        endedNaturally = false
        stopRequest = false
        currentSequence = 0
        currentSequenceFlow.value = 0

        if (!initialized) {
            // First load — fall back to full init path
            return load(file)
        }

        // Stop render loop without closing Oboe
        softStop()

        Xmp.releaseModule()

        val preLoadMask = Xmp.XMP_FLAGS_VBLANK or Xmp.XMP_FLAGS_FX9BUG or Xmp.XMP_FLAGS_FIXLOOP
        val playerFlags = runBlocking { prefs.getPlayerFlagsFlow().first() }
        val defaultPan = runBlocking { prefs.getDefaultPanFlow().first() }
        Xmp.setPlayer(Xmp.XMP_PLAYER_FLAGS, playerFlags and preLoadMask)
        Xmp.setPlayer(Xmp.XMP_PLAYER_DEFPAN, defaultPan)

        val modInfo = ModInfo()
        val result = Xmp.loadFromFd(context, file.uri, modInfo)
        if (result != 0) {
            Timber.e("Xmp.loadFromFd() returned $result")
            return false
        }

        Xmp.getModVars(modVars)
        durationMs.value = modVars.seqDuration.toLong()
        positionMs.value = 0L
        return true
    }

    /** Starts the render thread and audio stream. */
    fun start() {
        Timber.d("start() called")
        if (!initialized) return
        if (renderThread?.isAlive == true) return

        Xmp.setExpectSilence(false)

        stopRequest = false
        paused = false

        val sampleRate = runBlocking { prefs.getSampleRateFlow().first() }
        val format = runBlocking { prefs.getPlayerFormatFlow().first() }
        if (Xmp.startPlayer(sampleRate, format) != 0) {
            Timber.e("Xmp.startPlayer() failed")
            return
        }

        // Unmute all channels
        for (i in 0 until 64) {
            Xmp.mute(i, 0)
        }

        val playerFlags = runBlocking { prefs.getPlayerFlagsFlow().first() }
        val cflags = Xmp.getPlayer(Xmp.XMP_PLAYER_CFLAGS)
        val newCflags = if ((playerFlags and Xmp.XMP_FLAGS_A500) != 0) {
            cflags or Xmp.XMP_FLAGS_A500
        } else {
            cflags and Xmp.XMP_FLAGS_A500.inv()
        }

        val volumeBoost = runBlocking { prefs.getVolumeBoostFlow().first() }
        val stereoMix = runBlocking { prefs.getStereoMixFlow().first() }
        val dspEffect = runBlocking { prefs.getDspEffectFlow().first() }
        val playerVolume = runBlocking { prefs.getPlayerVolumeFlow().first() }
        val interpolationType = runBlocking { prefs.getInterpolationTypeFlow().first() }

        Xmp.setPlayer(Xmp.XMP_PLAYER_AMP, volumeBoost)
        Xmp.setPlayer(Xmp.XMP_PLAYER_CFLAGS, newCflags)
        Xmp.setPlayer(Xmp.XMP_PLAYER_DSP, dspEffect)
        Xmp.setPlayer(Xmp.XMP_PLAYER_INTERP, interpolationType)
        Xmp.setPlayer(Xmp.XMP_PLAYER_MIX, stereoMix)
        Xmp.setPlayer(Xmp.XMP_PLAYER_VOLUME, playerVolume)

        var prefillCount = 0
        while (Xmp.hasFreeBuffer() && prefillCount++ < 100) {
            if (Xmp.fillBuffer(false) < 0) break
        }

        Xmp.playAudio()
        isPlaying.value = true

        renderThread = Thread(::renderLoop, "ModPlayerThread").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /** Pauses playback — audio stream stopped, position preserved. */
    fun pause() {
        endedNaturally = false
        paused = true
        Xmp.setExpectSilence(true)
        Xmp.dropAudio() // drain
        isPlaying.value = false
    }

    /** Resumes from paused state. No-op if not paused. */
    fun resume() {
        if (!paused) return
        paused = false
        Xmp.setExpectSilence(false)
        // Xmp.dropAudio()
        // Xmp.playAudio()
        isPlaying.value = true
    }

    /** Seeks to [posMs] milliseconds. */
    fun seek(posMs: Int) {
        Timber.d("engine.seek posMs=$posMs")
        Xmp.seek(posMs)
        positionMs.value = posMs.toLong()
    }

    /** Stops playback and releases all native resources. */
    fun stop() {
        endedNaturally = false
        stopRequest = true
        renderThread?.interrupt()
        renderThread?.join(2_000)
        renderThread = null

        if (initialized) {
            Xmp.setExpectSilence(true)
            Xmp.dropAudio()
            Thread.sleep(60) // drain
            Xmp.endPlayer()
            Xmp.releaseModule()
            Xmp.deinit()
            initialized = false
        }

        scope.cancel()

        isPlaying.value = false
        positionMs.value = 0L
        frameFlow.value = null
    }

    /** Stops render loop + libxmp player, but keeps Oboe stream and context alive. */
    private fun softStop() {
        stopRequest = true
        renderThread?.interrupt()
        renderThread?.join(2_000)
        renderThread = null
        Xmp.endPlayer()
        isPlaying.value = false
        positionMs.value = 0L
        frameFlow.value = null
    }

    fun getPatternData(patternIndex: Int): PatternData =
        patternCache.getOrPut(patternIndex) {
            val numChannels = modVars.numChannels
            val numRows = Xmp.getPatternRows(patternIndex)
            if (numChannels == 0 || numRows == 0) {
                return@getOrPut emptyPatternData()
            }

            val notes = ByteArray(64)
            val ins = ByteArray(64)
            val fxt = ByteArray(64)
            val fxp = ByteArray(64)

            val cells = Array(numRows) { row ->
                Xmp.getPatternRow(patternIndex, row, notes, ins, fxt, fxp)
                Array(numChannels) { ch ->
                    NoteCell(
                        note = notes[ch].toInt() and 0xFF,
                        instrument = ins[ch].toInt() and 0xFF,
                        fxType = fxt[ch].toInt(), // signed, -1 = empty
                        fxParam = fxp[ch].toInt() and 0xFF,
                    )
                }.toList().toImmutableList()
            }.toList().toImmutableList()

            PatternData(
                cells = cells,
                numChannels = numChannels,
                numRows = numRows,
                patternIndex = patternIndex,
            )
        }

    private fun clearPatternCache() = patternCache.clear()

    private fun renderLoop() {
        while (!stopRequest) {
            try {
                // Fast-path: skip JNI calls entirely while paused
                if (paused) {
                    Thread.sleep(50)
                    continue
                }

                while (!Xmp.hasFreeBuffer() && !paused && !stopRequest) {
                    Thread.sleep(40)
                }

                if (stopRequest) break

                val endReached = Xmp.fillBuffer(false) < 0

                Xmp.getInfo(frameInfo)
                Xmp.getChannelData(channelInfo)

                val timeMs = Xmp.time()
                val numCh = modVars.numChannels.coerceIn(0, 64)

                // Only reallocate the list when channel count changes
                if (numCh != lastNumCh) {
                    channelSnapshots = Array(numCh) { ChannelSnapshot(0, 0, 0, 0, 0, 0) }
                    lastNumCh = numCh
                }

                // Mutate snapshots in place
                for (i in 0 until numCh) {
                    channelSnapshots[i] = ChannelSnapshot(
                        volume = channelInfo.volumes[i],
                        finalVol = channelInfo.finalVols[i],
                        pan = channelInfo.pans[i],
                        instrument = channelInfo.instruments[i],
                        note = channelInfo.keys[i],
                        period = channelInfo.periods[i],
                    )
                }
                channelSnapshotList = channelSnapshots.toImmutableList()

                frameFlow.value = FrameSnapshot(
                    position = frameInfo.pos,
                    pattern = frameInfo.pattern,
                    row = frameInfo.row,
                    numRows = frameInfo.numRows,
                    speed = frameInfo.speed,
                    bpm = frameInfo.bpm,
                    timeMs = timeMs,
                    totalTimeMs = modVars.seqDuration,
                    channels = channelSnapshotList,
                )

                positionMs.value = timeMs.toLong()

                if (endReached) {
                    Timber.i(
                        "renderLoop: endReached, playAllSequences=$playAllSequences seq=$currentSequence/$numSequences"
                    )
                    if (playAllSequences) {
                        currentSequence++
                        if (setSequence(currentSequence)) {
                            // keep playing — don't set endedNaturally
                            Timber.i("renderLoop: advanced to seq $currentSequence")
                            continue
                        }
                    }
                    // Crappy haaaaack! (Drain remaining buffers)
                    while (Xmp.hasFreeBuffer() && !stopRequest) {
                        if (Xmp.fillBuffer(false) < 0) break
                    }
                    endedNaturally = true
                    isPlaying.value = false
                    Timber.i("renderLoop: ended naturally, exiting loop")
                    // Xmp.stopAudio()
                    Xmp.setExpectSilence(true)
                    break
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
