package com.lossydragon.modplayer.ui.screens.player.components.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.player.model.NoteCell
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.materialkolor.ktx.lighten
import kotlinx.collections.immutable.toImmutableList

/** Color palette for tracker cell parts. */
data class PatternColors(
    val rowNumberBackground: Color,
    val rowNumber: Color,
    val note: Color,
    val instrument: Color,
    val effectType: Color,
    val effectParam: Color,
    val empty: Color,
    val beat: Color,
    val currentRow: Color,
    val background: Color,
    val headerBackground: Color,
    val headerText: Color
)

@Composable
fun rememberDefaultPatternColors(): PatternColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        PatternColors(
            rowNumberBackground = Color(0xFF181C24),
            rowNumber = Color(0xFF6080A0),
            note = Color(0xFFE0E8F0),
            instrument = Color(0xFF60D0A0), // mint
            effectType = Color(0xFFE08060), // coral
            effectParam = Color(0xFFC0A0E0), // lavender
            empty = Color(0xFF303848),
            beat = Color(0xFF1C2030).copy(alpha = 0.40f),
            currentRow = Color(0xFF4060A0),
            background = Color.Black.lighten(1.0f),
            headerBackground = Color(0xFF20283A),
            headerText = Color(0xFFA0C0E0),
        )
    }
}

/**
 * Pre-built per-frame caches so the draw loop avoids per-cell string allocations
 * and substring slicing.
 */
private class PatternRenderState(
    val rowTextsHex: Array<String>,
    val rowTextsDec: Array<String>,
    val channelHeaderTexts: Array<String>
)

/** Pre-compute strings once per pattern (or channel-count change). */
private fun buildRenderState(pattern: PatternData): PatternRenderState =
    PatternRenderState(
        rowTextsHex = Array(pattern.numRows) { "%02X".format(it) },
        rowTextsDec = Array(pattern.numRows) { it.toString() },
        channelHeaderTexts = Array(pattern.numChannels) { (it + 1).toString() },
    )

