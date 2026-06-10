package com.lossydragon.modplayer.player

import android.content.Context
import android.net.Uri
import org.helllabs.libxmp.OpenMpt
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.AudioStats
import org.helllabs.libxmp.model.ChannelInfo
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModInfo
import org.helllabs.libxmp.model.ModVars

interface NativeModuleBackend {
    val renderingBackend: RenderingBackend
    val supportsRawChannelSamples: Boolean

    fun deinit(): Int
    fun endPlayer(): Int
    fun getAudioStats(stats: AudioStats)
    fun getChannelData(ci: ChannelInfo)
    fun getFormats(): Array<String>
    fun getFrameInfo(values: FrameInfo)
    fun getModVars(vars: ModVars)
    fun getPatternRow(
        pat: Int,
        row: Int,
        rowNotes: ByteArray,
        rowInstruments: ByteArray,
        rowFxType: ByteArray,
        rowFxParm: ByteArray
    )
    fun getPatternRows(pat: Int): Int
    fun getSampleData(
        trigger: Boolean,
        ins: Int,
        key: Int,
        period: Int,
        chn: Int,
        width: Int,
        buffer: ByteArray?
    )
    fun hasModuleEnded(): Boolean
    fun init(rate: Int, ms: Int, perfMode: Int, channels: Int, audioApi: Int, flags: Int): Boolean
    fun loadFromFd(context: Context, uri: Uri, modInfo: ModInfo = ModInfo()): Int
    fun mute(chn: Int, status: Int): Int
    fun playAudio(): Int
    fun releaseModule(): Int
    fun seek(time: Int): Int
    fun setLoopMode(loop: Boolean)
    fun setPlayer(parm: Int, value: Int): Int
    fun setPlaying(value: Boolean)
    fun setResampler(mode: ResamplerMode): Int
    fun setSequence(seq: Int): Boolean
    fun startPlayer(rate: Int, format: Int = 0): Int
    fun testFromFd(context: Context, uri: Uri, modInfo: ModInfo = ModInfo()): Boolean
}

object XmpBackend : NativeModuleBackend {
    override val renderingBackend = RenderingBackend.LIBXMP
    override val supportsRawChannelSamples = true
    override fun deinit() = Xmp.deinit()
    override fun endPlayer() = Xmp.endPlayer()
    override fun getAudioStats(stats: AudioStats) = Xmp.getAudioStats(stats)
    override fun getChannelData(ci: ChannelInfo) = Xmp.getChannelData(ci)
    override fun getFormats() = Xmp.getFormats()
    override fun getFrameInfo(values: FrameInfo) = Xmp.getFrameInfo(values)
    override fun getModVars(vars: ModVars) = Xmp.getModVars(vars)
    override fun getPatternRow(
        pat: Int,
        row: Int,
        rowNotes: ByteArray,
        rowInstruments: ByteArray,
        rowFxType: ByteArray,
        rowFxParm: ByteArray
    ) = Xmp.getPatternRow(pat, row, rowNotes, rowInstruments, rowFxType, rowFxParm)
    override fun getPatternRows(pat: Int) = Xmp.getPatternRows(pat)
    override fun getSampleData(
        trigger: Boolean,
        ins: Int,
        key: Int,
        period: Int,
        chn: Int,
        width: Int,
        buffer: ByteArray?
    ) = Xmp.getSampleData(trigger, ins, key, period, chn, width, buffer)
    override fun hasModuleEnded() = Xmp.hasModuleEnded()
    override fun init(rate: Int, ms: Int, perfMode: Int, channels: Int, audioApi: Int, flags: Int) =
        Xmp.init(rate, ms, perfMode, channels, audioApi, flags)
    override fun loadFromFd(context: Context, uri: Uri, modInfo: ModInfo) =
        Xmp.loadFromFd(context, uri, modInfo)
    override fun mute(chn: Int, status: Int) = Xmp.mute(chn, status)
    override fun playAudio() = Xmp.playAudio()
    override fun releaseModule() = Xmp.releaseModule()
    override fun seek(time: Int) = Xmp.seek(time)
    override fun setLoopMode(loop: Boolean) = Xmp.setLoopMode(loop)
    override fun setPlayer(parm: Int, value: Int) = Xmp.setPlayer(parm, value)
    override fun setPlaying(value: Boolean) = Xmp.setXmpPlaying(value)
    override fun setResampler(mode: ResamplerMode): Int {
        val interp = when (mode) {
            ResamplerMode.NEAREST -> Xmp.XMP_INTERP_NEAREST

            ResamplerMode.LINEAR -> Xmp.XMP_INTERP_LINEAR

            ResamplerMode.CUBIC -> Xmp.XMP_INTERP_SPLINE

            ResamplerMode.OPENMPT_AMIGA_A500,
            ResamplerMode.OPENMPT_AMIGA_A1200 -> Xmp.XMP_INTERP_LINEAR
        }
        return Xmp.setPlayer(Xmp.XMP_PLAYER_INTERP, interp)
    }
    override fun setSequence(seq: Int) = Xmp.setSequence(seq)
    override fun startPlayer(rate: Int, format: Int) = Xmp.startPlayer(rate, format)
    override fun testFromFd(context: Context, uri: Uri, modInfo: ModInfo) =
        Xmp.testFromFd(context, uri, modInfo)
}

