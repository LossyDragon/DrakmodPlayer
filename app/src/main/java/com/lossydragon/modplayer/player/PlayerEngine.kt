package com.lossydragon.modplayer.player

import android.content.Context
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.model.ModuleFile
import com.lossydragon.modplayer.player.model.ChannelSnapshot
import com.lossydragon.modplayer.player.model.FrameSnapshot
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ChannelInfo
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModInfo
import org.helllabs.libxmp.model.ModVars
import timber.log.Timber

class PlayerEngine(
    private val context: Context,
    prefs: AppPreferences
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

    @Volatile
    private var paused = false

    @Volatile
    private var initialized = false

    @Volatile
    var endedNaturally: Boolean = false
        private set

    @Volatile
    var stopRequest = false
        private set

    // Xmp Defaults
    @Volatile
    private var sampleRate: Int = Xmp.DEFAULT_SAMPLE_RATE

    @Volatile
    private var bufferMs: Int = Xmp.DEFAULT_BUFFER_MS

    @Volatile
    private var defaultPan: Int = Xmp.DEFAULT_PAN_SEPARATION

    @Volatile
    private var volumeBoost: Int = Xmp.DEFAULT_VOLUME_BOOST

    @Volatile
    private var stereoMix: Int = Xmp.DEFAULT_STEREO_MIX

    @Volatile
    private var dspEffect: Int = Xmp.XMP_DSP_LOWPASS

    @Volatile
    private var playerVolume = Xmp.DEFAULT_PLAYER_VOLUME

    @Volatile
    private var interpolationType = Xmp.DEFAULT_INTERPOLATION

    @Volatile
    private var playerFlags: Int = 0

    init {
        prefs.getSampleRateFlow().distinctUntilChanged().onEach { sampleRate = it }.launchIn(scope)
        prefs.getBufferMsFlow().distinctUntilChanged().onEach { bufferMs = it }.launchIn(scope)
        prefs.getDefaultPanFlow().distinctUntilChanged().onEach {
            defaultPan = it
        }.launchIn(scope)
        prefs.getVolumeBoostFlow().distinctUntilChanged().onEach {
            volumeBoost = it
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_AMP, it)
        }.launchIn(scope)
        prefs.getStereoMixFlow().distinctUntilChanged().onEach {
            stereoMix = it
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_MIX, it)
        }.launchIn(scope)
        prefs.getDspEffectFlow().distinctUntilChanged().onEach {
            dspEffect = it
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_DSP, it)
        }.launchIn(scope)
        prefs.getPlayerVolumeFlow().distinctUntilChanged().onEach {
            playerVolume = it
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_VOLUME, it)
        }.launchIn(scope)
        prefs.getInterpolationTypeFlow().distinctUntilChanged().onEach {
            interpolationType = it
            if (initialized) Xmp.setPlayer(Xmp.XMP_PLAYER_INTERP, it)
        }.launchIn(scope)
        prefs.getPlayerFlagsFlow().distinctUntilChanged().onEach {
            playerFlags = it
            if (initialized) {
                // A500 can update live via CFLAGS
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
        endedNaturally = false
        stopRequest = false
        currentSequence = 0
        currentSequenceFlow.value = 0

        if (initialized) {
            // Shouldn't happen — caller should use loadNext()
            softStop()
            Xmp.releaseModule()
        } else {
            if (!Xmp.init(sampleRate, bufferMs)) {
                Timber.e("Xmp.init() failed")
                return false
            }
            initialized = true
        }

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

        if (Xmp.startPlayer(sampleRate) != 0) {
            Timber.e("Xmp.startPlayer() failed")
            return
        }

        // Unmute all channels
        for (i in 0 until 64) {
            Xmp.mute(i, 0)
        }

        val cflags = Xmp.getPlayer(Xmp.XMP_PLAYER_CFLAGS)
        val newCflags = if ((playerFlags and Xmp.XMP_FLAGS_A500) != 0) {
            cflags or Xmp.XMP_FLAGS_A500
        } else {
            cflags and Xmp.XMP_FLAGS_A500.inv()
        }

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

                frameFlow.value = FrameSnapshot(
                    position = frameInfo.pos,
                    pattern = frameInfo.pattern,
                    row = frameInfo.row,
                    numRows = frameInfo.numRows,
                    speed = frameInfo.speed,
                    bpm = frameInfo.bpm,
                    timeMs = timeMs,
                    totalTimeMs = modVars.seqDuration,
                    channels = Array(numCh) { i ->
                        ChannelSnapshot(
                            volume = channelInfo.volumes[i],
                            finalVol = channelInfo.finalVols[i],
                            pan = channelInfo.pans[i],
                            instrument = channelInfo.instruments[i],
                            note = channelInfo.keys[i],
                            period = channelInfo.periods[i],
                        )
                    }.toImmutableList(),
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
