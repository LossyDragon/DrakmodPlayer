package com.lossydragon.modplayer.ui.screens.player.components.views

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.player.model.NoteCell
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.toImmutableList
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModVars

@Composable
fun DebugView(
    modifier: Modifier = Modifier,
    state: PlayerUiState,
    patternData: PatternData
) {
    val fi = state.frameInfo
    val mv = state.modVars
    val patternTextStyle = TextStyle(
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontFamily = FontFamily.Monospace,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                DebugHeader("Playback")
                DebugRow("Status", state.status.name)
                DebugRow(
                    "Module",
                    state.currentModule?.let { it.moduleName.ifBlank { it.filename } } ?: "—"
                )
                DebugRow("Queue", "${state.currentQueueIndex + 1} / ${state.queue.size}")
                DebugRow("Sequence", state.currentSequence.toString())
                DebugRow("Repeat", state.repeatMode.toString())
                DebugRow("Shuffle", state.isShuffle.toString())
                DebugRow("All seqs", state.playAllSequences.toString())

                Spacer(Modifier.height(6.dp))
                DebugHeader("ModVars")
                DebugRow("Name", mv.name.ifBlank { "—" })
                DebugRow("Type", mv.type.ifBlank { "—" })
                DebugRow("Pat/Chn/Ins", "${mv.pat} / ${mv.chn} / ${mv.ins}")
                DebugRow("Smp/Spd/BPM", "${mv.smp} / ${mv.spd} / ${mv.bpm}")
                DebugRow("Len/Rst/Gvl", "${mv.len} / ${mv.rst} / ${mv.gvl}")
                DebugRow("Sequences", mv.miNumSequences.toString())
                if (mv.miComment.isNotBlank()) DebugRow("Comment", mv.miComment.take(32))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                DebugHeader("FrameInfo")
                DebugRow("Pos/Pat/Row", "${fi.pos} / ${fi.pattern} / ${fi.row}")
                DebugRow("Speed/BPM", "${fi.speed} / ${fi.bpm}")
                DebugRow("Time/Total", "${fi.time} / ${fi.totalTime}")
                DebugRow("Loop/Seq", "${fi.loopCount} / ${fi.sequence}")
                DebugRow("VChn used", "${fi.virtUsed} / ${fi.virtChannels}")
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            DebugHeader(
                "Pat #${patternData.patternIndex}  ${patternData.numRows}r × ${patternData.numChannels}ch"
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    patternData.cells.forEachIndexed { rowIdx, row ->
                        val isCurrent = patternData.patternIndex == fi.pattern && rowIdx == fi.row
                        Row {
                            Text(
                                text = "%02X ".format(rowIdx),
                                style = patternTextStyle,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary

                                    rowIdx % 4 == 0 -> MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = 0.8f
                                    )

                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                },
                            )
                            row.forEach { cell ->
                                Text(
                                    text = "${cell.noteStr}${cell.instrumentStr}" +
                                        "${cell.effectTypeChar}${cell.effectParamStr} ",
                                    style = patternTextStyle,
                                    color = when {
                                        isCurrent -> MaterialTheme.colorScheme.primary

                                        cell.isEmpty -> MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.2f
                                        )

                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DebugRow(key: String, value: String) {
    Row {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(80.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(showBackground = true, widthDp = 480, heightDp = 640)
@Composable
private fun Preview() {
    val fakeCells = (0 until 16).map { row ->
        (0 until 8).map { ch ->
            when {
                row % 4 == 0 && ch % 2 == 0 -> NoteCell(
                    note = 48 + ch,
                    instrument = ch + 1,
                    fxType = 0,
                    fxParam = 0
                )

                row == 2 && ch == 1 -> NoteCell(
                    note = 52,
                    instrument = 2,
                    fxType = 10,
                    fxParam = 0xF0
                )

                row == 6 && ch == 5 -> NoteCell(
                    note = 0x80,
                    instrument = 0,
                    fxType = -1,
                    fxParam = 0
                )

                else -> NoteCell(note = 0, instrument = 0, fxType = -1, fxParam = 0)
            }
        }.toImmutableList()
    }.toImmutableList()

    val fakeState = PlayerUiState(
        status = PlaybackStatus.PLAYING,
        modVars = ModVars(
            name = "Space Debris",
            type = "Extended Module",
            pat = 24, chn = 8, ins = 18, smp = 32,
            bpm = 125, spd = 6, len = 24, rst = 0, gvl = 64,
            miNumSequences = 2,
            miComment = "Made with FastTracker II",
        ),
        frameInfo = FrameInfo(
            pos = 2, pattern = 3, row = 6,
            speed = 6, bpm = 125,
            time = 32450, totalTime = 186000,
            loopCount = 0, sequence = 0,
            virtChannels = 8, virtUsed = 3,
        ),
        currentSequence = 0,
        currentQueueIndex = 2,
        isShuffle = true,
    )

    AppTheme {
        DebugView(
            modifier = Modifier.fillMaxSize(),
            state = fakeState,
            patternData = PatternData(
                cells = fakeCells,
                numChannels = 8,
                numRows = 16,
                patternIndex = 3,
            ),
        )
    }
}
