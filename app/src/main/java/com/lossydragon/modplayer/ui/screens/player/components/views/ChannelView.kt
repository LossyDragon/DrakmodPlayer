package com.lossydragon.modplayer.ui.screens.player.components.views

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
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.tooling.preview.datasource.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.player.RenderingBackend
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.lighten
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.helllabs.libxmp.OpenMpt
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ChannelInfo

private const val WAVEFORM_SAMPLES = 256

// Packed Long key: [type:4][ch:8][extra:20][fontSizeBits:32]
// type: 0=ch number, 1=instrument, 2="L [", 3="] R", 4=muted instrument
private fun textKey(type: Int, ch: Int, extra: Int, fontSizeBits: Int): Long =
    (type.toLong() shl 60) or (ch.toLong() shl 52) or (extra.toLong() shl 32) or
        fontSizeBits.toLong()

data class ChannelViewColors(
    val background: Color,
    val channelNumber: Color,
    val rowSeparator: Color,
    val waveformBackground: Color,
    val waveformBorder: Color,
    val waveformBaseline: Color,
    val waveformLine: Color,
    val instrumentText: Color,
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
            background = Color.Black.lighten(1.0f),
            channelNumber = Color.White,
            rowSeparator = Color(0xFF1A2030),
            waveformBackground = Color(0xFF0C1018),
            waveformBorder = Color(0xFF303848),
            waveformBaseline = Color(0xFF1E2830),
            waveformLine = Color(0xFF40C878),
            instrumentText = Color(0xFF60D0A0),
            volumeBarTrack = Color.DarkGray.darken(1.5f),
            volumeBar = Color.Blue.lighten(1.1f),
            finalVolumeBar = Color.Gray,
            panRail = Color(0xFF2E3848),
            panCursor = Color(0xFF8898AA),
        )
    }
}

