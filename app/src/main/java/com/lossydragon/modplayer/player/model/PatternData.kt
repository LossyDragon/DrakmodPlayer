package com.lossydragon.modplayer.player.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

fun emptyPatternData() = PatternData(
    cells = persistentListOf(persistentListOf()),
    numChannels = 0,
    numRows = 0,
    patternIndex = -1,
)

@Immutable
data class PatternData(
    val cells: ImmutableList<ImmutableList<NoteCell>>,
    val numChannels: Int,
    val numRows: Int,
    val patternIndex: Int
)

@Immutable
data class NoteCell(
    val fxParam: Int,
    val fxType: Int,
    val instrument: Int,
    val note: Int
) {
    val isEmpty: Boolean
        get() = note == 0 && instrument == 0 && fxType < 0

    val noteStr: String = when (note) {
        0 -> "---"

        0x80 -> "==="

        0x81 -> "^^^"

        in 1..127 -> {
            val n = note - 1
            "${NOTE_NAMES[n % 12]}${n / 12}"
        }

        else -> "???"
    }

    val instrumentStr: String =
        if (instrument > 0) "%02X".format(instrument) else ".."

    val effectTypeChar: String =
        if (fxType >= 0) effectChar(fxType).toString() else "."

    val effectParamStr: String =
        if (fxType >= 0) "%02X".format(fxParam) else ".."

    // val effectStr: String = effectTypeChar + effectParamStr

    companion object {
        private val NOTE_NAMES = arrayOf(
            "C-", "C#", "D-", "D#", "E-", "F-",
            "F#", "G-", "G#", "A-", "A#", "B-",
        )

        private fun effectChar(effect: Int): Char = when {
            effect in 0..9 -> '0' + effect
            effect in 10..35 -> 'A' + (effect - 10)
            else -> '?'
        }
    }
}
