package org.helllabs.libxmp.model

import androidx.compose.runtime.Stable

/**
 * @see [org.helllabs.libxmp.Xmp.getModVars]
 */
@Stable
data class ModVars(
    val name: String = "",  /* Module title */
    val type: String = "",  /* Module format */
    val pat: Int = 0,   /* Number of patterns */
    val trk: Int = 0,   /* Number of tracks */
    val chn: Int = 0,   /* Tracks per pattern */
    val ins: Int = 0,   /* Number of instruments */
    val smp: Int = 0,   /* Number of samples */
    val spd: Int = 0,   /* Initial speed */
    val bpm: Int = 0,   /* Initial BPM */
    val len: Int = 0,   /* Module length in patterns */
    val rst: Int = 0,   /* Restart position */
    val gvl: Int = 0,   /* Global volume */
    val miNumSequences: Int = 0,    /* Number of valid sequences */
    val miComment: String = "",     /* Comment text, if any */
    val seqData: Array<Sequence> = emptyArray(),
    val instruments: Array<String> = emptyArray(),
) {
    val modName: String get() = name.trim()
    val modType: String get() = type.trim()

    fun sequenceDuration(idx: Int): Int = seqData.getOrNull(idx)?.duration ?: 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ModVars

        if (pat != other.pat) return false
        if (trk != other.trk) return false
        if (chn != other.chn) return false
        if (ins != other.ins) return false
        if (smp != other.smp) return false
        if (spd != other.spd) return false
        if (bpm != other.bpm) return false
        if (len != other.len) return false
        if (rst != other.rst) return false
        if (gvl != other.gvl) return false
        if (miNumSequences != other.miNumSequences) return false
        if (name != other.name) return false
        if (type != other.type) return false
        if (miComment != other.miComment) return false
        if (!seqData.contentEquals(other.seqData)) return false
        if (!instruments.contentEquals(other.instruments)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pat
        result = 31 * result + trk
        result = 31 * result + chn
        result = 31 * result + ins
        result = 31 * result + smp
        result = 31 * result + spd
        result = 31 * result + bpm
        result = 31 * result + len
        result = 31 * result + rst
        result = 31 * result + gvl
        result = 31 * result + miNumSequences
        result = 31 * result + name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + miComment.hashCode()
        result = 31 * result + seqData.contentHashCode()
        result = 31 * result + instruments.contentHashCode()
        return result
    }
}
