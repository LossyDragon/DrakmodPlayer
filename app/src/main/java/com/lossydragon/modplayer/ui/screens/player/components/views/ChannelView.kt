package com.lossydragon.modplayer.ui.screens.player.components.views

import androidx.collection.MutableLongObjectMap
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.tooling.preview.datasource.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.renderer.Player
import com.lossydragon.renderer.model.ChannelInfo
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WAVEFORM_SAMPLES = 256

private val HAIRLINE = Stroke(1f)

// Packed Long key: [type:32][extra:32]
// type: 0=ch number, 1=instrument, 2="L [", 3="] R", 4=muted instrument
private fun textKey(type: Int, extra: Int): Long =
    (type.toLong() shl 32) or (extra.toLong() and 0xFFFFFFFFL)

data class ChannelViewColors(
    val background: Color,
    val channelNumber: Color,
    val rowSeparator: Color,
    val waveformBackground: Color,
    val waveformBorder: Color,
    val waveformBaseline: Color,
    val waveformLine: Color,
    val instrumentText: Color,
    val mutedText: Color,
    val volumeBarTrack: Color,
    val volumeBar: Color,
    val finalVolumeBar: Color,
    val panRail: Color,
    val panCursor: Color
)

@Composable
fun rememberDefaultChannelViewColors(): ChannelViewColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        ChannelViewColors(
            background = scheme.surface,
            channelNumber = scheme.onSurface,
            rowSeparator = scheme.outlineVariant,
            waveformBackground = scheme.surfaceContainerLow,
            waveformBorder = scheme.outline,
            waveformBaseline = scheme.outlineVariant,
            waveformLine = scheme.primary,
            instrumentText = scheme.secondary,
            mutedText = scheme.error,
            volumeBarTrack = scheme.surfaceContainerHighest,
            volumeBar = scheme.primary,
            finalVolumeBar = scheme.onSurfaceVariant,
            panRail = scheme.outline,
            panCursor = scheme.tertiary,
        )
    }
}

private class FlingJob {
    var job: Job? = null
}

