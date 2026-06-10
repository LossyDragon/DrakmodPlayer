package com.lossydragon.modplayer.ui.screens.player.components.views

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.player.RenderingBackend
import com.lossydragon.modplayer.player.model.NoteCell
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.modplayer.ui.theme.AppTheme
import io.ktor.http.headers
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.helllabs.libxmp.OpenMpt
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ChannelInfo
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModVars

private val NOTE_NAMES = arrayOf(
    "C-", "C#", "D-", "D#", "E-", "F-",
    "F#", "G-", "G#", "A-", "A#", "B-",
)

private fun noteStr(note: Int): String = when {
    note <= 0 -> "---"
    else -> "${NOTE_NAMES[note % 12]}${note / 12}"
}

@Composable
fun DebugView(
    modifier: Modifier = Modifier,
    state: PlayerUiState,
    renderingBackend: RenderingBackend,
    supportsRawChannelSamples: Boolean,
    isPlaying: Boolean
) {
    Column {
        LazyVerticalStaggeredGrid(
            modifier = modifier.weight(1f),
            columns = StaggeredGridCells.Adaptive(200.dp),
            verticalItemSpacing = 4.dp,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                item { PlaybackInfo(state) }
                item { ModVarsInfo(state) }
                item { FrameInfo(state) }
            },
        )
        HorizontalDivider(Modifier.fillMaxWidth())
        ChannelInfo(
            Modifier.weight(1f),
            state,
            renderingBackend,
            supportsRawChannelSamples,
            isPlaying
        )
    }
}

@Composable
private fun ChannelInfo(
    modifier: Modifier = Modifier,
    state: PlayerUiState,
    renderingBackend: RenderingBackend,
    supportsRawChannelSamples: Boolean,
    isPlaying: Boolean
) {
    val numChannels = state.modVars.chn

    val channelInfoBuffer = remember { ChannelInfo() }
    var channelSnapshot by remember { mutableStateOf(ChannelInfo()) }
    val isPlayingState = rememberUpdatedState(isPlaying)
    val backendState = rememberUpdatedState(renderingBackend)

    LaunchedEffect(numChannels) {
        if (numChannels == 0) return@LaunchedEffect
        while (isActive) {
            if (isPlayingState.value) {
                withContext(Dispatchers.Default) {
                    if (backendState.value == RenderingBackend.LIBXMP) {
                        Xmp.getChannelData(channelInfoBuffer)
                    } else {
                        OpenMpt.getChannelData(channelInfoBuffer)
                    }
                }
                channelSnapshot = ChannelInfo(
                    volumes = channelInfoBuffer.volumes.copyOf(),
                    finalVols = channelInfoBuffer.finalVols.copyOf(),
                    pans = channelInfoBuffer.pans.copyOf(),
                    instruments = channelInfoBuffer.instruments.copyOf(),
                    keys = channelInfoBuffer.keys.copyOf(),
                    periods = channelInfoBuffer.periods.copyOf(),
                    holdVols = channelInfoBuffer.holdVols.copyOf(),
                    positions = channelInfoBuffer.positions.copyOf(),
                    pitchbends = channelInfoBuffer.pitchbends.copyOf(),
                    notes = channelInfoBuffer.notes.copyOf(),
                    samples = channelInfoBuffer.samples.copyOf(),
                )
            }
            delay(50.milliseconds)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (numChannels > 0) {
            DebugHeader("Channel Info")

            val ci = channelSnapshot
            val mono = TextStyle(
                fontSize = 12.sp,
                lineHeight = 11.sp,
                fontFamily = FontFamily.Monospace,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            )
            val hdrColor = MaterialTheme.colorScheme.primary
            val cellColor = MaterialTheme.colorScheme.onSurface

            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Row {
                        DebugCell("Ch", 4, mono, hdrColor)
                        DebugCell("Note", 5, mono, hdrColor)
                        DebugCell("Ins", 4, mono, hdrColor)
                        DebugCell("Smp", 4, mono, hdrColor)
                        DebugCell("Vol", 4, mono, hdrColor)
                        DebugCell("FVol", 5, mono, hdrColor)
                        DebugCell("Pan", 4, mono, hdrColor)
                        DebugCell("Period", 7, mono, hdrColor)
                        DebugCell("Pos", 9, mono, hdrColor)
                        DebugCell("Bend", 6, mono, hdrColor)
                    }
                    for (i in 0 until numChannels) {
                        Row {
                            DebugCell("%2d".format(i + 1), 4, mono, cellColor)
                            DebugCell(noteStr(ci.notes[i]), 5, mono, cellColor)
                            DebugCell("%3d".format(ci.instruments[i]), 4, mono, cellColor)
                            DebugCell("%3d".format(ci.samples[i]), 4, mono, cellColor)
                            DebugCell("%3d".format(ci.volumes[i]), 4, mono, cellColor)
                            DebugCell("%4d".format(ci.finalVols[i]), 5, mono, cellColor)
                            DebugCell("%3d".format(ci.pans[i]), 4, mono, cellColor)
                            DebugCell("%6d".format(ci.periods[i]), 7, mono, cellColor)
                            DebugCell("%8d".format(ci.positions[i]), 9, mono, cellColor)
                            DebugCell("%5d".format(ci.pitchbends[i]), 6, mono, cellColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameInfo(state: PlayerUiState) {
    val fi = state.frameInfo
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        DebugHeader("FrameInfo")
        DebugRow("Pos/Pat/Row", "${fi.pos} / ${fi.pattern} / ${fi.row}")
        DebugRow("Speed/BPM", "${fi.speed} / ${fi.bpm}")
        DebugRow("Time/Total", "${fi.time} / ${fi.totalTime}")
        DebugRow("Loop/Seq", "${fi.loopCount} / ${fi.sequence}")
        DebugRow("VChn used", "${fi.virtUsed} / ${fi.virtChannels}")
    }
}

@Composable
private fun PlaybackInfo(state: PlayerUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
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
    }
}

@Composable
private fun ModVarsInfo(state: PlayerUiState) {
    val mv = state.modVars
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        DebugHeader("ModVars")
        DebugRow("Name", mv.modName.ifBlank { "—" })
        DebugRow("Type", mv.modType.ifBlank { "—" })
        DebugRow("Pat/Chn/Ins", "${mv.pat} / ${mv.chn} / ${mv.ins}")
        DebugRow("Smp/Spd/BPM", "${mv.smp} / ${mv.spd} / ${mv.bpm}")
        DebugRow("Len/Rst/Gvl", "${mv.len} / ${mv.rst} / ${mv.gvl}")
        DebugRow("Sequences", mv.miNumSequences.toString())
        if (mv.miComment.isNotBlank()) DebugRow("Comment", mv.miComment.take(32))
    }
}

@Composable
private fun DebugCell(
    text: String,
    minChars: Int,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color
) {
    Text(
        text = text.padEnd(minChars),
        style = style,
        color = color,
        modifier = Modifier.padding(end = 4.dp),
    )
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
    val state = PlayerUiState(
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
            state = state,
            renderingBackend = RenderingBackend.LIBXMP,
            supportsRawChannelSamples = true,
            isPlaying = true,
        )
    }
}
