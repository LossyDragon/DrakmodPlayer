package com.lossydragon.native

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lossydragon.native.model.AudioStats
import com.lossydragon.native.model.ChannelInfo
import com.lossydragon.native.model.FrameInfo
import com.lossydragon.native.model.ModInfo
import com.lossydragon.native.model.ModVars

object Player {

    const val MIN_BUFFER_MS = 80
    const val MAX_BUFFER_MS = 1000

    /* sample format flags */
    const val XMP_FORMAT_8BIT = (1 shl 0)       /* Mix to 8-bit instead of 16 */
    const val XMP_FORMAT_UNSIGNED = (1 shl 1)   /* Mix to unsigned samples */
    const val XMP_FORMAT_MONO = (1 shl 2)       /* Mix to mono instead of stereo */
    const val XMP_FORMAT_32BIT = (1 shl 3)      /* Mix to 32-bit int instead of 16 */

    /* player parameters */
    const val XMP_PLAYER_AMP = 0            /* Amplification factor */
    const val XMP_PLAYER_MIX = 1            /* Stereo mixing */
    const val XMP_PLAYER_INTERP = 2         /* Interpolation type */
    const val XMP_PLAYER_DSP = 3            /* DSP effect flags */
    const val XMP_PLAYER_FLAGS = 4          /* Player flags */
    const val XMP_PLAYER_CFLAGS = 5         /* Player flags for current module */
    const val XMP_PLAYER_SMPCTL = 6         /* Sample control flags */
    const val XMP_PLAYER_VOLUME = 7         /* Player module volume */
    const val XMP_PLAYER_SMIX_VOLUME = 9    /* SMIX volume */
    const val XMP_PLAYER_DEFPAN = 10        /* Default pan setting */
    const val XMP_PLAYER_MODE = 11          /* Player personality */
    const val XMP_PLAYER_VOICES = 13        /* Maximum number of mixer voices */

    /* interpolation types */
    const val XMP_INTERP_NEAREST = 0    /* Nearest neighbor */
    const val XMP_INTERP_LINEAR = 1     /* Linear (default) */
    const val XMP_INTERP_SPLINE = 2     /* Cubic spline */

    /* player flags */
    const val XMP_FLAGS_VBLANK = (1 shl 0)  /* Use vblank timing */
    const val XMP_FLAGS_FX9BUG = (1 shl 1)  /* Emulate FX9 bug */
    const val XMP_FLAGS_FIXLOOP = (1 shl 2) /* Emulate sample loop bug */
    const val XMP_FLAGS_A500 = (1 shl 3)    /* Use Paula mixer in Amiga modules */

    /* dsp effect types */
    const val XMP_DSP_NONE = 0
    const val XMP_DSP_LOWPASS = (1 shl 0)       /* Lowpass filter effect */

    /* Oboe Audio configuration */
    const val OBOE_PERFMODE_LOWLATENCY = -1
    const val OBOE_PERFMODE_NONE = 1
    const val OBOE_PERFMODE_POWERSAVING = 2

    const val OBOE_CHANNELS_STEREO = -1
    const val OBOE_CHANNELS_MONO = 1

    const val OBOE_AUDIO_API_AAUDIO = 1
    const val OBOE_AUDIO_API_OPENSLES = 2
    const val OBOE_AUDIO_API_UNSPECIFIED = -1

    // Default Values

    val volumeBoostRange = 0..3
    val interpolationTypes = listOf(XMP_INTERP_NEAREST, XMP_INTERP_LINEAR, XMP_INTERP_SPLINE)

    const val DEFAULT_BUFFER_MS = 400
    const val DEFAULT_SAMPLE_RATE = 44100
    const val DEFAULT_PAN_SEPARATION = 100
    const val DEFAULT_PLAYER_VOLUME = 100
    const val DEFAULT_STEREO_MIX = 70
    const val DEFAULT_VOLUME_BOOST = 1
    const val DEFAULT_INTERPOLATION = XMP_INTERP_LINEAR

    private const val TAG: String = "Drakplayer Player Object"

    var renderingBackend: RenderingBackend = RenderingBackend.INVALID
        private set

    init {
        System.loadLibrary("xmp-jni")
    }

    fun switchBackend(backend: RenderingBackend) {
        setBackend(backend.id)
        renderingBackend = backend
        deinitInactive()
    }

    external fun deinit(): Int
    external fun deinitInactive()
    external fun endPlayer(): Int
    external fun getAudioStats(stats: AudioStats)
    external fun getChannelData(ci: ChannelInfo)
    external fun getFormats(): Array<String>
    external fun getFrameInfo(values: FrameInfo)
    external fun getModVars(vars: ModVars)
    external fun getPatternRow(pat: Int, row: Int, rowNotes: ByteArray, rowInstruments: ByteArray, rowFxType: ByteArray, rowFxParm: ByteArray)
    external fun getPatternRows(pat: Int): Int
    external fun getPlayer(parm: Int): Int
    external fun getSampleData(trigger: Boolean, ins: Int, key: Int, period: Int, chn: Int, width: Int, buffer: ByteArray?)
    external fun getVersion(): String
    external fun hasAudioDisconnected(): Boolean
    external fun hasModuleEnded(): Boolean
    external fun init(rate: Int, ms: Int, perfMode: Int, channels: Int, audioApi: Int, flags: Int): Boolean
    external fun loadModuleFd(fd: Int, modInfo: ModInfo): Int
    external fun mute(chn: Int, status: Int): Int
    external fun playAudio(): Int
    external fun releaseModule(): Int
    external fun seek(time: Int): Int
    external fun setBackend(backend: Int)
    external fun setLoopMode(loop: Boolean)
    external fun setPlayer(parm: Int, value: Int): Int
    external fun setPlaying(value: Boolean)
    external fun setResampler(mode: Int): Int
    external fun restartModule(): Int
    external fun stopModule(): Int
    external fun setPosition(pos: Int): Int
    external fun prevPosition(): Int
    external fun nextPosition(): Int
    external fun restartAudio(): Boolean
    external fun stopAudio(): Boolean
    external fun setSequence(seq: Int): Boolean
    external fun startPlayer(rate: Int, format: Int = 0): Int
    external fun testModuleFd(fd: Int, modInfo: ModInfo): Boolean
    external fun time(): Int

    fun testFromFd(context: Context, uri: Uri, modInfo: ModInfo = ModInfo()): Boolean {
        Log.d(TAG, "Testing: $uri")
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            testModuleFd(pfd.detachFd(), modInfo).also { success ->
                if (success) Log.i(TAG, "Test Success: ${modInfo.name} | ${modInfo.type}")
            }
        } ?: false
    }

    fun loadFromFd(context: Context, uri: Uri, modInfo: ModInfo = ModInfo()): Int {
        Log.d(TAG, "Loading: $uri")
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            loadModuleFd(pfd.detachFd(), modInfo).also { result ->
                when (result) {
                    0 -> Log.i(TAG, "Loaded: ${modInfo.name} | ${modInfo.type}")
                    -2 -> Log.w(TAG, "Test failed for $uri")
                    else -> Log.e(TAG, "Load failed: $result for $uri")
                }
            }
        } ?: -1
    }
}