@Composable
fun PatternView(
    pattern: PatternData,
    currentRow: Int,
    modifier: Modifier = Modifier,
    colors: PatternColors = rememberDefaultPatternColors(),
    showRowNumbers: Boolean
) {
    // Zoom + pan state
    var zoom by remember { mutableFloatStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom and pan when track/pattern changes
    LaunchedEffect(pattern.patternIndex) {
        zoom = 1f
        userOffset = Offset.Zero
    }

    // Pre-compute static text values once per pattern
    val renderState = remember(pattern.patternIndex, pattern.numRows, pattern.numChannels) {
        buildRenderState(pattern)
    }

    val density = LocalDensity.current
    val baseRowHeight = with(density) { 14.sp.toPx() }
    val baseColumnGap = with(density) { 6.dp.toPx() }
    val baseFontSize = 12.sp

    // Zoom-derived dimensions
    val rowHeight = baseRowHeight * zoom
    val columnGap = baseColumnGap * zoom
    val fontSize = baseFontSize * zoom
    val rowNumberWidth = with(density) { 26.dp.toPx() } * zoom
    val headerHeight = rowHeight * 1.5f

    val textMeasurer = rememberTextMeasurer()
    val textCache = remember { mutableMapOf<String, TextLayoutResult>() }

    // Clear cache when font size changes (zoom) - stale measurements become wrong size
    LaunchedEffect(fontSize) {
        textCache.clear()
    }

    val baseStyle = remember(fontSize) {
        TextStyle(fontSize = fontSize, fontFamily = FontFamily.Monospace)
    }
    val headerStyle = remember(fontSize) {
        TextStyle(
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }

    // Cache key suffix - concat once, append per text
    val styleKey = remember(fontSize) { "|${baseStyle.fontSize}" }

    // Measure one sample cell to derive exact channel slot width
    val sampleLayout = remember(fontSize, textMeasurer, baseStyle) {
        textMeasurer.measure("C-3 01 F00", baseStyle)
    }
    val channelWidth = sampleLayout.size.width.toFloat() + columnGap

    // Mutable refs so the gesture lambda always sees latest values without re-keying pointerInput
    val channelWidthRef = remember { mutableFloatStateOf(channelWidth) }
    val rowNumberWidthRef = remember { mutableFloatStateOf(rowNumberWidth) }
    channelWidthRef.floatValue = channelWidth
    rowNumberWidthRef.floatValue = rowNumberWidth

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(pattern.numChannels) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    // Pinch zoom (0.5x – 3x)
                    zoom = (zoom * gestureZoom).coerceIn(0.5f, 3f)

                    // Clamp horizontal pan so user can't scroll past channel 0 or last channel
                    val proposed = userOffset + pan
                    val cw = channelWidthRef.floatValue
                    val rnw = rowNumberWidthRef.floatValue
                    val contentWidth = pattern.numChannels * cw
                    val visibleChannelArea = size.width - rnw
                    val minX = (visibleChannelArea - contentWidth).coerceAtMost(0f)
                    userOffset = Offset(proposed.x.coerceIn(minX, 0f), proposed.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        zoom = 1f
                        userOffset = Offset.Zero
                    }
                )
            }
    ) {
        if (pattern.numRows == 0 || pattern.numChannels == 0) return@Canvas

        // Canvas background
        drawRect(color = colors.background, size = size)

        // Header background
        drawRect(
            color = colors.headerBackground,
            topLeft = Offset.Zero,
            size = Size(size.width, headerHeight),
        )

        // Channel header labels
        clipRect(
            left = rowNumberWidth,
            top = 0f,
            right = size.width,
            bottom = headerHeight,
        ) {
            for (ch in 0 until pattern.numChannels) {
                val cellX = rowNumberWidth + ch * channelWidth + userOffset.x
                if (cellX + channelWidth < 0 || cellX > size.width) continue

                val label = renderState.channelHeaderTexts[ch]
                val labelLayout = textCache.getOrPut("hdr|$label$styleKey") {
                    textMeasurer.measure(label, headerStyle)
                }
                val labelX = cellX + (channelWidth - columnGap - labelLayout.size.width) / 2
                val labelY = (headerHeight - labelLayout.size.height) / 2
                drawText(labelLayout, color = colors.headerText, topLeft = Offset(labelX, labelY))
            }
        }

        // --- PATTERN GRID below header ---
        val gridTop = headerHeight
        val gridHeight = size.height - headerHeight

        // Auto-center current row, applying user's vertical pan offset
        val targetY = currentRow * rowHeight - gridHeight / 2 + rowHeight / 2
        val scrollY = -targetY + userOffset.y + gridTop

        // Compute only the row range that's actually on screen
        val firstVisibleRow = ((gridTop - scrollY) / rowHeight).toInt().coerceAtLeast(0)
        val lastVisibleRow = ((size.height - scrollY) / rowHeight).toInt()
            .coerceAtMost(pattern.numRows - 1)

        // Row number column background
        drawRect(
            color = colors.rowNumberBackground,
            topLeft = Offset(0f, gridTop),
            size = Size(rowNumberWidth, size.height - gridTop),
        )

        // Beat tints
        clipRect(left = rowNumberWidth, top = gridTop, right = size.width, bottom = size.height) {
            for (row in firstVisibleRow..lastVisibleRow) {
                if (row % 4 == 0) {
                    drawRect(
                        color = colors.beat,
                        topLeft = Offset(rowNumberWidth, row * rowHeight + scrollY),
                        size = Size(size.width - rowNumberWidth, rowHeight),
                    )
                }
            }
        }

        // Current row tint
        clipRect(top = gridTop) {
            drawRect(
                color = colors.currentRow,
                topLeft = Offset(0f, (currentRow * rowHeight + scrollY)),
                size = Size(size.width, rowHeight),
            )
        }

        // Row numbers
        val rowTexts = if (showRowNumbers) renderState.rowTextsDec else renderState.rowTextsHex
        clipRect(
            left = 0f,
            top = gridTop,
            right = rowNumberWidth,
            bottom = size.height,
        ) {
            for (row in firstVisibleRow..lastVisibleRow) {
                val rowY = row * rowHeight + scrollY
                val rowText = rowTexts[row]
                val rowTextLayout = textCache.getOrPut(rowText + styleKey) {
                    textMeasurer.measure(rowText, baseStyle)
                }
                val rowTextX = (rowNumberWidth - rowTextLayout.size.width) / 2
                val rowTextY = rowY + (rowHeight - rowTextLayout.size.height) / 2
                drawText(
                    rowTextLayout,
                    color = if (row == currentRow) Color.White else colors.rowNumber,
                    topLeft = Offset(rowTextX, rowTextY)
                )
            }
        }

        // Channel cells
        clipRect(
            left = rowNumberWidth,
            top = gridTop,
            right = size.width,
            bottom = size.height,
        ) {
            for (row in firstVisibleRow..lastVisibleRow) {
                val rowY = row * rowHeight + scrollY

                val rowCells = pattern.cells[row]
                for (ch in 0 until pattern.numChannels) {
                    val cellX = rowNumberWidth + ch * channelWidth + userOffset.x
                    if (cellX + channelWidth < 0 || cellX > size.width) continue

                    drawCell(
                        measurer = textMeasurer,
                        cache = textCache,
                        styleKey = styleKey,
                        cell = rowCells[ch],
                        style = baseStyle,
                        colors = colors,
                        x = cellX,
                        y = rowY,
                    )
                }
            }
        }
    }
}

