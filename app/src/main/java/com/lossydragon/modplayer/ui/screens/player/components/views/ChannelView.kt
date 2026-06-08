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
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.lighten
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.helllabs.libxmp.Xmp
import org.helllabs.libxmp.model.ChannelInfo
import kotlin.time.Duration.Companion.milliseconds

/** PCM samples requested per channel per frame - matches getSampleData buffer width. */
private const val WAVEFORM_SAMPLES = 256

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
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    colors: ChannelViewColors = rememberDefaultChannelViewColors()
) {
    // Pre-allocated buffer reused across polls — JNI fills IntArrays in place via SetIntArrayRegion.
    val channelInfoBuffer = remember { ChannelInfo() }

    // Per-channel waveform PCM buffers. Re-created only when channel count changes.
    val waveformBuffers = remember(numChannels) {
        Array(numChannels) { ByteArray(WAVEFORM_SAMPLES) }
    }

    // Tracks last active key and instrument per channel.
    // keys[i] == -1 on held frames so we persist the last real value for getSampleData.
    val prevKey = remember(numChannels) { IntArray(numChannels) }
    val prevInstrument = remember(numChannels) { IntArray(numChannels) { -1 } }

    // Snapshots published to Canvas after each background poll.
    var channelSnapshot by remember { mutableStateOf(ChannelInfo()) }
    var waveformSnapshot by remember { mutableStateOf(emptyArray<ByteArray>()) }

    // Per-channel mute state; SnapshotStateList so individual toggles trigger recomposition.
    val muteStates = remember(numChannels) {
        mutableStateListOf<Boolean>().apply {
            repeat(numChannels) { i -> add(Xmp.mute(i, -1) == 1) }
        }
    }
    val isPlayingState = rememberUpdatedState(isPlaying)
    var soloChannel by remember { mutableIntStateOf(-1) }

    // Gesture state
    var zoom by remember { mutableFloatStateOf(1f) }
    val scrollYAnim = remember { Animatable(0f) }
    val scrollY = scrollYAnim.value
    val decay = remember { exponentialDecay<Float>() }
    val coroutineScope = rememberCoroutineScope()

    // Reset per-module state and start the ~50fps poll loop.
    LaunchedEffect(numChannels) {
        zoom = 1f
        scrollYAnim.snapTo(0f)
        soloChannel = -1

        if (numChannels == 0) return@LaunchedEffect

        while (isActive) {
            if (isPlayingState.value) {
                withContext(Dispatchers.Default) {
                    Xmp.getChannelData(channelInfoBuffer)

                    for (i in 0 until numChannels) {
                        val period = channelInfoBuffer.periods[i]
                        val volume = channelInfoBuffer.volumes[i]
                        val finalVol = channelInfoBuffer.finalVols[i]
                        val key = channelInfoBuffer.keys[i]
                        val ins = channelInfoBuffer.instruments[i]

                        if (period <= 0 || (volume == 0 && finalVol == 0)) {
                            waveformBuffers[i].fill(0)
                            continue
                        }

                        // Trigger waveform oscilloscope sync only on real note-on events.
                        val trigger = key >= 0 && (key != prevKey[i] || ins != prevInstrument[i])
                        if (key >= 0) prevKey[i] = key
                        prevInstrument[i] = ins

                        Xmp.getSampleData(
                            trigger = trigger,
                            ins = ins,
                            key = prevKey[i],
                            period = period,
                            chn = i,
                            width = WAVEFORM_SAMPLES,
                            buffer = waveformBuffers[i],
                        )
                    }
                }

                // Publish to Canvas on Main. copyOf prevents the JNI from racing the Canvas reader.
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
                waveformSnapshot = Array(numChannels) { waveformBuffers[it].copyOf() }
            }

            delay(20.milliseconds) // ~50fps
        }
    }

    // Layout constants derived from density and zoom.
    val density = LocalDensity.current
    val baseRowHeight = with(density) { 52.dp.toPx() }
    val baseChannelNumWidth = with(density) { 26.dp.toPx() }
    val baseWaveBoxWidth = with(density) { 96.dp.toPx() }
    val basePad = with(density) { 4.dp.toPx() }

    val fontSize = 11.sp * zoom
    val textMeasurer = rememberTextMeasurer()
    val textCache = remember { mutableMapOf<String, TextLayoutResult>() }
    LaunchedEffect(fontSize) { textCache.clear() }

    val textStyle = remember(fontSize) {
        TextStyle(fontSize = fontSize, fontFamily = FontFamily.Monospace)
    }
    val styleKey = remember(fontSize) { "|$fontSize" }

    // Shared refs so gesture lambdas always read the latest derived values
    // without the composable re-keying on pointerInput every zoom change.
    val contentHeightRef = remember { mutableFloatStateOf(0f) }
    val scrollYRef = remember { mutableFloatStateOf(0f) }
    val channelNumWidthRef = remember { mutableFloatStateOf(0f) }
    val waveBoxWidthRef = remember { mutableFloatStateOf(0f) }
    val rowHeightRef = remember { mutableFloatStateOf(0f) }
    scrollYRef.floatValue = scrollY

    val rowHeight = baseRowHeight * zoom
    contentHeightRef.floatValue = rowHeight * numChannels
    channelNumWidthRef.floatValue = baseChannelNumWidth * zoom
    waveBoxWidthRef.floatValue = baseWaveBoxWidth * zoom
    rowHeightRef.floatValue = rowHeight

    val waveformPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(numChannels) {
                val velocityTracker = VelocityTracker()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    coroutineScope.launch { scrollYAnim.stop() }
                    velocityTracker.resetTracking()
                    var isSingleFinger = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }

                        if (active.isEmpty()) {
                            if (isSingleFinger) {
                                val vel = velocityTracker.calculateVelocity()
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
                            velocityTracker.addPointerInputChange(change)
                            val dy = change.position.y - change.previousPosition.y
                            if (dy != 0f) {
                                coroutineScope.launch {
                                    val max = (contentHeightRef.floatValue - size.height)
                                        .coerceAtLeast(0f)
                                    scrollYAnim.snapTo(
                                        (scrollYAnim.value - dy)
                                            .coerceIn(0f, max)
                                    )
                                }
                                change.consume()
                            }
                            isSingleFinger = true
                        } else {
                            val gestureZoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            zoom = (zoom * gestureZoom).coerceIn(0.5f, 3f)
                            coroutineScope.launch {
                                val max = (contentHeightRef.floatValue - size.height).coerceAtLeast(
                                    0f
                                )
                                scrollYAnim.snapTo((scrollYAnim.value - pan.y).coerceIn(0f, max))
                            }
                            event.changes.forEach { it.consume() }
                            isSingleFinger = false
                            velocityTracker.resetTracking()
                        }
                    }
                }
            }
            .pointerInput(numChannels) {
                detectTapGestures(
                    onTap = { offset ->
                        val rh = rowHeightRef.floatValue
                        val cnw = channelNumWidthRef.floatValue
                        val wbw = waveBoxWidthRef.floatValue
                        val ch = ((offset.y + scrollYRef.floatValue) / rh).toInt()
                        if (ch in 0 until numChannels && offset.x in cnw..(cnw + wbw)) {
                            val nowMuted = !muteStates[ch]
                            muteStates[ch] = nowMuted
                            Xmp.mute(ch, if (nowMuted) 1 else 0)
                        }
                    },
                    onLongPress = { offset ->
                        val rh = rowHeightRef.floatValue
                        val cnw = channelNumWidthRef.floatValue
                        val wbw = waveBoxWidthRef.floatValue
                        val ch = ((offset.y + scrollYRef.floatValue) / rh).toInt()
                        if (ch in 0 until numChannels && offset.x in cnw..(cnw + wbw)) {
                            if (soloChannel < 0) {
                                for (i in 0 until numChannels) {
                                    val mute = i != ch
                                    muteStates[i] = mute
                                    Xmp.mute(i, if (mute) 1 else 0)
                                }
                                soloChannel = ch
                            } else {
                                for (i in 0 until numChannels) {
                                    muteStates[i] = false
                                    Xmp.mute(i, 0)
                                }
                                soloChannel = -1
                            }
                        } else {
                            // Long-press outside the waveform box resets zoom and scroll.
                            zoom = 1f
                            coroutineScope.launch { scrollYAnim.snapTo(0f) }
                        }
                    },
                )
            }
    ) {
        if (numChannels == 0) return@Canvas
        val ci = channelSnapshot
        val wavSnap = waveformSnapshot

        val pad = basePad * zoom
        val channelNumWidth = baseChannelNumWidth * zoom
        val waveBoxWidth = baseWaveBoxWidth * zoom
        val infoX = channelNumWidth + waveBoxWidth + pad * 2

        drawRect(color = colors.background, size = size)

        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            for (ch in 0 until numChannels) {
                val rowTop = ch * rowHeight - scrollY
                if (rowTop + rowHeight < 0f || rowTop > size.height) continue

                val muted = muteStates[ch]
                val muteAlpha = if (muted) 0.3f else 1f

                val period = ci.periods[ch]
                val volume = ci.volumes[ch]
                val finalVol = ci.finalVols[ch]
                val pan = ci.pans[ch]
                val instrument = ci.instruments[ch]

                // Row separator
                if (ch > 0) {
                    drawLine(
                        color = colors.rowSeparator,
                        start = Offset(0f, rowTop),
                        end = Offset(size.width, rowTop),
                        strokeWidth = 1f,
                    )
                }

                // Channel number
                val chLabel = (ch + 1).toString()
                val chLayout = textCache.getOrPut("chn$chLabel$styleKey") {
                    textMeasurer.measure(chLabel, textStyle)
                }
                drawText(
                    chLayout,
                    color = colors.channelNumber.copy(alpha = muteAlpha),
                    topLeft = Offset(
                        x = (channelNumWidth - chLayout.size.width) / 2f,
                        y = rowTop + (rowHeight - chLayout.size.height) / 2f,
                    ),
                )

                /************
                 * Waveform *
                 ************/

                val waveTop = rowTop + pad
                val waveH = rowHeight - pad * 2

                drawRect(
                    color = colors.waveformBackground,
                    topLeft = Offset(channelNumWidth, waveTop),
                    size = Size(waveBoxWidth, waveH),
                )
                drawRect(
                    color = colors.waveformBorder,
                    topLeft = Offset(channelNumWidth, waveTop),
                    size = Size(waveBoxWidth, waveH),
                    style = Stroke(width = 1f),
                )
                val midY = waveTop + waveH / 2f
                drawLine(
                    color = colors.waveformBaseline,
                    start = Offset(channelNumWidth + 1f, midY),
                    end = Offset(channelNumWidth + waveBoxWidth - 1f, midY),
                    strokeWidth = 1f,
                )

                if (period > 0 && ch < wavSnap.size) {
                    val buf = wavSnap[ch]
                    val ampScale = (finalVol / 64f).coerceIn(0f, 1f)
                    val halfH = (waveH / 2f) * 0.88f * ampScale
                    val xStep = (waveBoxWidth - 2f) / WAVEFORM_SAMPLES.toFloat()
                    waveformPath.reset()
                    for (i in 0 until WAVEFORM_SAMPLES) {
                        val sx = channelNumWidth + 1f + i * xStep
                        val sy = midY - (buf[i].toFloat() / 128f) * halfH
                        if (i == 0) waveformPath.moveTo(sx, sy) else waveformPath.lineTo(sx, sy)
                    }
                    drawPath(
                        path = waveformPath,
                        color = colors.waveformLine.copy(alpha = muteAlpha),
                        style = Stroke(
                            width = (1.5f * zoom).coerceAtLeast(1f),
                            cap = StrokeCap.Round,
                        ),
                    )
                }

                /****************
                 * Channel info *
                 ****************/

                val infoWidth = (size.width - infoX - pad).coerceAtLeast(0f)
                if (infoWidth < 16f) continue

                val lineH = rowHeight / 3f

                // Instrument index + name
                val insRaw = instrumentNames.getOrNull(instrument) ?: ".."
                val insPrefix = insRaw.take(2)
                val insText = if (muted) "$insPrefix Muted" else insRaw
                val insColor = if (muted) Color.Red else colors.instrumentText
                val insLayout = textCache.getOrPut("ins|$insText$styleKey") {
                    textMeasurer.measure(insText, textStyle)
                }
                drawText(
                    insLayout,
                    color = insColor,
                    topLeft = Offset(
                        x = infoX,
                        y = rowTop + (lineH - insLayout.size.height) / 2f,
                    ),
                )

                /**************
                 * Volume bar *
                 **************/

                val barH = lineH * 0.5f
                val barTop = rowTop + lineH + (lineH - barH) / 2f
                val volFrac = (volume / 64f).coerceIn(0f, 1f)
                val finalVolFrac = (finalVol / 64f).coerceIn(0f, 1f)

                drawRect(
                    color = colors.volumeBarTrack,
                    topLeft = Offset(infoX, barTop),
                    size = Size(infoWidth, barH),
                )
                if (finalVolFrac > 0f) {
                    drawRect(
                        color = colors.finalVolumeBar.copy(alpha = 0.35f * muteAlpha),
                        topLeft = Offset(infoX, barTop),
                        size = Size(infoWidth * finalVolFrac, barH),
                    )
                }
                if (volFrac > 0f) {
                    drawRect(
                        color = colors.volumeBar.copy(alpha = muteAlpha),
                        topLeft = Offset(infoX, barTop),
                        size = Size(infoWidth * volFrac, barH),
                    )
                }

                /*******
                 * Pan *
                 *******/

                val panCenterY = rowTop + lineH * 2.5f
                // libxmp pan is 0 (full-left) - 128 (center) - 255 (full-right)
                val panFrac = (pan / 255f).coerceIn(0f, 1f)

                val lLayout = textCache.getOrPut("pan_L$styleKey") {
                    textMeasurer.measure("L [", textStyle)
                }
                val rLayout = textCache.getOrPut("pan_R$styleKey") {
                    textMeasurer.measure("] R", textStyle)
                }
                val labelGap = pad * 0.5f

                val railStartX = infoX + lLayout.size.width + labelGap
                val railEndX = infoX + infoWidth - rLayout.size.width - labelGap
                val railWidth = (railEndX - railStartX).coerceAtLeast(0f)

                val cursorW = (railWidth * 0.06f).coerceIn(
                    minOf(4f * zoom, railWidth),
                    minOf(14f * zoom, railWidth),
                )
                val cursorH = lineH * 0.45f
                val cursorX = (railStartX + railWidth * panFrac - cursorW / 2f)
                    .coerceIn(railStartX, (railEndX - cursorW).coerceAtLeast(railStartX))

                drawText(
                    lLayout,
                    color = colors.panRail,
                    topLeft = Offset(infoX, panCenterY - lLayout.size.height / 2f),
                )
                drawText(
                    rLayout,
                    color = colors.panRail,
                    topLeft = Offset(
                        infoX + infoWidth - rLayout.size.width,
                        panCenterY - rLayout.size.height / 2f,
                    ),
                )
                drawLine(
                    color = colors.panRail,
                    start = Offset(railStartX, panCenterY),
                    end = Offset(railEndX, panCenterY),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = colors.panRail,
                    start = Offset(railStartX + railWidth / 2f, panCenterY - cursorH / 2f),
                    end = Offset(railStartX + railWidth / 2f, panCenterY + cursorH / 2f),
                    strokeWidth = 1f,
                )
                drawRect(
                    color = colors.panCursor.copy(alpha = muteAlpha),
                    topLeft = Offset(cursorX, panCenterY - cursorH / 2f),
                    size = Size(cursorW, cursorH),
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 520)
@Composable
private fun PreviewChannelView() {
    // LaunchedEffect does not run in preview, so numChannels=0 shows the blank-canvas guard.
    // Set to a positive value during local dev to inspect layout metrics without JNI.
    AppTheme {
        ChannelView(
            numChannels = 0,
            instrumentNames = emptyList<String>().toPersistentList(),
            isPlaying = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