@Composable
fun ChannelView(
    numChannels: Int,
    instrumentNames: ImmutableList<String>,
    renderingBackend: RenderingBackend,
    supportsRawChannelSamples: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    colors: ChannelViewColors = rememberDefaultChannelViewColors()
) {
    val view = LocalView.current

    val liveData = remember(numChannels) { ChannelInfo() }
    val liveWave = remember(numChannels) { Array(numChannels) { ByteArray(WAVEFORM_SAMPLES) } }
    val prevKey = remember(numChannels) { IntArray(numChannels) }
    val prevInstrument = remember(numChannels) { IntArray(numChannels) { -1 } }

    val muteArr = remember(numChannels, renderingBackend, supportsRawChannelSamples) {
        IntArray(numChannels) { i ->
            if (!view.isInEditMode && supportsRawChannelSamples) {
                muteChannel(renderingBackend, i, -1)
            } else {
                0
            }
        }
    }
    var muteVersion by remember { mutableIntStateOf(0) }
    var drawTick by remember { mutableIntStateOf(0) }
    var soloChannel by remember { mutableIntStateOf(-1) }

    val isPlayingState = rememberUpdatedState(isPlaying)
    val backendState = rememberUpdatedState(renderingBackend)
    var zoom by remember { mutableFloatStateOf(1f) }
    val scrollYAnim = remember { Animatable(0f) }
    val scrollY = scrollYAnim.value
    val decay = remember { exponentialDecay<Float>() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(numChannels) {
        zoom = 1f
        scrollYAnim.snapTo(0f)
        soloChannel = -1

        if (numChannels == 0) return@LaunchedEffect

        while (isActive) {
            if (isPlayingState.value) {
                withContext(Dispatchers.Default) {
                    getChannelData(backendState.value, liveData)
                    for (i in 0 until numChannels) {
                        val period = liveData.periods[i]
                        val volume = liveData.volumes[i]
                        val finalVol = liveData.finalVols[i]
                        val key = liveData.keys[i]
                        val ins = liveData.instruments[i]
                        if (!supportsRawChannelSamples || period <= 0 ||
                            (volume == 0 && finalVol == 0)
                        ) {
                            liveWave[i].fill(0)
                            continue
                        }
                        val trigger = key >= 0 && (key != prevKey[i] || ins != prevInstrument[i])
                        if (key >= 0) prevKey[i] = key
                        prevInstrument[i] = ins
                        getSampleData(
                            backend = backendState.value,
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
    val textCache = remember { HashMap<Long, TextLayoutResult>(64) }

    LaunchedEffect(fontSize) { textCache.clear() }

    val textStyle = remember(fontSize) {
        TextStyle(fontSize = fontSize, fontFamily = FontFamily.Monospace)
    }
    val fontSizeBits = remember(fontSize) { fontSize.value.toBits() }

    val contentHeightRef = remember { mutableFloatStateOf(0f) }
    val scrollYRef = remember { mutableFloatStateOf(0f) }
    val chNumWidthRef = remember { mutableFloatStateOf(0f) }
    val waveWidthRef = remember { mutableFloatStateOf(0f) }
    val rowHeightRef = remember { mutableFloatStateOf(0f) }
    scrollYRef.floatValue = scrollY

    val rowHeight = baseRowHeight * zoom
    contentHeightRef.floatValue = rowHeight * numChannels
    chNumWidthRef.floatValue = baseChNumWidth * zoom
    waveWidthRef.floatValue = baseWaveWidth * zoom
    rowHeightRef.floatValue = rowHeight

    val waveformPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(numChannels) {
                val vt = VelocityTracker()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    coroutineScope.launch { scrollYAnim.stop() }
                    vt.resetTracking()
                    var singleFinger = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) {
                            if (singleFinger) {
                                val vel = vt.calculateVelocity()
                                coroutineScope.launch {
                                    val max = (contentHeightRef.floatValue - size.height)
                                        .coerceAtLeast(0f)
                                    scrollYAnim.updateBounds(0f, max)
                                    scrollYAnim.animateDecay(-vel.y, decay)
                                    scrollYAnim.updateBounds(null, null)
                                }
                            }
                            break
                        }
                        if (active.size == 1) {
                            val change = active[0]
                            vt.addPointerInputChange(change)
                            val dy = change.position.y - change.previousPosition.y
                            if (dy != 0f) {
                                coroutineScope.launch {
                                    val max = (contentHeightRef.floatValue - size.height)
                                        .coerceAtLeast(0f)
                                    scrollYAnim.snapTo((scrollYAnim.value - dy).coerceIn(0f, max))
                                }
                                change.consume()
                            }
                            singleFinger = true
                        } else {
                            zoom = (zoom * event.calculateZoom()).coerceIn(0.5f, 3f)
                            val pan = event.calculatePan()
                            coroutineScope.launch {
                                val max = (contentHeightRef.floatValue - size.height)
                                    .coerceAtLeast(0f)
                                scrollYAnim.snapTo((scrollYAnim.value - pan.y).coerceIn(0f, max))
                            }
                            event.changes.forEach { it.consume() }
                            singleFinger = false
                            vt.resetTracking()
                        }
                    }
                }
            }
            .pointerInput(numChannels) {
                detectTapGestures(
                    onTap = { offset ->
                        // Good formatting
                        val ch = ((offset.y + scrollYRef.floatValue) / rowHeightRef.floatValue)
                            .toInt()
                        val chnNumRef = chNumWidthRef.floatValue
                        val values = chnNumRef..(chnNumRef + waveWidthRef.floatValue)
                        if (ch in 0 until numChannels && offset.x in values) {
                            muteArr[ch] = if (muteArr[ch] == 0) 1 else 0
                            if (supportsRawChannelSamples) {
                                muteChannel(backendState.value, ch, muteArr[ch])
                            }
                            muteVersion++
                        }
                    },
                    onLongPress = { offset ->
                        // Good formatting
                        val ch = ((offset.y + scrollYRef.floatValue) / rowHeightRef.floatValue)
                            .toInt()
                        val chnNumRef = chNumWidthRef.floatValue
                        val values = chnNumRef..(chnNumRef + waveWidthRef.floatValue)
                        if (ch in 0 until numChannels && offset.x in values) {
                            if (soloChannel < 0) {
                                for (i in 0 until numChannels) {
                                    muteArr[i] = if (i != ch) 1 else 0
                                    if (supportsRawChannelSamples) {
                                        muteChannel(backendState.value, i, muteArr[i])
                                    }
                                }
                                soloChannel = ch
                            } else {
                                for (i in 0 until numChannels) {
                                    muteArr[i] = 0
                                    if (supportsRawChannelSamples) {
                                        muteChannel(backendState.value, i, 0)
                                    }
                                }
                                soloChannel = -1
                            }
                            muteVersion++
                        } else {
                            zoom = 1f
                            coroutineScope.launch { scrollYAnim.snapTo(0f) }
                        }
                    },
                )
            }
    ) {
        if ((drawTick == 0 && !view.isInEditMode) || numChannels == 0) return@Canvas

        val pad = basePad * zoom
        val chNumWidth = baseChNumWidth * zoom
        val waveWidth = baseWaveWidth * zoom
        val infoX = chNumWidth + waveWidth + pad * 2

        drawRect(color = colors.background, size = size)

        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            for (ch in 0 until numChannels) {
                val rowTop = ch * rowHeight - scrollY
                if (rowTop + rowHeight < 0f || rowTop > size.height) continue

                val muted = muteArr[ch] == 1
                val alpha = if (muted) 0.3f else 1f
                val period = liveData.periods[ch]
                val volume = liveData.volumes[ch]
                val finalVol = liveData.finalVols[ch]
                val pan = liveData.pans[ch]
                val instrument = liveData.instruments[ch]

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
                val chLayout = textCache.getOrPut(textKey(0, ch, 0, fontSizeBits)) {
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
                    style = Stroke(1f)
                )
                val midY = waveTop + waveH / 2f
                drawLine(
                    color = colors.waveformBaseline,
                    start = Offset(chNumWidth + 1f, midY),
                    end = Offset(chNumWidth + waveWidth - 1f, midY),
                    strokeWidth = 1f
                )

                // waveform
                if (period > 0 && ch < liveWave.size) {
                    val buf = liveWave[ch]
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
                        style = Stroke((1.5f * zoom).coerceAtLeast(1f), cap = StrokeCap.Butt)
                    )
                }

                val infoWidth = (size.width - infoX - pad).coerceAtLeast(0f)
                if (infoWidth < 16f) continue
                val lineH = rowHeight / 3f

                // instrument name
                val insLayout = textCache.getOrPut(
                    textKey(if (muted) 4 else 1, ch, instrument, fontSizeBits)
                ) {
                    val raw = instrumentNames.getOrNull(instrument) ?: ".."
                    textMeasurer.measure(if (muted) "${raw.take(2)} Muted" else raw, textStyle)
                }
                drawText(
                    textLayoutResult = insLayout,
                    color = if (muted) Color.Red else colors.instrumentText,
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
                val lLayout = textCache.getOrPut(textKey(2, 0, 0, fontSizeBits)) {
                    textMeasurer.measure("L [", textStyle)
                }
                val rLayout = textCache.getOrPut(textKey(3, 0, 0, fontSizeBits)) {
                    textMeasurer.measure("] R", textStyle)
                }
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

private fun getChannelData(backend: RenderingBackend, info: ChannelInfo) {
    when (backend) {
        RenderingBackend.LIBXMP -> Xmp.getChannelData(info)
        RenderingBackend.OPENMPT -> OpenMpt.getChannelData(info)
    }
}

private fun getSampleData(
    backend: RenderingBackend,
    trigger: Boolean,
    ins: Int,
    key: Int,
    period: Int,
    chn: Int,
    width: Int,
    buffer: ByteArray?
) {
    when (backend) {
        RenderingBackend.LIBXMP -> Xmp.getSampleData(trigger, ins, key, period, chn, width, buffer)

        RenderingBackend.OPENMPT -> OpenMpt.getSampleData(
            trigger,
            ins,
            key,
            period,
            chn,
            width,
            buffer
        )
    }
}

private fun muteChannel(backend: RenderingBackend, chn: Int, status: Int): Int =
    when (backend) {
        RenderingBackend.LIBXMP -> Xmp.mute(chn, status)
        RenderingBackend.OPENMPT -> OpenMpt.mute(chn, status)
    }

@Preview(showBackground = true, widthDp = 420, heightDp = 520)
@Composable
private fun PreviewChannelView() {
    AppTheme {
        ChannelView(
            numChannels = 10,
            instrumentNames = LoremIpsum(4).values.toPersistentList(),
            renderingBackend = RenderingBackend.LIBXMP,
            supportsRawChannelSamples = true,
            isPlaying = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
