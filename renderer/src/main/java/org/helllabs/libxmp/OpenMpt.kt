package org.helllabs.libxmp

import android.content.Context
import android.net.Uri
import android.util.Log
import org.helllabs.libxmp.model.AudioStats
import org.helllabs.libxmp.model.ChannelInfo
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModInfo
import org.helllabs.libxmp.model.ModVars

object OpenMpt {
    private const val TAG = "OpenMPT Library"

    init {
        System.loadLibrary("xmp-jni")
    }

    external fun init(
        rate: Int,
        ms: Int,
        perfMode: Int = Xmp.OBOE_PERFMODE_LOWLATENCY,
        channels: Int = Xmp.OBOE_CHANNELS_STEREO,
        audioApi: Int = Xmp.OBOE_AUDIO_API_UNSPECIFIED,
        flags: Int = 0,
    ): Boolean

    external fun deinit(): Int

    external fun endPlayer(): Int

    external fun getAudioStats(stats: AudioStats)

    external fun getChannelData(ci: ChannelInfo)

    external fun getFormats(): Array<String>

    external fun getFrameInfo(values: FrameInfo)

    external fun getModVars(vars: ModVars)

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

    external fun getVersion(): String

    external fun hasModuleEnded(): Boolean

    external fun loadModuleFd(fd: Int, modInfo: ModInfo): Int

    external fun mute(chn: Int, status: Int): Int

    external fun playAudio(): Int

    external fun releaseModule(): Int

    external fun seek(time: Int): Int

    external fun setLoopMode(loop: Boolean)

    external fun setOpenMptPlaying(value: Boolean)

    external fun setPlayer(parm: Int, value: Int): Int

    external fun setResampler(mode: Int): Int

    external fun setSequence(seq: Int): Boolean

    external fun startPlayer(rate: Int, format: Int = 0): Int

    external fun testModuleFd(fd: Int, modInfo: ModInfo): Boolean

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
                    else -> Log.e(TAG, "Load failed: $result for $uri")
                }
            }
        } ?: -1
    }
}
