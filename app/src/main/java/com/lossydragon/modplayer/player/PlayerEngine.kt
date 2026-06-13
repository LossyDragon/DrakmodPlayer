package com.lossydragon.modplayer.player

import android.content.Context
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.model.NoteCell
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.native.Player
import com.lossydragon.native.RenderingBackend
import com.lossydragon.native.ResamplerMode
import com.lossydragon.native.model.AudioStats
import com.lossydragon.native.model.FrameInfo
import com.lossydragon.native.model.ModInfo
import com.lossydragon.native.model.ModVars
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
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

    /** Per-frame playback snapshot emitted by the render loop; reset when idle. */
    val frameInfoFlow: StateFlow<FrameInfo>
        field = MutableStateFlow(FrameInfo())

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

    /** Emits true when Oboe reports ErrorDisconnected (e.g. headphones unplugged, BT disconnected). */
    val audioDisconnectedFlow: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Lazily populated by [getPatternData]; cleared on each module load. */
    private val patternCache = mutableMapOf<Int, PatternData>()

    @Volatile
    private var isRepeatOne = false

    /** Set to true to signal the render thread to exit cleanly. */
    @Volatile
    private var stopRequest = false

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
    var endedNaturally = false
        private set

    init {
        val initialBackend = prefs.getRenderingBackendFlow().blockingFirst()
        if (initialBackend != RenderingBackend.INVALID) Player.switchBackend(initialBackend)

        observePref(prefs.getVolumeBoostFlow()) { Player.setPlayer(Player.XMP_PLAYER_AMP, it) }
        observePref(prefs.getStereoMixFlow()) { Player.setPlayer(Player.XMP_PLAYER_MIX, it) }
        observePref(prefs.getDspEffectFlow()) { Player.setPlayer(Player.XMP_PLAYER_DSP, it) }
        observePref(prefs.getPlayerVolumeFlow()) { Player.setPlayer(Player.XMP_PLAYER_VOLUME, it) }
        observePref(prefs.getInterpolationTypeFlow()) {
            Player.setResampler(ResamplerMode.fromId(it).id)
        }
        observePref(prefs.getPlayerFlagsFlow(), ::applyA500Flag)
    }

    /** Enables or disables single-track repeat mode. */
    fun isRepeatingOne(value: Boolean) {
        Timber.d("isRepeatingOne: $value")
        isRepeatOne = value
        if (initialized) Player.setLoopMode(value)
    }

    /**
     * Switches to sequence [index] within the loaded module.
     * Updates [positionMs] and [currentSequenceFlow] on success.
     *
     * @return true if the sequence index is valid and the switch succeeded.
     */
    fun setSequence(index: Int): Boolean {
        val result = Player.setSequence(index)
        Timber.d("setSequence: index=$index result=$result")
        if (result) {
            modVarsFlow.value = readModVars()
            positionMs.value = 0L
            currentSequenceFlow.value = index
        }
        return result
    }

    /**
     * Fast-path for queue transitions: keeps the Oboe stream open, stops the
     * render loop, releases the current module, then loads [file].
     * Falls back to [load] if the engine is uninitialised or the backend changed.
     *
     * @return true on success; false if Oboe or libxmp init fails.
     */
    fun loadNext(file: ModuleEntity): Boolean {
        Timber.d(
            "loadNext: '${file.filename}' backend=${Player.renderingBackend} initialized=$initialized"
        )
        selectBackendForNextLoad()
        if (!initialized) return load(file)

        softStop()
        clearPatternCache()
        resetSequenceState()
        Player.releaseModule()
        applyPreLoadSettings()
        return loadModule(file)
    }

    /**
     * Starts the render loop and audio output.
     * Applies all current preference values to libxmp before playback begins.
     */
    fun start() {
        Timber.d(
            "start: backend=${Player.renderingBackend} repeatOne=$isRepeatOne playAllSeq=$playAllSequences"
        )
        if (!initialized) {
            Timber.w("start: not initialized, aborting")
            return
        }
        if (renderThread?.isAlive == true) {
            Timber.w("start: render thread already alive")
            return
        }

        stopRequest = false
        paused = false
        Player.setLoopMode(isRepeatOne)

        val sampleRate = prefs.getSampleRateFlow().blockingFirst()
        val format = prefs.getPlayerFormatFlow().blockingFirst()
        if (Player.startPlayer(sampleRate, format) != 0) {
            Timber.e("${Player.renderingBackend}.startPlayer() failed")
            return
        }

        // Unmute all channels on every start.
        for (i in 0 until 64) Player.mute(i, 0)
        applyRuntimeSettings()

        Player.playAudio()
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
        Timber.d("pause")
        endedNaturally = false
        paused = true
        Player.setPlaying(false)
        isPlaying.value = false
    }

    /** Resumes paused playback without reloading or seeking. */
    fun resume() {
        if (!paused) {
            Timber.w("resume: not paused, ignoring")
            return
        }
        Timber.d("resume")
        paused = false
        Player.setPlaying(true)
        isPlaying.value = true
    }

    /**
     * Seeks to [posMs] milliseconds within the current module.
     * Updates [positionMs] immediately so the UI reflects the target position.
     */
    fun seek(posMs: Int) {
        Timber.d("engine.seek posMs=$posMs")
        Player.seek(posMs)
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
        Timber.d("stop: initialized=$initialized")
        endedNaturally = false
        joinRenderThread(2_000)
        if (initialized) {
            Player.endPlayer()
            Player.releaseModule()
        }
        isPlaying.value = false
        positionMs.value = 0L
        frameInfoFlow.value = FrameInfo()
    }

    /**
     * Cleans up after an Oboe ErrorDisconnected event so the next [load] / [loadNext]
     * call can open a fresh stream on the new audio device.
     *
     * Must be called off the main thread (joins the render thread and calls [Player.deinit]).
     */
    fun handleAudioDisconnect() {
        Timber.w("handleAudioDisconnect: joining render thread, initialized=$initialized")
        joinRenderThread(1_000)
        deinit()
        audioDisconnectedFlow.value = false
        Timber.w("handleAudioDisconnect: done")
    }

    /**
     * Full teardown: closes the Oboe stream and cancels the preference observer scope.
     * Called only when the PlayerService is destroyed. Do not call during normal
     * stop/play transitions — use [stop] for those.
     */
    fun release() {
        Timber.d("release")
        stop()
        deinit()
        scope.cancel()
    }

    /**
     * Returns (potentially cached) [PatternData] for [patternIndex].
     * Used by the pattern visualiser.
     */
    fun getPatternData(patternIndex: Int): PatternData =
        patternCache[patternIndex] ?: run {
            val numCh = modVarsFlow.value.chn
            val numRows = Player.getPatternRows(patternIndex)
            // Don't cache invalid results — a race with module teardown can produce
            // numRows=0 for a valid pattern. Leaving the entry absent lets the next
            // recomposition retry rather than permanently serving a blank.
            if (numCh == 0 || numRows == 0) return PatternData()

            val notes = ByteArray(64)
            val ins = ByteArray(64)
            val fxt = ByteArray(64)
            val fxp = ByteArray(64)
            val fx2t = ByteArray(64)
            val fx2p = ByteArray(64)

            val cells = Array(numRows) { row ->
                Player.getPatternRow(patternIndex, row, notes, ins, fxt, fxp, fx2t, fx2p)
                Array(numCh) { ch ->
                    NoteCell(
                        note = notes[ch].toInt() and 0xFF,
                        instrument = ins[ch].toInt() and 0xFF,
                        fxType = fxt[ch].toInt(), // signed; -1 = empty slot
                        fxParam = fxp[ch].toInt() and 0xFF,
                        fx2Type = fx2t[ch].toInt(),
                        fx2Param = fx2p[ch].toInt() and 0xFF,
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

    fun getAudioStats(stats: AudioStats) = Player.getAudioStats(stats)

    /**
     * Full initialisation path: selects the persisted backend, inits the Oboe
     * stream, then loads [file]. Use [loadNext] for queue transitions so the
     * stream stays open between tracks.
     */
    private fun load(file: ModuleEntity): Boolean {
        Timber.d(
            "load: '${file.filename}' backend=${Player.renderingBackend} initialized=$initialized"
        )
        selectBackendForNextLoad()
        if (initialized) {
            softStop()
            Player.releaseModule()
        } else if (!initAudio()) {
            return false
        }
        clearPatternCache()
        resetSequenceState()
        applyPreLoadSettings()
        return loadModule(file)
    }

    /** Resets sequence-tracking fields before loading a new module. */
    private fun resetSequenceState() {
        endedNaturally = false
        stopRequest = false
        currentSequenceFlow.value = 0
    }

    /** Switches to the persisted backend if it differs, tearing down the old one first. */
    private fun selectBackendForNextLoad() {
        val selected = prefs.getRenderingBackendFlow().blockingFirst()
        if (selected == Player.renderingBackend) return
        Timber.i("backend switch ${Player.renderingBackend} -> $selected")
        if (initialized) {
            stop()
            deinit()
        }
        Player.switchBackend(selected)
    }

    /** Initializes the Oboe audio stream using the current audio preferences. */
    private fun initAudio(): Boolean = runBlocking {
        val sampleRate = prefs.getSampleRateFlow().first()
        val bufferMs = prefs.getBufferMsFlow().first()
        val format = prefs.getPlayerFormatFlow().first()
        val perfMode = prefs.getOboePerfModeFlow().first()
        val audioApi = prefs.getOboeAudioApiFlow().first()
        val isMono = format and Player.XMP_FORMAT_MONO != 0

        Timber.d(
            "initAudio: sampleRate=$sampleRate bufferMs=$bufferMs " +
                "format=$format perfMode=$perfMode audioApi=$audioApi"
        )

        selectBackendForNextLoad()
        initialized = Player.init(
            rate = sampleRate,
            ms = bufferMs,
            perfMode = perfMode,
            channels = if (isMono) Player.OBOE_CHANNELS_MONO else Player.OBOE_CHANNELS_STEREO,
            audioApi = audioApi,
            flags = format,
        )
        if (!initialized) Timber.e("${Player.renderingBackend}.init() failed")
        initialized
    }

    /** Applies player flags and default pan from prefs before loading a module. */
    private fun applyPreLoadSettings() {
        val preLoadMask =
            Player.XMP_FLAGS_VBLANK or Player.XMP_FLAGS_FX9BUG or Player.XMP_FLAGS_FIXLOOP
        Player.setPlayer(
            Player.XMP_PLAYER_FLAGS,
            prefs.getPlayerFlagsFlow().blockingFirst() and preLoadMask
        )
        Player.setPlayer(Player.XMP_PLAYER_DEFPAN, prefs.getDefaultPanFlow().blockingFirst())
    }

    /** Applies volume / DSP / interpolation settings to the running player. */
    private fun applyRuntimeSettings() = runBlocking {
        Player.setPlayer(Player.XMP_PLAYER_AMP, prefs.getVolumeBoostFlow().first())
        applyA500Flag(prefs.getPlayerFlagsFlow().first())
        Player.setPlayer(Player.XMP_PLAYER_DSP, prefs.getDspEffectFlow().first())
        Player.setResampler(ResamplerMode.fromId(prefs.getInterpolationTypeFlow().first()).id)
        Player.setPlayer(Player.XMP_PLAYER_MIX, prefs.getStereoMixFlow().first())
        Player.setPlayer(Player.XMP_PLAYER_VOLUME, prefs.getPlayerVolumeFlow().first())
    }

    /** Mirrors the A500 filter bit of [playerFlags] into CFLAGS (libxmp backend only). */
    private fun applyA500Flag(playerFlags: Int) {
        if (Player.renderingBackend != RenderingBackend.LIBXMP) return
        val cflags = Player.getPlayer(Player.XMP_PLAYER_CFLAGS)
        val newCflags = if (playerFlags and Player.XMP_FLAGS_A500 != 0) {
            cflags or Player.XMP_FLAGS_A500
        } else {
            cflags and Player.XMP_FLAGS_A500.inv()
        }
        Player.setPlayer(Player.XMP_PLAYER_CFLAGS, newCflags)
    }

    /**
     * Loads [file] into libxmp and populates [modVarsFlow].
     *
     * @return true on success; on failure, de-inits only if this is a
     *   first-time load (i.e., [softStop] wasn't called).
     */
    private fun loadModule(file: ModuleEntity): Boolean {
        val result = Player.loadFromFd(context, file.uri, ModInfo())
        if (result != 0) {
            Timber.e(
                "${Player.renderingBackend}.loadFromFd() returned $result for '${file.filename}'"
            )
            if (!initialized) Player.deinit()
            return false
        }
        val mv = readModVars()
        modVarsFlow.value = mv
        positionMs.value = 0L
        Timber.i(
            "loadModule: '${mv.modName}' type=${mv.modType} chn=${mv.chn} seq=${mv.miNumSequences}"
        )
        return true
    }

    /**
     * Stops the render thread and ends the libxmp player session, but leaves
     * the Oboe stream open. Used between queue tracks to avoid teardown overhead.
     */
    private fun softStop() {
        Timber.d("softStop")
        joinRenderThread(2_000)
        Player.endPlayer()
        isPlaying.value = false
        positionMs.value = 0L
    }

    /** Signals the render thread to exit, interrupts it, and waits up to [timeoutMs]. */
    private fun joinRenderThread(timeoutMs: Long) {
        stopRequest = true
        renderThread?.interrupt()
        renderThread?.join(timeoutMs)
        renderThread = null
    }

    /** Closes the native player if it is currently initialised. */
    private fun deinit() {
        if (!initialized) return
        Player.deinit()
        initialized = false
    }

    /** Clears cached pattern data and module/frame state before loading a new module. */
    private fun clearPatternCache() {
        patternCache.clear()
        modVarsFlow.value = ModVars()
        frameInfoFlow.value = FrameInfo()
    }

    private fun readModVars() = ModVars().also { Player.getModVars(it) }

    private fun <T> Flow<T>.blockingFirst(): T = runBlocking { first() }

    /** Re-applies [apply] whenever a preference changes while the engine is initialised. */
    private fun <T> observePref(flow: Flow<T>, apply: (T) -> Unit) {
        flow.distinctUntilChanged().onEach { if (initialized) apply(it) }.launchIn(scope)
    }

    /** The loop! */
    private fun renderLoop() {
        Timber.d("renderLoop: start")
        while (!stopRequest) {
            try {
                Thread.sleep(20)

                if (paused || stopRequest) continue

                val fi = FrameInfo()
                Player.getFrameInfo(fi)
                frameInfoFlow.value = fi
                positionMs.value = fi.time.toLong().coerceAtLeast(0L)

                if (Player.hasAudioDisconnected()) {
                    Timber.w("renderLoop: audio device disconnected")
                    isPlaying.value = false
                    audioDisconnectedFlow.value = true
                    break
                }

                if (Player.hasModuleEnded()) {
                    Timber.i(
                        "renderLoop: module ended pos=${positionMs.value}ms playAllSequences=$playAllSequences"
                    )

                    if (playAllSequences) {
                        val nextSeq = currentSequenceFlow.value + 1
                        Timber.i(
                            "renderLoop: trying seq $nextSeq / ${modVarsFlow.value.miNumSequences}"
                        )
                        if (setSequence(nextSeq)) continue
                        Timber.i("renderLoop: no more sequences, ending naturally")
                    }

                    endedNaturally = true
                    isPlaying.value = false
                    Timber.i("renderLoop: ended naturally")
                    break
                }
            } catch (_: InterruptedException) {
                Timber.d("renderLoop: interrupted")
                Thread.currentThread().interrupt()
                break
            }
        }
        Timber.d("renderLoop: exit stopRequest=$stopRequest endedNaturally=$endedNaturally")
    }
}
