package com.lossydragon.modplayer.ui.screens.player.components.views

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lossydragon.modplayer.player.model.ChannelSnapshot
import com.lossydragon.modplayer.player.model.FrameSnapshot
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.materialkolor.ktx.darken
import com.materialkolor.ktx.lighten
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.helllabs.libxmp.Xmp

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
    frame: FrameSnapshot?,
    instrumentNames: ImmutableList<String>,
    modifier: Modifier = Modifier,
    colors: ChannelViewColors = rememberDefaultChannelViewColors()
) {
    val channels = frame?.channels
    val numChannels = channels?.size ?: 0

    // Pre-allocated PCM buffers, one per channel. Re-created only when numChannels changes
    // so Dispatchers.Default never allocates during steady-state playback.
    val waveformBuffers = remember(numChannels) {
        Array(numChannels) { ByteArray(WAVEFORM_SAMPLES) }
    }

    // Per-channel instrument snapshot for trigger detection.
    val prevTrigger = remember(numChannels) {
        Array(numChannels) { intArrayOf(-1) }
    }

    // Last valid note per channel. ChannelSnapshot.note is -1 on every frame that
    // has no new note event - using it directly passes key=0 (C-0) to getSampleData,
    // selecting the wrong sub-instrument for multi-layered samples on held notes.
    val prevKey = remember(numChannels) { IntArray(numChannels) }

    // Atomic snapshot published to the Canvas after each background fill.
    // Using a State<Array> avoids exposing raw mutable ByteArrays across threads.
    var waveformSnapshot by remember { mutableStateOf(emptyArray<ByteArray>()) }

    // Per-channel mute state; SnapshotStateList so individual toggles trigger recomposition.
    val muteStates = remember(numChannels) {
        mutableStateListOf<Boolean>().apply { repeat(numChannels) { add(false) } }
    }

    // -1 = no solo active; ≥0 = index of the soloed channel.
    var soloChannel by remember { mutableIntStateOf(-1) }

    LaunchedEffect(frame?.timeMs) {
        if (channels == null || numChannels == 0) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            for (i in 0 until numChannels) {
                val s = channels[i]
                if (s.period <= 0 || (s.volume == 0 && s.finalVol == 0)) {
                    // Channel is silent or fully faded out - show a flat line.
                    waveformBuffers[i].fill(0)
                    continue
                }

                // Trigger only when a real note event fires (s.note >= 0) and the
                // note or instrument actually changed. The old code also fired on note
                // release (s.note going -1 -> real) which was resetting the oscilloscope
                // position every held frame.
                val trigger = s.note >= 0 &&
                    (s.note != prevKey[i] || s.instrument != prevTrigger[i][0])

                if (s.note >= 0) prevKey[i] = s.note

                prevTrigger[i][0] = s.instrument

                // Reads raw instrument sample data pitched by period - not actual mixed PCM.
                // Volume, envelopes, and effects are not applied; position is tracked independently.
                Xmp.getSampleData(
                    trigger = trigger,
                    ins = s.instrument,
                    key = prevKey[i],
                    period = s.period,
                    chn = i,
                    width = WAVEFORM_SAMPLES,
                    buffer = waveformBuffers[i],
                )
            }
        }

        // copyOf on main thread - 256 B × channels is cheap; avoids sharing mutable arrays.
        waveformSnapshot = Array(numChannels) { waveformBuffers[it].copyOf() }
    }

    // Gesture
    var zoom by remember { mutableFloatStateOf(1f) }
    val scrollYAnim = remember { Animatable(0f) }
    val scrollY = scrollYAnim.value
    val decay = remember { exponentialDecay<Float>() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(numChannels) {
        zoom = 1f
        coroutineScope.launch { scrollYAnim.snapTo(0f) }
        soloChannel = -1
    }

    // Layout
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

    // Reused across rows to avoid per-frame Path allocation.
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
                                    val max =
                                        (contentHeightRef.floatValue - size.height).coerceAtLeast(
                                            0f
                                        )
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
                                    val max =
                                        (contentHeightRef.floatValue - size.height).coerceAtLeast(
                                            0f
                                        )
                                    scrollYAnim.snapTo((scrollYAnim.value - dy).coerceIn(0f, max))
                                }
                                change.consume()
                            }
                            isSingleFinger = true
                        } else {
                            // Multi-finger: zoom + pan, no fling on release
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
                                // Enter solo - mute every channel except this one.
                                for (i in 0 until numChannels) {
                                    val mute = i != ch
                                    muteStates[i] = mute
                                    Xmp.mute(i, if (mute) 1 else 0)
                                }
                                soloChannel = ch
                            } else {
                                // Exit solo - unmute everything.
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
        if (numChannels == 0 || channels == null) return@Canvas
        val wavSnap = waveformSnapshot // read State then registers redraw dependency

        val pad = basePad * zoom
        val channelNumWidth = baseChannelNumWidth * zoom
        val waveBoxWidth = baseWaveBoxWidth * zoom
        val infoX = channelNumWidth + waveBoxWidth + pad * 2

        drawRect(color = colors.background, size = size)

        clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
            for (ch in 0 until numChannels) {
                val rowTop = ch * rowHeight - scrollY
                if (rowTop + rowHeight < 0f || rowTop > size.height) continue

                val snap = channels[ch]
                val muted = muteStates[ch]
                val muteAlpha = if (muted) 0.3f else 1f

                // Row separator (skip for the very first row)
                if (ch > 0) {
                    drawLine(
                        color = colors.rowSeparator,
                        start = Offset(0f, rowTop),
                        end = Offset(size.width, rowTop),
                        strokeWidth = 1f,
                    )
                }

                /*******************
                 * Channel numbers *
                 *******************/

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

                val waveLeft = channelNumWidth
                val waveTop = rowTop + pad
                val waveH = rowHeight - pad * 2

                drawRect(
                    color = colors.waveformBackground,
                    topLeft = Offset(waveLeft, waveTop),
                    size = Size(waveBoxWidth, waveH),
                )
                drawRect(
                    color = colors.waveformBorder,
                    topLeft = Offset(waveLeft, waveTop),
                    size = Size(waveBoxWidth, waveH),
                    style = Stroke(width = 1f),
                )

                // Center baseline
                val midY = waveTop + waveH / 2f
                drawLine(
                    color = colors.waveformBaseline,
                    start = Offset(waveLeft + 1f, midY),
                    end = Offset(waveLeft + waveBoxWidth - 1f, midY),
                    strokeWidth = 1f,
                )

                // Waveform polyline - only when the channel has an active period
                if (snap.period > 0 && ch < wavSnap.size) {
                    val buf = wavSnap[ch]
                    val ampScale = (snap.finalVol / 64f).coerceIn(0f, 1f)
                    val halfH = (waveH / 2f) * 0.88f * ampScale
                    val xStep = (waveBoxWidth - 2f) / WAVEFORM_SAMPLES.toFloat()
                    waveformPath.reset()
                    for (i in 0 until WAVEFORM_SAMPLES) {
                        val sx = waveLeft + 1f + i * xStep
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
                 * Channel Info *
                 ****************/

                val infoWidth = (size.width - infoX - pad).coerceAtLeast(0f)
                if (infoWidth < 16f) continue

                val lineH = rowHeight / 3f

                // instrument index + name  ("01 SampleName" or "01 Muted" when muted)
                val insRaw = instrumentNames.getOrNull(snap.instrument) ?: ".."
                val insPrefix = insRaw.take(2) // e.g. "01"
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
                 * Volume Bar *
                 **************/

                val barH = lineH * 0.5f
                val barTop = rowTop + lineH + (lineH - barH) / 2f
                val volFrac = (snap.volume / 64f).coerceIn(0f, 1f)
                val finalVolFrac = (snap.finalVol / 64f).coerceIn(0f, 1f)

                // Volume bar background
                drawRect(
                    color = colors.volumeBarTrack,
                    topLeft = Offset(infoX, barTop),
                    size = Size(infoWidth, barH),
                )
                if (finalVolFrac > 0f) {
                    // Final volume bar
                    drawRect(
                        color = colors.finalVolumeBar.copy(alpha = 0.35f * muteAlpha),
                        topLeft = Offset(infoX, barTop),
                        size = Size(infoWidth * finalVolFrac, barH),
                    )
                }
                if (volFrac > 0f) {
                    // Volume bar
                    drawRect(
                        color = colors.volumeBar.copy(alpha = muteAlpha),
                        topLeft = Offset(infoX, barTop),
                        size = Size(infoWidth * volFrac, barH),
                    )
                }

                /*******
                 * Pan *
                 *******/

                // Pan rail with L/R labels  L ----- o ------- R
                val panCenterY = rowTop + lineH * 2.5f
                // libxmp pan is 0 (full-left) - 128 (center) - 255 (full-right)
                val panFrac = (snap.pan / 255f).coerceIn(0f, 1f)

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

                // Pan 'L'
                drawText(
                    lLayout,
                    color = colors.panRail,
                    topLeft = Offset(infoX, panCenterY - lLayout.size.height / 2f),
                )
                // Pan 'R'
                drawText(
                    rLayout,
                    color = colors.panRail,
                    topLeft = Offset(
                        infoX + infoWidth - rLayout.size.width,
                        panCenterY - rLayout.size.height / 2f
                    ),
                )
                // Pan Line
                drawLine(
                    color = colors.panRail,
                    start = Offset(railStartX, panCenterY),
                    end = Offset(railEndX, panCenterY),
                    strokeWidth = 1f,
                )
                // Pan Center Tick
                drawLine(
                    color = colors.panRail,
                    start = Offset(railStartX + railWidth / 2f, panCenterY - cursorH / 2f),
                    end = Offset(railStartX + railWidth / 2f, panCenterY + cursorH / 2f),
                    strokeWidth = 1f,
                )
                // Actual Pan Rect
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
    val fakeChannels = List(8) { ch ->
        ChannelSnapshot(
            volume = ((ch + 1) * 7).coerceAtMost(64),
            finalVol = ((ch + 1) * 9).coerceAtMost(64),
            pan = (ch * 36 + 18).coerceAtMost(255),
            instrument = ch % 5,
            note = 36 + ch * 3,
            period = 400 + ch * 60,
        )
    }.toImmutableList()

    val fakeInstruments = Array(5) { i -> "%02X Sample_%02d".format(i + 1, i + 1) }

    AppTheme {
        ChannelView(
            frame = FrameSnapshot(
                bpm = 125,
                channels = fakeChannels,
                numRows = 64,
                pattern = 0,
                position = 0,
                row = 0,
                speed = 6,
                timeMs = 0,
                totalTimeMs = 120_000,
            ),
            instrumentNames = fakeInstruments.toPersistentList(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
