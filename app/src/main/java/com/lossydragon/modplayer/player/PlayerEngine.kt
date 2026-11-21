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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Wraps the libxmp / libopenmpt / Oboe JNI layer. Every native call that mutates state runs
 * on [executor]; the render loop only polls read-only snapshots.
 */
class PlayerEngine(
    private val context: Context,
    private val prefs: AppPreferences
) : Engine {

    private companion object {
        const val DUCK_PERCENT = 70
        const val MAX_CHANNELS = 64
        const val POLL_MS = 20L
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ModPlayerEngine") }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The dedicated thread running [renderLoop]. Null when stopped. */
    private var renderThread: Thread? = null

    override val frameInfoFlow: StateFlow<FrameInfo>
        field = MutableStateFlow(FrameInfo())

    override val playingFlow: StateFlow<Boolean>
        field = MutableStateFlow(false)

    override val positionMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    override val currentSequenceFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    override val modVarsFlow: StateFlow<ModVars>
        field = MutableStateFlow(ModVars())

    override val events: SharedFlow<EngineEvent>
        field = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)

    /** Filled by [getPatternData] on UI threads, cleared on load; hence concurrent. */
    private val patternCache = ConcurrentHashMap<Int, PatternData>()

    @Volatile
    private var isRepeatOne = false

    @Volatile
    private var ducked = false

    /** Set to make the render thread exit. */
    @Volatile
    private var stopRequest = false

    @Volatile
    override var playAllSequences = false

    /** Stream open and a module loaded. */
    @Volatile
    private var initialized = false

    /** Stream open but not rendering. */
    @Volatile
    private var paused = false

    /** Everything the engine reads from preferences, as one snapshot. */
    private data class EnginePrefs(
        val sampleRate: Int,
        val bufferMs: Int,
        val format: Int,
        val perfMode: Int,
        val audioApi: Int,
        val playerFlags: Int,
        val defaultPan: Int,
        val volumeBoost: Int,
        val stereoMix: Int,
        val dsp: Int,
        val interpolation: Int,
        val volume: Int,
        val backend: RenderingBackend
    ) {
        val audioConfig get() = AudioConfig(sampleRate, bufferMs, format, perfMode, audioApi)
    }

    /** Stream-level settings that only take effect by reopening the Oboe stream. */
    private data class AudioConfig(
        val sampleRate: Int,
        val bufferMs: Int,
        val format: Int,
        val perfMode: Int,
        val audioApi: Int
    )

    /** Config the current stream was opened with; null when no stream is open. */
    private var activeAudioConfig: AudioConfig? = null

    private val enginePrefs: StateFlow<EnginePrefs?> = combine(
        listOf<Flow<Int>>(
            prefs.getSampleRateFlow(),
            prefs.getBufferMsFlow(),
            prefs.getPlayerFormatFlow(),
            prefs.getOboePerfModeFlow(),
            prefs.getOboeAudioApiFlow(),
            prefs.getPlayerFlagsFlow(),
            prefs.getDefaultPanFlow(),
            prefs.getVolumeBoostFlow(),
            prefs.getStereoMixFlow(),
            prefs.getDspEffectFlow(),
            prefs.getInterpolationTypeFlow(),
            prefs.getPlayerVolumeFlow(),
            prefs.getRenderingBackendFlow().map { it.id },
        )
    ) { v ->
        EnginePrefs(
            sampleRate = v[0], bufferMs = v[1], format = v[2], perfMode = v[3], audioApi = v[4],
            playerFlags = v[5], defaultPan = v[6], volumeBoost = v[7], stereoMix = v[8],
            dsp = v[9], interpolation = v[10], volume = v[11],
            backend = RenderingBackend.fromId(v[12]),
        )
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Settings that take effect on a running player are re-applied on every change.
        enginePrefs.filterNotNull().onEach { p ->
            post { if (initialized) applyRuntimeSettings(p) }
        }.launchIn(scope)
    }

    override fun post(block: () -> Unit) {
        executor.execute(block)
    }

    /** Engine thread only. Blocks once, before the first preference emission. */
    private fun prefs(): EnginePrefs =
        enginePrefs.value ?: runBlocking { enginePrefs.filterNotNull().first() }

    override fun load(file: ModuleEntity): LoadResult {
        val p = prefs()
        Timber.d("load: '${file.filename}' backend=${p.backend} initialized=$initialized")
        selectBackend(p.backend)
        reopenAudioIfConfigChanged(p.audioConfig)
        if (initialized) {
            joinRenderThread()
            Player.endPlayer()
            Player.releaseModule()
        } else if (!initAudio(p)) {
            return LoadResult.NO_AUDIO
        }
        paused = false
        playingFlow.value = false
        positionMs.value = 0L
        currentSequenceFlow.value = 0
        clearModuleState()
        applyPreLoadSettings(p)
        return if (loadModule(file)) LoadResult.OK else LoadResult.UNPLAYABLE
    }

    override fun play(): Boolean {
        if (!initialized) {
            Timber.w("play: nothing loaded")
            return false
        }
        if (Player.hasAudioDisconnected()) {
            signalDisconnected("play")
            return false
        }
        if (renderThread?.isAlive == true) {
            paused = false
            Player.setPlaying(true)
            playingFlow.value = true
            return true
        }

        stopRequest = false
        paused = false
        Player.setLoopMode(isRepeatOne)
        val p = prefs()
        if (Player.startPlayer(p.sampleRate, p.format) != 0) {
            Timber.e("${Player.renderingBackend}.startPlayer() failed")
            events.tryEmit(EngineEvent.Error("Could not start ${Player.renderingBackend}"))
            return false
        }
        for (i in 0 until MAX_CHANNELS) Player.mute(i, 0)
        applyRuntimeSettings(p)

        // A stream that died while idle only shows up here.
        if (Player.playAudio() != 0) {
            signalDisconnected("playAudio")
            return false
        }
        playingFlow.value = true
        startRenderThread()
        return true
    }

    override fun pause() {
        if (!initialized) return
        paused = true
        Player.setPlaying(false)
        playingFlow.value = false
    }

    override fun seek(posMs: Int) {
        if (!initialized) return
        Player.seek(posMs)
        positionMs.value = posMs.toLong()
    }

    /** Keeps the Oboe stream open and silent so the DAC never deactivates (no pop). */
    override fun stop() {
        Timber.d("stop: initialized=$initialized")
        joinRenderThread()
        if (initialized) {
            Player.endPlayer()
            Player.releaseModule()
        }
        paused = false
        playingFlow.value = false
        positionMs.value = 0L
        clearModuleState()
    }

    override fun recoverFromDisconnect() {
        Timber.w("recoverFromDisconnect: initialized=$initialized")
        joinRenderThread()
        deinit()
        playingFlow.value = false
        clearModuleState()
    }

    /**
     * The engine is a process-wide singleton that outlives the service, so [scope] and
     * [executor] stay alive: the next service instance reuses them.
     */
    override fun release() {
        Timber.d("release")
        stop()
        deinit()
    }

    override fun setSequence(index: Int): Boolean {
        if (!initialized) return false
        val result = Player.setSequence(index)
        Timber.d("setSequence: index=$index result=$result")
        if (result) {
            modVarsFlow.value = readModVars()
            positionMs.value = 0L
            currentSequenceFlow.value = index
        }
        return result
    }

    override fun setRepeatOne(value: Boolean) {
        isRepeatOne = value
        if (initialized) Player.setLoopMode(value)
    }

    override fun setDucked(value: Boolean) {
        if (ducked == value) return
        ducked = value
        post { if (initialized) applyVolume(prefs().volume) }
    }

    override fun getPatternData(patternIndex: Int): PatternData =
        patternCache[patternIndex] ?: run {
            val numCh = modVarsFlow.value.chn
            val numRows = Player.getPatternRows(patternIndex)
            // Not cached: a race with module teardown can yield 0 rows for a valid pattern.
            if (numCh == 0 || numRows <= 0) return PatternData()

            val notes = ByteArray(MAX_CHANNELS)
            val ins = ByteArray(MAX_CHANNELS)
            val fxt = ByteArray(MAX_CHANNELS)
            val fxp = ByteArray(MAX_CHANNELS)
            val fx2t = ByteArray(MAX_CHANNELS)
            val fx2p = ByteArray(MAX_CHANNELS)

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

    override fun getAudioStats(stats: AudioStats) = Player.getAudioStats(stats)

    // --- engine thread internals -----------------------------------------------------------

    private fun selectBackend(selected: RenderingBackend) {
        if (selected == Player.renderingBackend) return
        Timber.i("backend switch ${Player.renderingBackend} -> $selected")
        if (initialized) {
            stop()
            deinit()
        }
        Player.switchBackend(selected)
    }

    private fun reopenAudioIfConfigChanged(wanted: AudioConfig) {
        if (!initialized || wanted == activeAudioConfig) return
        Timber.i("audio config changed $activeAudioConfig -> $wanted, reopening stream")
        stop()
        deinit()
    }

    private fun initAudio(p: EnginePrefs): Boolean {
        val isMono = p.format and Player.XMP_FORMAT_MONO != 0
        Timber.d("initAudio: $p")
        initialized = Player.init(
            rate = p.sampleRate,
            ms = p.bufferMs,
            perfMode = p.perfMode,
            channels = if (isMono) Player.OBOE_CHANNELS_MONO else Player.OBOE_CHANNELS_STEREO,
            audioApi = p.audioApi,
            flags = p.format,
        )
        if (!initialized) Timber.e("${Player.renderingBackend}.init() failed")
        activeAudioConfig = p.audioConfig.takeIf { initialized }
        return initialized
    }

    private fun applyPreLoadSettings(p: EnginePrefs) {
        val preLoadMask =
            Player.XMP_FLAGS_VBLANK or Player.XMP_FLAGS_FX9BUG or Player.XMP_FLAGS_FIXLOOP
        Player.setPlayer(Player.XMP_PLAYER_FLAGS, p.playerFlags and preLoadMask)
        Player.setPlayer(Player.XMP_PLAYER_DEFPAN, p.defaultPan)
    }

    private fun applyRuntimeSettings(p: EnginePrefs) {
        Player.setPlayer(Player.XMP_PLAYER_AMP, p.volumeBoost)
        applyA500Flag(p.playerFlags)
        Player.setPlayer(Player.XMP_PLAYER_DSP, p.dsp)
        Player.setResampler(ResamplerMode.fromId(p.interpolation).id)
        Player.setPlayer(Player.XMP_PLAYER_MIX, p.stereoMix)
        applyVolume(p.volume)
    }

    private fun applyVolume(preferred: Int) {
        val volume = if (ducked) preferred * DUCK_PERCENT / 100 else preferred
        Player.setPlayer(Player.XMP_PLAYER_VOLUME, volume)
    }

    /** Mirrors the A500 filter bit into CFLAGS (libxmp backend only). */
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

    /** Never throws; the stream stays open on failure so the next load reuses it. */
    private fun loadModule(file: ModuleEntity): Boolean {
        val result = try {
            Player.loadFromFd(context, file.uri, ModInfo())
        } catch (e: Exception) {
            Timber.e(e, "loadFromFd threw for '${file.filename}'")
            Player.LOAD_ERROR_OPEN_FAILED
        }
        if (result != 0) {
            Timber.e(
                "${Player.renderingBackend}.loadFromFd() returned $result for '${file.filename}'"
            )
            return false
        }
        val mv = readModVars()
        modVarsFlow.value = mv
        Timber.i(
            "loadModule: '${mv.modName}' type=${mv.modType} chn=${mv.chn} seq=${mv.miNumSequences}"
        )
        return true
    }

    private fun signalDisconnected(where: String) {
        Timber.w("$where: audio stream is disconnected")
        playingFlow.value = false
        events.tryEmit(EngineEvent.Disconnected)
    }

    private fun startRenderThread() {
        stopRequest = false
        renderThread = Thread(::renderLoop, "ModPlayerPoll").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /** Signals the render thread to exit and waits for it. */
    private fun joinRenderThread() {
        stopRequest = true
        renderThread?.let {
            it.interrupt()
            it.join(2_000)
        }
        renderThread = null
    }

    private fun deinit() {
        if (!initialized) return
        Player.deinit()
        initialized = false
        activeAudioConfig = null
    }

    private fun clearModuleState() {
        patternCache.clear()
        modVarsFlow.value = ModVars()
        frameInfoFlow.value = FrameInfo()
    }

    private fun readModVars() = ModVars().also { Player.getModVars(it) }

    /** Engine thread: the module finished; try the next sub-song or report the end. */
    private fun onModuleEnded() {
        // A load/play queued ahead of this already cleared the native end flag.
        if (!initialized || stopRequest || !Player.hasModuleEnded()) return
        if (playAllSequences) {
            val nextSeq = currentSequenceFlow.value + 1
            if (setSequence(nextSeq)) {
                Timber.i("onModuleEnded: sequence $nextSeq / ${modVarsFlow.value.miNumSequences}")
                Player.setPlaying(true) // clears the native end flag
                startRenderThread()
                return
            }
        }
        Timber.i("onModuleEnded: ended naturally")
        Player.setPlaying(false) // or the callback keeps mixing while parked
        playingFlow.value = false
        events.tryEmit(EngineEvent.Ended)
    }

    /** Poll thread: read-only snapshots only; anything that mutates is posted to [executor]. */
    private fun renderLoop() {
        while (!stopRequest) {
            try {
                Thread.sleep(POLL_MS)

                // Before the pause gate: a route change while paused must not go unnoticed.
                if (Player.hasAudioDisconnected()) {
                    signalDisconnected("renderLoop")
                    break
                }
                if (paused || stopRequest) continue

                val fi = FrameInfo()
                Player.getFrameInfo(fi)
                frameInfoFlow.value = fi
                positionMs.value = fi.time.toLong().coerceAtLeast(0L)

                if (Player.hasModuleEnded()) {
                    post(::onModuleEnded)
                    break
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }
}
