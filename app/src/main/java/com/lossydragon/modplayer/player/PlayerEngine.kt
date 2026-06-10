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
import org.helllabs.libxmp.model.AudioStats
import org.helllabs.libxmp.model.ChannelInfo
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
    private var native: NativeModuleBackend = RenderingBackend.OPENMPT.nativeBackend()

    private val mutableBackendFlow = MutableStateFlow(native.renderingBackend)
    val backendFlow: StateFlow<RenderingBackend> = mutableBackendFlow

    private val mutableSupportsRawChannelSamplesFlow = MutableStateFlow(native.supportsRawChannelSamples)
    val supportsRawChannelSamplesFlow: StateFlow<Boolean> = mutableSupportsRawChannelSamplesFlow

    @Volatile
    private var isRepeatOne: Boolean = false

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
            if (initialized) native.setPlayer(Xmp.XMP_PLAYER_AMP, it)
        }.launchIn(scope)
        prefs.getStereoMixFlow().distinctUntilChanged().onEach {
            if (initialized) native.setPlayer(Xmp.XMP_PLAYER_MIX, it)
        }.launchIn(scope)
        prefs.getDspEffectFlow().distinctUntilChanged().onEach {
            if (initialized) native.setPlayer(Xmp.XMP_PLAYER_DSP, it)
        }.launchIn(scope)
        prefs.getPlayerVolumeFlow().distinctUntilChanged().onEach {
            if (initialized) native.setPlayer(Xmp.XMP_PLAYER_VOLUME, it)
        }.launchIn(scope)
        prefs.getInterpolationTypeFlow().distinctUntilChanged().onEach {
            if (initialized) native.setResampler(ResamplerMode.fromId(it))
        }.launchIn(scope)
        prefs.getPlayerFlagsFlow().distinctUntilChanged().onEach {
            if (initialized && native.renderingBackend == RenderingBackend.LIBXMP) {
                val cflags = Xmp.getPlayer(Xmp.XMP_PLAYER_CFLAGS)
                val newCflags = if ((it and Xmp.XMP_FLAGS_A500) != 0) {
                    cflags or Xmp.XMP_FLAGS_A500
                } else {
                    cflags and Xmp.XMP_FLAGS_A500.inv()
                }
                native.setPlayer(Xmp.XMP_PLAYER_CFLAGS, newCflags)
            }
        }.launchIn(scope)
    }

    /**
     * Enables or disables single-track repeat mode.
     */
    fun isRepeatingOne(value: Boolean) {
        isRepeatOne = value
        if (initialized) native.setLoopMode(value)
    }

    /**
     * Switches to sequence [index] within the loaded module.
     * Updates [positionMs] and [currentSequenceFlow] on success.
     *
     * @return true if the sequence index is valid and the switch succeeded.
     */
    fun setSequence(index: Int): Boolean {
        val result = native.setSequence(index)
        if (result) {
            val mv = ModVars()
            native.getModVars(mv)
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
        selectBackendForNextLoad()
        if (initialized) {
            softStop()
            clearPatternCache()
            native.releaseModule()
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
        val selected = runBlocking { prefs.getRenderingBackendFlow().first() }.nativeBackend()
        if (initialized && selected.renderingBackend != native.renderingBackend) {
            stop()
            native.deinit()
            initialized = false
            native = selected
        }
        if (!initialized) return load(file)

        softStop()
        clearPatternCache()
        resetSequenceState()
        native.releaseModule()
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

        stopRequest = false
        paused = false

        native.setLoopMode(isRepeatOne)

        val sampleRate = runBlocking { prefs.getSampleRateFlow().first() }
        val format = runBlocking { prefs.getPlayerFormatFlow().first() }
        if (native.startPlayer(sampleRate, format) != 0) {
            Timber.e("${native.renderingBackend}.startPlayer() failed")
            return
        }

        // Unmute all channels on every start.
        for (i in 0 until 64) native.mute(i, 0)

        applyRuntimeSettings()

        native.playAudio()
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
        native.setPlaying(false)
        isPlaying.value = false
    }

    /**
     * Resumes paused playback without reloading or seeking.
     */
    fun resume() {
        if (!paused) return
        paused = false
        native.setPlaying(true)
        isPlaying.value = true
    }

    /**
     * Seeks to [posMs] milliseconds within the current module.
     * Updates [positionMs] immediately so the UI reflects the target position.
     */
    fun seek(posMs: Int) {
        Timber.d("engine.seek posMs=$posMs")
        native.seek(posMs)
        positionMs.value = posMs.toLong()
    }

    /**
     * Stops playback and releases the current module, but keeps the Oboe stream
     * open and outputting silence. The next [load] / [loadNext] call reuses the
     * live stream so the hardware DAC never deactivates — no hardware pop.
     *
     * Call [release] when the engine is truly being torn down (service destroyed).
     */
    fun stop() {
        endedNaturally = false
        stopRequest = true
        renderThread?.interrupt()
        renderThread?.join(2_000)
        renderThread = null

        if (initialized) {
            native.endPlayer()
            native.releaseModule()
        }

        isPlaying.value = false
        positionMs.value = 0L
        frameInfoFlow.value = FrameInfo()
    }

    /**
     * Full teardown: closes the Oboe stream and cancels the preference observer scope.
     * Called only when the PlayerService is destroyed. Do not call during normal
     * stop/play transitions — use [stop] for those.
     */
    fun release() {
        stop()
        if (initialized) {
            native.deinit()
            initialized = false
        }
        scope.cancel()
    }

    /**
     * Returns (potentially cached) [PatternData] for [patternIndex].
     * Used by the pattern visualiser.
     */
    fun getPatternData(patternIndex: Int): PatternData =
        patternCache[patternIndex] ?: run {
            val numCh = modVarsFlow.value.chn
            val numRows = native.getPatternRows(patternIndex)
            // Don't cache invalid results — a race with module teardown can produce
            // numRows=0 for a valid pattern. Leaving the entry absent lets the next
            // recomposition retry rather than permanently serving a blank.
            if (numCh == 0 || numRows == 0) return PatternData()

            val notes = ByteArray(64)
            val ins = ByteArray(64)
            val fxt = ByteArray(64)
            val fxp = ByteArray(64)

            val cells = Array(numRows) { row ->
                native.getPatternRow(patternIndex, row, notes, ins, fxt, fxp)
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

    fun getAudioStats(stats: AudioStats) = native.getAudioStats(stats)

    fun getChannelData(info: ChannelInfo) = native.getChannelData(info)

    fun getSampleData(
        trigger: Boolean,
        ins: Int,
        key: Int,
        period: Int,
        chn: Int,
        width: Int,
        buffer: ByteArray?
    ) = native.getSampleData(trigger, ins, key, period, chn, width, buffer)

    fun mute(chn: Int, status: Int): Int = native.mute(chn, status)

    /** Resets sequence-tracking fields before loading a new module. */
    private fun resetSequenceState() {
        endedNaturally = false
        stopRequest = false
        currentSequenceFlow.value = 0
    }

    /** Selects the persisted backend for the next module load. */
    private fun selectBackendForNextLoad() {
        val selected = runBlocking { prefs.getRenderingBackendFlow().first() }.nativeBackend()
        if (selected.renderingBackend == native.renderingBackend) {
            mutableBackendFlow.value = native.renderingBackend
            mutableSupportsRawChannelSamplesFlow.value = native.supportsRawChannelSamples
            return
        }
        if (initialized) {
            stop()
            native.deinit()
            initialized = false
        }
        native = selected
        mutableBackendFlow.value = native.renderingBackend
        mutableSupportsRawChannelSamplesFlow.value = native.supportsRawChannelSamples
    }

    /**
     * Initializes the Oboe audio stream using the current audio preferences.
     */
    private fun initAudio(): Boolean {
        return runBlocking {
            val sampleRate = prefs.getSampleRateFlow().first()
            val bufferMs = prefs.getBufferMsFlow().first()
            val format = prefs.getPlayerFormatFlow().first()
            val perfMode = prefs.getOboePerfModeFlow().first()
            val audioApi = prefs.getOboeAudioApiFlow().first()
            val isMono = format and Xmp.XMP_FORMAT_MONO != 0

            Timber.d(
                "load: sampleRate=$sampleRate bufferMs=$bufferMs " +
                    "format=$format perfMode=$perfMode audioApi=$audioApi"
            )

            selectBackendForNextLoad()
            val result = native.init(
                rate = sampleRate,
                ms = bufferMs,
                perfMode = perfMode,
                channels = if (isMono) Xmp.OBOE_CHANNELS_MONO else Xmp.OBOE_CHANNELS_STEREO,
                audioApi = audioApi,
                flags = format,
            )

            if (result) {
                initialized = true
                return@runBlocking true
            } else {
                Timber.e("${native.renderingBackend}.init() failed")
                return@runBlocking false
            }
        }
    }

    /** Applies player flags and default pan from prefs before loading a module. */
    private fun applyPreLoadSettings() {
        val playerFlags = runBlocking { prefs.getPlayerFlagsFlow().first() }
        val defaultPan = runBlocking { prefs.getDefaultPanFlow().first() }
        val preLoadMask = Xmp.XMP_FLAGS_VBLANK or Xmp.XMP_FLAGS_FX9BUG or Xmp.XMP_FLAGS_FIXLOOP
        native.setPlayer(
            parm = Xmp.XMP_PLAYER_FLAGS,
            value = playerFlags and preLoadMask
        )
        native.setPlayer(
            parm = Xmp.XMP_PLAYER_DEFPAN,
            value = defaultPan
        )
    }

    /** Applies volume / DSP / interpolation settings to the running player. */
    private fun applyRuntimeSettings() {
        runBlocking {
            val playerFlags = prefs.getPlayerFlagsFlow().first()
            native.setPlayer(
                parm = Xmp.XMP_PLAYER_AMP,
                value = prefs.getVolumeBoostFlow().first()
            )
            if (native.renderingBackend == RenderingBackend.LIBXMP) {
                val cflags = Xmp.getPlayer(Xmp.XMP_PLAYER_CFLAGS)
                val newCflags = if ((playerFlags and Xmp.XMP_FLAGS_A500) != 0) {
                    cflags or Xmp.XMP_FLAGS_A500
                } else {
                    cflags and Xmp.XMP_FLAGS_A500.inv()
                }
                native.setPlayer(
                    parm = Xmp.XMP_PLAYER_CFLAGS,
                    value = newCflags
                )
            }
            native.setPlayer(
                parm = Xmp.XMP_PLAYER_DSP,
                value = prefs.getDspEffectFlow().first()
            )
            native.setResampler(ResamplerMode.fromId(prefs.getInterpolationTypeFlow().first()))
            native.setPlayer(
                parm = Xmp.XMP_PLAYER_MIX,
                value = prefs.getStereoMixFlow().first()
            )
            native.setPlayer(
                parm = Xmp.XMP_PLAYER_VOLUME,
                value = prefs.getPlayerVolumeFlow().first()
            )
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
        val result = native.loadFromFd(context, file.uri, modInfo)
        if (result != 0) {
            Timber.e("${native.renderingBackend}.loadFromFd() returned $result")
            if (!initialized) {
                native.deinit()
                initialized = false
            }
            return false
        }
        val mv = ModVars()
        native.getModVars(mv)
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
        native.endPlayer()
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
                Thread.sleep(20)

                if (paused || stopRequest) continue

                val fi = FrameInfo()
                native.getFrameInfo(fi)
                frameInfoFlow.value = fi

                positionMs.value = fi.time.toLong().coerceAtLeast(0L)

                if (native.hasModuleEnded()) {
                    Timber.i("renderLoop: module ended, playAllSequences=$playAllSequences")

                    if (playAllSequences) {
                        currentSequenceFlow.value++
                        if (setSequence(currentSequenceFlow.value)) {
                            Timber.i("renderLoop: advanced to seq ${currentSequenceFlow.value}")
                            continue
                        }
                    }

                    endedNaturally = true
                    isPlaying.value = false
                    Timber.i("renderLoop: ended naturally")
                    break
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
