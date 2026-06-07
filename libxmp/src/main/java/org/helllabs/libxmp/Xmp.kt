package org.helllabs.libxmp

import android.content.Context
import android.net.Uri
import android.util.Log
import org.helllabs.libxmp.model.AudioStats
import org.helllabs.libxmp.model.ChannelInfo
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModInfo
import org.helllabs.libxmp.model.ModVars
import kotlin.math.abs

/**
 * https://github.com/libxmp/libxmp/blob/master/docs/libxmp.rst
 */
object Xmp {

    private const val TAG = "XMP Library"

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

    /* error codes */
    enum class XmpResult(val code: Int) {
        OK(0),
        END(1),
        INTERNAL(2),
        FORMAT(3),
        LOAD(4),
        DEPACK(5),
        SYSTEM(6),
        INVALID(7),
        STATE(8),
        UNKNOWN(-1);

        companion object {
            fun fromCode(code: Int): XmpResult =
                entries.firstOrNull { it.code == abs(code) } ?: UNKNOWN
        }
    }

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

    init {
        System.loadLibrary("xmp-jni")
    }

    external fun loadModuleFd(fd: Int, modInfo: ModInfo): Int

    external fun deinit(): Int

    external fun dropAudio()

    external fun endPlayer(): Int

    external fun fillBuffer(loop: Boolean): Int

    external fun getInfo(values: FrameInfo)

    external fun getPlayer(parm: Int): Int

    external fun hasFreeBuffer(): Boolean

    external fun init(
        rate: Int,
        ms: Int,
        perfMode: Int = OBOE_PERFMODE_LOWLATENCY,
        channels: Int = OBOE_CHANNELS_STEREO,
        audioApi: Int = OBOE_AUDIO_API_UNSPECIFIED,
        flags: Int = 0,
    ): Boolean

    external fun mute(chn: Int, status: Int): Int

    external fun playAudio(): Int

    external fun releaseModule(): Int

    external fun restartAudio(): Boolean

    external fun seek(time: Int): Int

    private external fun setPlayerNative(parm: Int, value: Int): Int

    external fun startPlayer(rate: Int, format: Int = 0): Int

    external fun stopAudio(): Boolean

    external fun stopModule(): Int

    external fun testModuleFd(fd: Int, modInfo: ModInfo): Boolean

    external fun time(): Int

    external fun getChannelData(ci: ChannelInfo)

    external fun getComment(): ByteArray

    external fun getFormats(): Array<String>

    external fun getInstruments(): Array<String>

    external fun getLoopCount(): Int

    private external fun getMaxSequences(): Int

    external fun getModName(): String

    external fun getModType(): String

    external fun getModVars(vars: ModVars)

    fun setPlayer(parm: Int, value: Int) {
        val res = setPlayerNative(parm, value)
        Log.d(TAG, "setPlayer($parm, $value) = ${XmpResult.fromCode(res)}($res)")
    }

    external fun getPatternRow(
        pat: Int,
        row: Int,
        rowNotes: ByteArray,
        rowInstruments: ByteArray,
        rowFxType: ByteArray,
        rowFxParm: ByteArray
    )

    external fun getPatternRows(pat: Int): Int

    external fun getSampleData(
        trigger: Boolean,
        ins: Int,
        key: Int,
        period: Int,
        chn: Int,
        width: Int,
        buffer: ByteArray?
    )

    external fun getSeqVars(): IntArray

    external fun getVersion(): String

    external fun nextPosition(): Int

    external fun prevPosition(): Int

    external fun restartModule(): Int

    external fun setPosition(num: Int): Int

    external fun setSequence(seq: Int): Boolean

    external fun getAudioStats(stats: AudioStats)

    external fun setExpectSilence(value: Boolean)

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