/** Draws one tracker cell as colored segments: NOTE INS TYPE PARAM. */
private fun DrawScope.drawCell(
    measurer: TextMeasurer,
    cache: MutableMap<String, TextLayoutResult>,
    styleKey: String,
    cell: NoteCell,
    style: TextStyle,
    colors: PatternColors,
    x: Float,
    y: Float
) {
    val gap = style.fontSize.toPx() * 0.25f

    // Note column
    val noteColor = if (cell.note == 0) colors.empty else colors.note
    val noteLayout = drawCachedText(measurer, cache, styleKey, cell.noteStr, style, noteColor, x, y)
    var cursorX = x + noteLayout.size.width + gap

    // Instrument column
    val insColor = if (cell.instrument == 0) colors.empty else colors.instrument
    val insLayout =
        drawCachedText(measurer, cache, styleKey, cell.instrumentStr, style, insColor, cursorX, y)
    cursorX += insLayout.size.width + gap

    // Effect column
    if (cell.fxType < 0) {
        drawCachedText(measurer, cache, styleKey, "...", style, colors.empty, cursorX, y)
    } else {
        val typeChar = cell.effectTypeChar
        val paramStr = cell.effectParamStr
        val typeLayout = drawCachedText(
            measurer,
            cache,
            styleKey,
            typeChar,
            style,
            colors.effectType,
            cursorX,
            y
        )
        drawCachedText(
            measurer,
            cache,
            styleKey,
            paramStr,
            style,
            colors.effectParam,
            cursorX + typeLayout.size.width,
            y
        )
    }
}

/** Measures (if needed) and draws text at (x, y). */
private fun DrawScope.drawCachedText(
    measurer: TextMeasurer,
    cache: MutableMap<String, TextLayoutResult>,
    styleKey: String,
    text: String,
    style: TextStyle,
    color: Color,
    x: Float,
    y: Float
): TextLayoutResult {
    val layout = cache.getOrPut(text + styleKey) { measurer.measure(text, style) }
    drawText(layout, color = color, topLeft = Offset(x, y))
    return layout
}

@Preview(showBackground = true, widthDp = 400, heightDp = 600)
@Composable
private fun PreviewPatternView() {
    val sample = PatternData(
        patternIndex = 0,
        numRows = 64,
        numChannels = 8,
        cells = List(64) { row ->
            List(8) { ch ->
                if (row % 2 == 0 && ch < 4) {
                    NoteCell(
                        note = 37 + (row / 4) % 12,
                        instrument = ch + 1,
                        fxType = if (ch == 0) 0 else -1,
                        fxParam = if (ch == 0) row else -1,
                    )
                } else {
                    NoteCell(0, 0, -1, -1)
                }
            }.toImmutableList()
        }.toImmutableList(),
    )

    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PatternView(
                pattern = sample,
                currentRow = 12,
                modifier = Modifier.size(400.dp, 600.dp),
                showRowNumbers = false,
            )
        }
    }
}