object OpenMptBackend : NativeModuleBackend {
    override val renderingBackend = RenderingBackend.OPENMPT
    override val supportsRawChannelSamples = true
    override fun deinit() = OpenMpt.deinit()
    override fun endPlayer() = OpenMpt.endPlayer()
    override fun getAudioStats(stats: AudioStats) = OpenMpt.getAudioStats(stats)
    override fun getChannelData(ci: ChannelInfo) = OpenMpt.getChannelData(ci)
    override fun getFormats() = OpenMpt.getFormats()
    override fun getFrameInfo(values: FrameInfo) = OpenMpt.getFrameInfo(values)
    override fun getModVars(vars: ModVars) = OpenMpt.getModVars(vars)
    override fun getPatternRow(
        pat: Int,
        row: Int,
        rowNotes: ByteArray,
        rowInstruments: ByteArray,
        rowFxType: ByteArray,
        rowFxParm: ByteArray
    ) = OpenMpt.getPatternRow(pat, row, rowNotes, rowInstruments, rowFxType, rowFxParm)
    override fun getPatternRows(pat: Int) = OpenMpt.getPatternRows(pat)
    override fun getSampleData(
        trigger: Boolean,
        ins: Int,
        key: Int,
        period: Int,
        chn: Int,
        width: Int,
        buffer: ByteArray?
    ) = OpenMpt.getSampleData(trigger, ins, key, period, chn, width, buffer)
    override fun hasModuleEnded() = OpenMpt.hasModuleEnded()
    override fun init(rate: Int, ms: Int, perfMode: Int, channels: Int, audioApi: Int, flags: Int) =
        OpenMpt.init(rate, ms, perfMode, channels, audioApi, flags)
    override fun loadFromFd(context: Context, uri: Uri, modInfo: ModInfo) =
        OpenMpt.loadFromFd(context, uri, modInfo)
    override fun mute(chn: Int, status: Int) = OpenMpt.mute(chn, status)
    override fun playAudio() = OpenMpt.playAudio()
    override fun releaseModule() = OpenMpt.releaseModule()
    override fun seek(time: Int) = OpenMpt.seek(time)
    override fun setLoopMode(loop: Boolean) = OpenMpt.setLoopMode(loop)
    override fun setPlayer(parm: Int, value: Int) = OpenMpt.setPlayer(parm, value)
    override fun setPlaying(value: Boolean) = OpenMpt.setOpenMptPlaying(value)
    override fun setResampler(mode: ResamplerMode) = OpenMpt.setResampler(mode.id)
    override fun setSequence(seq: Int) = OpenMpt.setSequence(seq)
    override fun startPlayer(rate: Int, format: Int) = OpenMpt.startPlayer(rate, format)
    override fun testFromFd(context: Context, uri: Uri, modInfo: ModInfo) =
        OpenMpt.testFromFd(context, uri, modInfo)
}

fun RenderingBackend.nativeBackend(): NativeModuleBackend = when (this) {
    RenderingBackend.OPENMPT -> OpenMptBackend
    RenderingBackend.LIBXMP -> XmpBackend
}
