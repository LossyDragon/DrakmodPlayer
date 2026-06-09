package com.lossydragon.modplayer.player

import android.content.Context
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.model.NoteCell
import com.lossydragon.modplayer.player.model.PatternData
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
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModInfo
import org.helllabs.libxmp.model.ModVars
import timber.log.Timber

/**
 * Low-level audio playback engine that wraps the libxmp / Oboe JNI layer.
 */
class PlayerEngine(
    private val context: Context,
    private val prefs: AppPreferences
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The dedicated thread running [renderLoop]. Null when stopped. */
    private var renderThread: Thread? = null

    /** Per-frame playback snapshot emitted by the render loop; null when idle. */
    val frameInfoFlow: StateFlow<FrameInfo>
        field = MutableStateFlow<FrameInfo>(FrameInfo())

    /** True while the Oboe stream is actively rendering audio. */
    val isPlaying: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Current playback position in milliseconds, updated every render frame. */
    val positionMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    /** Index of the currently playing sequence within the loaded module. */
    val currentSequenceFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    /** Module metadata, emitted once after each successful load. */
    val modVarsFlow: StateFlow<ModVars>
        field = MutableStateFlow(ModVars())

    /** Lazily populated by [load] / [loadNext]; read by [renderLoop]. */
    private val patternCache = mutableMapOf<Int, PatternData>()

    /** When true, the render loop advances through all module sequences automatically. */
    @Volatile
    var playAllSequences = false

    /** True while playback is paused (stream is open but not rendering). */
    @Volatile
    var paused = false
        private set

    /** True after [load] or [loadNext] completes successfully. */
    @Volatile
    var initialized = false
        private set

    /** True after the render loop exits because the module reached its end. */
    @Volatile
    var endedNaturally: Boolean = false
        private set

    /** Set to true to signal the render thread to exit cleanly. */
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

    /**
     * Switches to sequence [index] within the loaded module.
     * Updates [positionMs] and [currentSequenceFlow] on success.
     *
     * @return true if the sequence index is valid and the switch succeeded.
     */
    fun setSequence(index: Int): Boolean {
        val result = Xmp.setSequence(index)
        if (result) {
            val mv = ModVars()
            Xmp.getModVars(mv)
            modVarsFlow.value = mv
            positionMs.value = 0L
            currentSequenceFlow.value = index
        }
        return result
    }

    /**
     * Full initialisation path: reads audio settings from prefs, inits the Oboe stream,
     * then loads [file].
     *
     * Use [loadNext] for queue transitions so the stream stays open between tracks.
     *
     * @return true on success; false if Oboe or libxmp init fails.
     */
    fun load(file: ModuleEntity): Boolean {
        if (initialized) {
            softStop()
            clearPatternCache()
            Xmp.releaseModule()
        } else {
            clearPatternCache()
            if (!initAudio()) return false
        }
        resetSequenceState()

        applyPreLoadSettings()

        return loadModule(file)
    }

    /**
     * Fast-path for queue transitions: skips Oboe re-init (stream stays open),
     * stops the render loop, releases the current module, then loads [file].
     *
     * Falls back to [load] if the engine has not been initialised yet.
     *
     * @return true on success; false if libxmp cannot load the file.
     */
    fun loadNext(file: ModuleEntity): Boolean {
        if (!initialized) return load(file)

        softStop()
        clearPatternCache()
        resetSequenceState()
        Xmp.releaseModule()
        applyPreLoadSettings()

        return loadModule(file)
    }

    /**
     * Starts the render loop and audio output.
     * Applies all current preference values to libxmp before playback begins.
     * Pre-fills the Oboe buffer to minimise start latency.
     */
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

        // Unmute all channels on every start.
        for (i in 0 until 64) Xmp.mute(i, 0)

        applyRuntimeSettings()
        prefillBuffer()

        Xmp.playAudio()
        isPlaying.value = true

        renderThread = Thread(::renderLoop, "ModPlayerThread").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /**
     * Pauses playback: drains the Oboe buffer and signals [isPlaying] false.
     * The stream stays open so [resume] can restart without re-loading.
     */
    fun pause() {
        endedNaturally = false
        paused = true
        Xmp.setExpectSilence(true)
        Xmp.dropAudio()
        isPlaying.value = false
    }

    /**
     * Resumes paused playback without reloading or seeking.
     */
    fun resume() {
        if (!paused) return
        paused = false
        Xmp.setExpectSilence(false)
        isPlaying.value = true
    }

    /**
     * Seeks to [posMs] milliseconds within the current module.
     * Updates [positionMs] immediately so the UI reflects the target position.
     */
    fun seek(posMs: Int) {
        Timber.d("engine.seek posMs=$posMs")
        Xmp.seek(posMs)
        positionMs.value = posMs.toLong()
    }

    /**
     * Full teardown: stops the render thread, closes the Oboe stream, releases
     * the module, and cancels the preference observer scope.
     * After this call the engine must be re-initialized via [load] before use.
     */
    fun stop() {
        endedNaturally = false
        stopRequest = true
        renderThread?.interrupt()
        renderThread?.join(2_000)
        renderThread = null

        if (initialized) {
            Xmp.setExpectSilence(true) // We know we're going to xRun in Oboe, so silence is OK.
            Xmp.dropAudio()
            Thread.sleep(60) // allow Oboe to drain
            Xmp.endPlayer()
            Xmp.releaseModule()
            Xmp.deinit()
            initialized = false
        }

        scope.cancel()

        isPlaying.value = false
        positionMs.value = 0L
        frameInfoFlow.value = FrameInfo()
    }

    /**
     * Returns (potentially cached) [PatternData] for [patternIndex].
     * Used by the pattern visualiser.
     */
    fun getPatternData(patternIndex: Int): PatternData =
        patternCache[patternIndex] ?: run {
            val numCh = modVarsFlow.value.chn
            val numRows = Xmp.getPatternRows(patternIndex)
            // Don't cache invalid results — a race with module teardown can produce
            // numRows=0 for a valid pattern. Leaving the entry absent lets the next
            // recomposition retry rather than permanently serving a blank.
            if (numCh == 0 || numRows == 0) return PatternData()

            val notes = ByteArray(64)
            val ins = ByteArray(64)
            val fxt = ByteArray(64)
            val fxp = ByteArray(64)

            val cells = Array(numRows) { row ->
                Xmp.getPatternRow(patternIndex, row, notes, ins, fxt, fxp)
                Array(numCh) { ch ->
                    NoteCell(
                        note = notes[ch].toInt() and 0xFF,
                        instrument = ins[ch].toInt() and 0xFF,
                        fxType = fxt[ch].toInt(), // signed; -1 = empty cell
                        fxParam = fxp[ch].toInt() and 0xFF,
                    )
                }.toList().toImmutableList()
            }.toList().toImmutableList()

            PatternData(
                cells = cells,
                numChannels = numCh,
                numRows = numRows,
                patternIndex = patternIndex
            ).also { patternCache[patternIndex] = it }
        }

    /** Resets sequence-tracking fields before loading a new module. */
    private fun resetSequenceState() {
        endedNaturally = false
        stopRequest = false
        currentSequenceFlow.value = 0
    }

    /**
     * Initializes the Oboe audio stream using the current audio preferences.
     */
    private fun initAudio(): Boolean {
        val sampleRate = runBlocking { prefs.getSampleRateFlow().first() }
        val bufferMs = runBlocking { prefs.getBufferMsFlow().first() }
        val format = runBlocking { prefs.getPlayerFormatFlow().first() }
        val perfMode = runBlocking { prefs.getOboePerfModeFlow().first() }
        val audioApi = runBlocking { prefs.getOboeAudioApiFlow().first() }
        val isMono = format and Xmp.XMP_FORMAT_MONO != 0

        Timber.d(
            "load: sampleRate=$sampleRate bufferMs=$bufferMs " +
                "format=$format perfMode=$perfMode audioApi=$audioApi"
        )

        val result = Xmp.init(
            rate = sampleRate,
            ms = bufferMs,
            perfMode = perfMode,
            channels = if (isMono) Xmp.OBOE_CHANNELS_MONO else Xmp.OBOE_CHANNELS_STEREO,
            audioApi = audioApi,
            flags = format,
        )

        return if (result) {
            initialized = true
            true
        } else {
            Timber.e("Xmp.init() failed")
            false
        }
    }

    /** Applies player flags and default pan from prefs before loading a module. */
    private fun applyPreLoadSettings() {
        val playerFlags = runBlocking { prefs.getPlayerFlagsFlow().first() }
        val defaultPan = runBlocking { prefs.getDefaultPanFlow().first() }
        val preLoadMask = Xmp.XMP_FLAGS_VBLANK or Xmp.XMP_FLAGS_FX9BUG or Xmp.XMP_FLAGS_FIXLOOP
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_FLAGS,
            value = playerFlags and preLoadMask
        )
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_DEFPAN,
            value = defaultPan
        )
    }

    /** Applies volume / DSP / interpolation settings to the running player. */
    private fun applyRuntimeSettings() {
        val playerFlags = runBlocking { prefs.getPlayerFlagsFlow().first() }
        val cflags = Xmp.getPlayer(Xmp.XMP_PLAYER_CFLAGS)
        val newCflags = if ((playerFlags and Xmp.XMP_FLAGS_A500) != 0) {
            cflags or Xmp.XMP_FLAGS_A500
        } else {
            cflags and Xmp.XMP_FLAGS_A500.inv()
        }
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_AMP,
            value = runBlocking { prefs.getVolumeBoostFlow().first() }
        )
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_CFLAGS,
            value = newCflags
        )
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_DSP,
            value = runBlocking { prefs.getDspEffectFlow().first() }
        )
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_INTERP,
            value = runBlocking { prefs.getInterpolationTypeFlow().first() }
        )
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_MIX,
            value = runBlocking { prefs.getStereoMixFlow().first() }
        )
        Xmp.setPlayer(
            parm = Xmp.XMP_PLAYER_VOLUME,
            value = runBlocking { prefs.getPlayerVolumeFlow().first() }
        )
    }

    /** Pre-fills the Oboe buffer to reduce start latency. */
    private fun prefillBuffer() {
        var count = 0
        while (Xmp.hasFreeBuffer() && count++ < 100) {
            if (Xmp.fillBuffer(false) < 0) break
        }
    }

    /**
     * Loads [file] into libxmp and populates [modVarsFlow].
     *
     * @return true on success; on failure, de-inits and resets [initialized] only
     *   if this is a first-time load (i.e., [softStop] wasn't called).
     */
    private fun loadModule(file: ModuleEntity): Boolean {
        val modInfo = ModInfo()
        val result = Xmp.loadFromFd(context, file.uri, modInfo)
        if (result != 0) {
            Timber.e("Xmp.loadFromFd() returned $result")
            if (!initialized) {
                Xmp.deinit()
                initialized = false
            }
            return false
        }
        val mv = ModVars()
        Xmp.getModVars(mv)
        modVarsFlow.value = mv
        positionMs.value = 0L
        return true
    }

    /**
     * Stops the render thread and ends the libxmp player session, but leaves
     * the Oboe stream open. Used between queue tracks to avoid stream teardown overhead.
     */
    private fun softStop() {
        stopRequest = true
        renderThread?.interrupt()
        renderThread?.join(2_000)
        renderThread = null
        Xmp.endPlayer()
        isPlaying.value = false
        positionMs.value = 0L
    }

    /** Clears the [patternCache] before loading a new module. */
    private fun clearPatternCache() {
        patternCache.clear()
        modVarsFlow.value = ModVars()
        frameInfoFlow.value = FrameInfo()
    }

    /** The loop! */
    private fun renderLoop() {
        while (!stopRequest) {
            try {
                if (paused) {
                    Thread.sleep(50)
                    continue
                }

                while (!Xmp.hasFreeBuffer() && !paused && !stopRequest) {
                    Thread.sleep(40)
                }

                if (stopRequest) break

                val endReached = Xmp.fillBuffer(false) < 0

                val fi = FrameInfo()
                Xmp.getFrameInfo(fi)
                frameInfoFlow.value = fi

                val timeMs = Xmp.time()
                positionMs.value = timeMs.toLong()

                if (endReached) {
                    Timber.i("renderLoop: endReached, playAllSequences=$playAllSequences")

                    if (playAllSequences) {
                        currentSequenceFlow.value++
                        if (setSequence(currentSequenceFlow.value)) {
                            Timber.i("renderLoop: advanced to seq ${currentSequenceFlow.value}")
                            continue
                        }
                    }

                    // Drain any remaining buffers before signaling end.
                    while (Xmp.hasFreeBuffer() && !stopRequest) {
                        if (Xmp.fillBuffer(false) < 0) break
                    }

                    endedNaturally = true
                    isPlaying.value = false

                    Timber.i("renderLoop: ended naturally, exiting loop")

                    Xmp.setExpectSilence(true) // We know we're going to xRun in Oboe.

                    break
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
