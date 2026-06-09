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
    val note: Int
) {
    val isEmpty: Boolean get() = note == 0 && instrument == 0 && fxType < 0

    val noteStr: String = when (note) {
        0 -> "---"
        0x80 -> "==="
        0x81 -> "^^^"
        in 1..127 -> (note - 1).let { n -> "${NOTE_NAMES[n % 12]}${n / 12}" }
        else -> "???"
    }

    val instrumentStr: String = if (instrument > 0) HEX2[instrument and 0xFF] else ".."

    val effectTypeChar: Char = if (fxType >= 0) effectChar(fxType) else '.'

    val effectTypeStr: String = effectTypeChar.toString()

    val effectParamStr: String = if (fxType >= 0) HEX2[fxParam and 0xFF] else ".."
}
