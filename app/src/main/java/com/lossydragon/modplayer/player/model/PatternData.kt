package com.lossydragon.modplayer.player.model

import androidx.compose.runtime.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PatternData(
    val cells: ImmutableList<ImmutableList<NoteCell>> = persistentListOf(),
    val numChannels: Int = 0,
    val numRows: Int = 0,
    val patternIndex: Int = -1
)

private val NOTE_NAMES = arrayOf(
    "C-", "C#", "D-", "D#", "E-", "F-",
    "F#", "G-", "G#", "A-", "A#", "B-",
)

/** Notes 1..127 by value; index 0 is "no note". */
private val NOTE_STR = Array(128) { note ->
    if (note == 0) "---" else (note - 1).let { n -> "${NOTE_NAMES[n % 12]}${n / 12}" }
}

private val HEX2 = Array(256) { "%02X".format(it) }

/** Effects 0..35 as 0-9, A-Z. */
private val EFFECT_STR = Array(36) { (if (it < 10) '0' + it else 'A' + (it - 10)).toString() }

private fun effectStr(effect: Int): String = when {
    effect < 0 -> "."
    effect < EFFECT_STR.size -> EFFECT_STR[effect]
    else -> "?"
}

@Immutable
data class NoteCell(
    val fxParam: Int,
    val fxType: Int,
    val instrument: Int,
    val note: Int,
    val fx2Param: Int = -1, // secondary effect: f2t/f2p (libxmp), volume column (openmpt)
    val fx2Type: Int = -1
) {
    val noteStr: String = when (note) {
        0x80 -> "==="
        0x81 -> "^^^"
        in NOTE_STR.indices -> NOTE_STR[note]
        else -> "???"
    }

    val instrumentStr: String = if (instrument > 0) HEX2[instrument and 0xFF] else ".."

    val effectTypeStr: String = effectStr(fxType)

    val effectParamStr: String = if (fxType >= 0) HEX2[fxParam and 0xFF] else ".."

    val effect2TypeStr: String = effectStr(fx2Type)

    val effect2ParamStr: String = if (fx2Type >= 0) HEX2[fx2Param and 0xFF] else ".."
}
