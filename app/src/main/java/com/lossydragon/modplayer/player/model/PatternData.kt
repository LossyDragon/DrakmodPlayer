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

private val HEX2 = Array(256) { "%02X".format(it) }

private fun effectChar(effect: Int): Char = when (effect) {
    in 0..9 -> '0' + effect
    in 10..35 -> 'A' + (effect - 10)
    else -> '?'
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
    val isEmpty: Boolean get() = note == 0 && instrument == 0 && fxType < 0 && fx2Type < 0

    val noteStr: String = when (note) {
        0 -> "---"
        0x80 -> "==="
        0x81 -> "^^^"
        in 1..127 -> (note - 1).let { n -> "${NOTE_NAMES[n % 12]}${n / 12}" }
        else -> "???"
    }

    val instrumentStr: String = if (instrument > 0) HEX2[instrument and 0xFF] else ".."

    val effectTypeStr: String = if (fxType >= 0) effectChar(fxType).toString() else "."

    val effectParamStr: String = if (fxType >= 0) HEX2[fxParam and 0xFF] else ".."

    val effect2TypeStr: String = if (fx2Type >= 0) effectChar(fx2Type).toString() else "."

    val effect2ParamStr: String = if (fx2Type >= 0) HEX2[fx2Param and 0xFF] else ".."
}