@Composable
fun ChannelView(
    numChannels: Int,
    instrumentNames: ImmutableList<String>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    colors: ChannelViewColors = rememberDefaultChannelViewColors()
) {
    val view = LocalView.current
    val resources = LocalResources.current

    val ci = remember(numChannels) { ChannelInfo() }
    val drawCi = remember(numChannels) { ChannelInfo() }
    // Filled on Dispatchers.Default, then copied into drawWave on the main thread so the Canvas
    // never reads a half-written buffer.
    val liveWave = remember(numChannels) { Array(numChannels) { ByteArray(WAVEFORM_SAMPLES) } }
    val drawWave = remember(numChannels) { Array(numChannels) { ByteArray(WAVEFORM_SAMPLES) } }
    val prevKey = remember(numChannels) { IntArray(numChannels) }
    val prevInstrument = remember(numChannels) { IntArray(numChannels) { -1 } }

    val muteArr = remember(numChannels) { IntArray(numChannels) }
    var muteVersion by remember { mutableIntStateOf(0) }
    var drawTick by remember { mutableIntStateOf(0) }
    var soloChannel by remember { mutableIntStateOf(-1) }

    val isPlayingState = rememberUpdatedState(isPlaying)
    var zoom by remember { mutableFloatStateOf(1f) }
    // Drag writes this directly; only a fling runs a coroutine. Read in the draw pass only.
    val scrollY = remember { mutableFloatStateOf(0f) }
    val fling = remember { FlingJob() }
    val decay = remember { exponentialDecay<Float>() }
    val coroutineScope = rememberCoroutineScope()

    fun toggleMute(ch: Int) {
        muteArr[ch] = if (muteArr[ch] == 0) 1 else 0
        Player.mute(ch, muteArr[ch])
        muteVersion++
    }

    fun toggleSolo(ch: Int) {
        if (soloChannel < 0) {
            for (i in 0 until numChannels) {
                muteArr[i] = if (i != ch) 1 else 0
                Player.mute(i, muteArr[i])
            }
            soloChannel = ch
        } else {
            for (i in 0 until numChannels) {
                muteArr[i] = 0
                Player.mute(i, 0)
            }
            soloChannel = -1
        }
        muteVersion++
    }

    LaunchedEffect(numChannels) {
        zoom = 1f
        scrollY.floatValue = 0f
        soloChannel = -1

        if (numChannels == 0) return@LaunchedEffect

        if (!view.isInEditMode) {
            withContext(Dispatchers.Default) {
                for (i in 0 until numChannels) muteArr[i] = Player.mute(i, -1)
            }
        }

        while (isActive) {
            // Once even when paused, so a view opened then shows the channels as they stand.
            if (isPlayingState.value || drawTick == 0) {
                withContext(Dispatchers.Default) {
                    Player.getChannelData(ci)
                    for (i in 0 until numChannels) {
                        val period = ci.periods[i]
                        val volume = ci.volumes[i]
                        val finalVol = ci.finalVols[i]
                        val key = ci.keys[i]
                        val ins = ci.instruments[i]
                        if (period <= 0 || (volume == 0 && finalVol == 0)) {
                            liveWave[i].fill(0)
                            continue
                        }
                        val trigger = key >= 0 && (key != prevKey[i] || ins != prevInstrument[i])
                        if (key >= 0) prevKey[i] = key
                        prevInstrument[i] = ins
                        Player.getSampleData(
                            trigger = trigger,
                            ins = ins,
                            key = prevKey[i],
                            period = period,
                            chn = i,
                            width = WAVEFORM_SAMPLES,
                            buffer = liveWave[i],
                        )
                    }
                }
                for (i in 0 until numChannels) liveWave[i].copyInto(drawWave[i])
                ci.periods.copyInto(drawCi.periods)
                ci.volumes.copyInto(drawCi.volumes)
                ci.finalVols.copyInto(drawCi.finalVols)
                ci.pans.copyInto(drawCi.pans)
                ci.instruments.copyInto(drawCi.instruments)
                drawTick++
            }
            delay(20.milliseconds)
        }
    }

    val density = LocalDensity.current
    val baseRowHeight = with(density) { 52.dp.toPx() }
    val baseChNumWidth = with(density) { 26.dp.toPx() }
    val baseWaveWidth = with(density) { 96.dp.toPx() }
    val basePad = with(density) { 4.dp.toPx() }

    val fontSize = 11.sp * zoom
    val textMeasurer = rememberTextMeasurer()
    val textCache = remember(fontSize, instrumentNames) {
        MutableLongObjectMap<TextLayoutResult>(64)
    }

    val textStyle = remember(fontSize) {
        TextStyle(fontSize = fontSize, fontFamily = FontFamily.Monospace)
    }
    val waveStroke = remember(zoom) {
        Stroke((1.5f * zoom).coerceAtLeast(1f), cap = StrokeCap.Butt)
    }

    val rowHeight = baseRowHeight * zoom
    val accessibilityActions = remember(numChannels, muteVersion, soloChannel) {
        buildList {
            for (ch in 0 until numChannels) {
                val label = if (muteArr[ch] == 1) {
                    resources.getString(R.string.desc_unmute_channel, ch + 1)
                } else {
                    resources.getString(R.string.desc_mute_channel, ch + 1)
                }
                add(
                    CustomAccessibilityAction(label) {
                        toggleMute(ch)
                        true
                    }
                )
            }
            if (soloChannel >= 0) {
                add(
                    CustomAccessibilityAction(resources.getString(R.string.desc_unsolo)) {
                        toggleSolo(soloChannel)
                        true
                    }
                )
            } else {
                for (ch in 0 until numChannels) {
                    val label = resources.getString(R.string.desc_solo_channel, ch + 1)
                    add(
                        CustomAccessibilityAction(label) {
                            toggleSolo(ch)
                            true
                        }
                    )
                }
            }
        }
    }

    val waveformPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .semantics { customActions = accessibilityActions }
            .pointerInput(numChannels) {
                val rowH = 52.dp.toPx()
                fun maxScroll() = (rowH * zoom * numChannels - size.height).coerceAtLeast(0f)
                val vt = VelocityTracker()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    fling.job?.cancel()
                    vt.resetTracking()
                    var singleFinger = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) {
                            if (singleFinger) {
                                val velocity = -vt.calculateVelocity().y
                                fling.job = coroutineScope.launch {
                                    val max = maxScroll()
                                    AnimationState(
                                        initialValue = scrollY.floatValue,
                                        initialVelocity = velocity,
                                    ).animateDecay(decay) {
                                        val clamped = value.coerceIn(0f, max)
                                        scrollY.floatValue = clamped
                                        if (clamped != value) cancelAnimation()
                                    }
                                }
                            }
                            break
                        }
                        if (active.size == 1) {
                            val change = active[0]
                            vt.addPointerInputChange(change)
                            val dy = change.position.y - change.previousPosition.y
                            if (dy != 0f) {
                                scrollY.floatValue =
                                    (scrollY.floatValue - dy).coerceIn(0f, maxScroll())
                                change.consume()
                            }
                            singleFinger = true
                        } else {
                            zoom = (zoom * event.calculateZoom()).coerceIn(0.5f, 3f)
                            val pan = event.calculatePan()
                            scrollY.floatValue =
                                (scrollY.floatValue - pan.y).coerceIn(0f, maxScroll())
                            event.changes.forEach { it.consume() }
                            singleFinger = false
                            vt.resetTracking()
                        }
                    }
                }
            }
            .pointerInput(numChannels) {
                val rowH = 52.dp.toPx()
                val chNumW = 26.dp.toPx()
                val waveW = 96.dp.toPx()
                fun channelAt(offset: Offset): Int {
                    val ch = ((offset.y + scrollY.floatValue) / (rowH * zoom)).toInt()
                    val waveRange = (chNumW * zoom)..((chNumW + waveW) * zoom)
                    return if (ch in 0 until numChannels && offset.x in waveRange) ch else -1
                }
                detectTapGestures(
                    onTap = { offset ->
                        val ch = channelAt(offset)
                        if (ch >= 0) toggleMute(ch)
                    },
                    onLongPress = { offset ->
                        val ch = channelAt(offset)
                        if (ch >= 0) {
                            toggleSolo(ch)
                        } else {
                            zoom = 1f
                            scrollY.floatValue = 0f
                        }
                    },
                )
            }
    ) {
        if ((drawTick == 0 && !view.isInEditMode) || numChannels == 0) return@Canvas

        val scroll = scrollY.floatValue
        val pad = basePad * zoom
        val chNumWidth = baseChNumWidth * zoom
        val waveWidth = baseWaveWidth * zoom
        val infoX = chNumWidth + waveWidth + pad * 2

        drawRect(color = colors.background, size = size)

        val lLayout = textCache.getOrPut(textKey(2, 0)) { textMeasurer.measure("L [", textStyle) }
        val rLayout = textCache.getOrPut(textKey(3, 0)) { textMeasurer.measure("] R", textStyle) }

        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            for (ch in 0 until numChannels) {
                val rowTop = ch * rowHeight - scroll
                if (rowTop + rowHeight < 0f || rowTop > size.height) continue

                val muted = muteArr[ch] == 1
                val alpha = if (muted) 0.3f else 1f
                val period = drawCi.periods[ch]
                val volume = drawCi.volumes[ch]
                val finalVol = drawCi.finalVols[ch]
                val pan = drawCi.pans[ch]
                val instrument = drawCi.instruments[ch]

                // row separator
                if (ch > 0) {
                    drawLine(
                        color = colors.rowSeparator,
                        start = Offset(0f, rowTop),
                        end = Offset(size.width, rowTop),
                        strokeWidth = 1f
                    )
                }

                // channel number
                val chLayout = textCache.getOrPut(textKey(0, ch)) {
                    textMeasurer.measure((ch + 1).toString(), textStyle)
                }
                drawText(
                    textLayoutResult = chLayout,
                    color = colors.channelNumber.copy(alpha = alpha),
                    topLeft = Offset(
                        (chNumWidth - chLayout.size.width) / 2f,
                        rowTop + (rowHeight - chLayout.size.height) / 2f
                    ),
                )

                // waveform box
                val waveTop = rowTop + pad
                val waveH = rowHeight - pad * 2
                drawRect(
                    color = colors.waveformBackground,
                    topLeft = Offset(chNumWidth, waveTop),
                    size = Size(waveWidth, waveH)
                )
                drawRect(
                    color = colors.waveformBorder,
                    topLeft = Offset(chNumWidth, waveTop),
                    size = Size(waveWidth, waveH),
                    style = HAIRLINE
                )
                val midY = waveTop + waveH / 2f
                drawLine(
                    color = colors.waveformBaseline,
                    start = Offset(chNumWidth + 1f, midY),
                    end = Offset(chNumWidth + waveWidth - 1f, midY),
                    strokeWidth = 1f
                )

                // waveform
                if (period > 0 && ch < drawWave.size) {
                    val buf = drawWave[ch]
                    val halfH = (waveH / 2f) * 0.88f * (finalVol / 64f).coerceIn(0f, 1f)
                    val xStep = (waveWidth - 2f) / WAVEFORM_SAMPLES.toFloat()
                    waveformPath.reset()
                    for (i in 0 until WAVEFORM_SAMPLES) {
                        val sx = chNumWidth + 1f + i * xStep
                        val sy = midY - (buf[i].toFloat() / 128f) * halfH
                        if (i == 0) waveformPath.moveTo(sx, sy) else waveformPath.lineTo(sx, sy)
                    }
                    drawPath(
                        path = waveformPath,
                        color = colors.waveformLine.copy(alpha = alpha),
                        style = waveStroke
                    )
                }

                val infoWidth = (size.width - infoX - pad).coerceAtLeast(0f)
                if (infoWidth < 16f) continue
                val lineH = rowHeight / 3f

                // instrument name
                val insLayout = textCache.getOrPut(textKey(if (muted) 4 else 1, instrument)) {
                    val raw = instrumentNames.getOrNull(instrument) ?: ".."
                    val text = if (muted) {
                        resources.getString(R.string.channel_muted, raw.take(2))
                    } else {
                        raw
                    }
                    textMeasurer.measure(text, textStyle)
                }
                drawText(
                    textLayoutResult = insLayout,
                    color = if (muted) colors.mutedText else colors.instrumentText,
                    topLeft = Offset(infoX, rowTop + (lineH - insLayout.size.height) / 2f)
                )

                // volume bar
                val barH = lineH * 0.5f
                val barTop = rowTop + lineH + (lineH - barH) / 2f
                val fvf = (finalVol / 64f).coerceIn(0f, 1f)
                val vf = (volume / 64f).coerceIn(0f, 1f)
                drawRect(
                    color = colors.volumeBarTrack,
                    topLeft = Offset(infoX, barTop),
                    size = Size(infoWidth, barH)
                )
                if (fvf > 0f) {
                    drawRect(
                        color = colors.finalVolumeBar.copy(alpha = 0.35f * alpha),
                        topLeft = Offset(infoX, barTop),
                        size = Size(infoWidth * fvf, barH)
                    )
                }
                if (vf > 0f) {
                    drawRect(
                        color = colors.volumeBar.copy(alpha = alpha),
                        topLeft = Offset(infoX, barTop),
                        size = Size(infoWidth * vf, barH)
                    )
                }

                // pan
                val panCenterY = rowTop + lineH * 2.5f
                val panFrac = (pan / 255f).coerceIn(0f, 1f)

                val gap = pad * 0.5f
                val railX0 = infoX + lLayout.size.width + gap
                val railX1 = infoX + infoWidth - rLayout.size.width - gap
                val railW = (railX1 - railX0).coerceAtLeast(0f)
                val cursorW = (railW * 0.06f).coerceIn(
                    minOf(4f * zoom, railW),
                    minOf(14f * zoom, railW)
                )
                val cursorH = lineH * 0.45f
                val cursorX = (railX0 + railW * panFrac - cursorW / 2f)
                    .coerceIn(railX0, (railX1 - cursorW).coerceAtLeast(railX0))

                drawText(
                    textLayoutResult = lLayout,
                    color = colors.panRail,
                    topLeft = Offset(
                        infoX,
                        panCenterY - lLayout.size.height / 2f
                    )
                )
                drawText(
                    textLayoutResult = rLayout,
                    color = colors.panRail,
                    topLeft = Offset(
                        infoX + infoWidth - rLayout.size.width,
                        panCenterY - rLayout.size.height / 2f
                    )
                )
                drawLine(
                    color = colors.panRail,
                    start = Offset(railX0, panCenterY),
                    end = Offset(railX1, panCenterY),
                    strokeWidth = 1f
                )
                drawLine(
                    color = colors.panRail,
                    start = Offset(railX0 + railW / 2f, panCenterY - cursorH / 2f),
                    end = Offset(railX0 + railW / 2f, panCenterY + cursorH / 2f),
                    strokeWidth = 1f
                )
                drawRect(
                    color = colors.panCursor.copy(alpha = alpha),
                    topLeft = Offset(cursorX, panCenterY - cursorH / 2f),
                    size = Size(cursorW, cursorH)
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 520)
@Composable
private fun PreviewChannelView() {
    AppTheme {
        ChannelView(
            numChannels = 10,
            instrumentNames = LoremIpsum(4).values.toPersistentList(),
            isPlaying = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
