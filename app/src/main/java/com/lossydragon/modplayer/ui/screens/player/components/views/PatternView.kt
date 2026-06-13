package com.lossydragon.modplayer.ui.screens.player.components.views

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
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.model.NoteCell
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.materialkolor.ktx.lighten
import kotlinx.collections.immutable.toImmutableList

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
            instrument = Color(0xFF60D0A0),
            effectType = Color(0xFFE08060),
            effectParam = Color(0xFFC0A0E0),
            empty = Color(0xFF303848),
            beat = Color(0xFF1C2030).copy(alpha = 0.40f),
            currentRow = Color(0xFF4060A0),
            background = Color.Black.lighten(1.0f),
            headerBackground = Color(0xFF20283A),
            headerText = Color(0xFFA0C0E0),
        )
    }
}

private class PatternRenderState(
    val rowTextsHex: Array<String>,
    val rowTextsDec: Array<String>,
    val headers: Array<String>
)

private fun buildRenderState(pattern: PatternData) = PatternRenderState(
    rowTextsHex = Array(pattern.numRows) { "%02X".format(it) },
    rowTextsDec = Array(pattern.numRows) { it.toString() },
    headers = Array(pattern.numChannels) { (it + 1).toString() },
)

private typealias TextCache = HashMap<String, TextLayoutResult>

@Composable
fun PatternView(
    module: ModuleEntity,
    pattern: PatternData,
    currentRow: Int,
    modifier: Modifier = Modifier,
    colors: PatternColors = rememberDefaultPatternColors(),
    showRowNumbers: Boolean
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(module) {
        zoom = 1f
        userOffset = Offset.Zero
    }

    val renderState = remember(pattern.patternIndex, pattern.numRows, pattern.numChannels) {
        buildRenderState(pattern)
    }

    val density = LocalDensity.current
    val baseRowHeight = with(density) { 14.sp.toPx() }
    val baseColumnGap = with(density) { 6.dp.toPx() }
    val rowNumberWidth = with(density) { 26.dp.toPx() }

    val fontSize = 12.sp * zoom
    val rowHeight = baseRowHeight * zoom
    val columnGap = baseColumnGap * zoom
    val scaledRowNumWidth = rowNumberWidth * zoom
    val headerHeight = rowHeight * 1.5f

    val textMeasurer = rememberTextMeasurer()
    val textCache = remember(fontSize) { TextCache(128) }

    val baseStyle = remember(fontSize) {
        TextStyle(fontSize = fontSize, fontFamily = FontFamily.Monospace)
    }
    val headerStyle = remember(fontSize) {
        TextStyle(
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }

    val channelWidth = remember(fontSize, textMeasurer) {
        textMeasurer.measure("C-3 01 F00", baseStyle).size.width.toFloat() + columnGap
    }

    val channelWidthRef = remember { mutableFloatStateOf(channelWidth) }
    val rowNumWidthRef = remember { mutableFloatStateOf(scaledRowNumWidth) }
    channelWidthRef.floatValue = channelWidth
    rowNumWidthRef.floatValue = scaledRowNumWidth

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(pattern.numChannels) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(0.5f, 3f)
                    val cw = channelWidthRef.floatValue
                    val rnw = rowNumWidthRef.floatValue
                    val minX = ((size.width - rnw) - pattern.numChannels * cw)
                        .coerceAtMost(0f)
                    userOffset = Offset(
                        (userOffset.x + pan.x)
                            .coerceIn(minX, 0f),
                        userOffset.y + pan.y
                    )
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

        drawRect(
            color = colors.background,
            size = size
        )
        drawRect(
            color = colors.headerBackground,
            topLeft = Offset.Zero,
            size = Size(size.width, headerHeight)
        )

        // Channel header labels
        clipRect(left = scaledRowNumWidth, top = 0f, right = size.width, bottom = headerHeight) {
            for (ch in 0 until pattern.numChannels) {
                val cellX = scaledRowNumWidth + ch * channelWidth + userOffset.x
                if (cellX + channelWidth < 0 || cellX > size.width) continue
                val layout = textCache.getOrPut("hdr${renderState.headers[ch]}") {
                    textMeasurer.measure(renderState.headers[ch], headerStyle)
                }
                drawText(
                    textLayoutResult = layout,
                    color = colors.headerText,
                    topLeft = Offset(
                        cellX + (channelWidth - columnGap - layout.size.width) / 2f,
                        (headerHeight - layout.size.height) / 2f
                    )
                )
            }
        }

        val gridHeight = size.height - headerHeight
        val scrollY = -(currentRow * rowHeight - gridHeight / 2f + rowHeight / 2f) +
            userOffset.y + headerHeight

        val firstRow = ((headerHeight - scrollY) / rowHeight).toInt()
            .coerceAtLeast(0)
        val lastRow = ((size.height - scrollY) / rowHeight).toInt()
            .coerceAtMost(pattern.numRows - 1)

        // Row number column background
        drawRect(
            color = colors.rowNumberBackground,
            topLeft = Offset(0f, headerHeight),
            size = Size(scaledRowNumWidth, size.height - headerHeight)
        )

        // Beat tints (every 4 rows)
        clipRect(scaledRowNumWidth, headerHeight, size.width, size.height) {
            for (row in firstRow..lastRow) {
                if (row % 4 == 0) {
                    drawRect(
                        color = colors.beat,
                        topLeft = Offset(scaledRowNumWidth, row * rowHeight + scrollY),
                        size = Size(size.width - scaledRowNumWidth, rowHeight)
                    )
                }
            }
        }

        // Current row highlight
        clipRect(top = headerHeight) {
            drawRect(
                color = colors.currentRow,
                topLeft = Offset(0f, currentRow * rowHeight + scrollY),
                size = Size(size.width, rowHeight)
            )
        }

        // Row numbers
        val rowTexts = if (showRowNumbers) renderState.rowTextsDec else renderState.rowTextsHex
        clipRect(0f, headerHeight, scaledRowNumWidth, size.height) {
            for (row in firstRow..lastRow) {
                val rowY = row * rowHeight + scrollY
                val layout = textCache.getOrPut("rn${rowTexts[row]}") {
                    textMeasurer.measure(rowTexts[row], baseStyle)
                }
                drawText(
                    textLayoutResult = layout,
                    color = if (row == currentRow) Color.White else colors.rowNumber,
                    topLeft = Offset(
                        (scaledRowNumWidth - layout.size.width) / 2f,
                        rowY + (rowHeight - layout.size.height) / 2f
                    )
                )
            }
        }

        // Cell data
        clipRect(scaledRowNumWidth, headerHeight, size.width, size.height) {
            for (row in firstRow..lastRow) {
                val rowY = row * rowHeight + scrollY
                val rowCells = pattern.cells[row]
                for (ch in 0 until pattern.numChannels) {
                    val cellX = scaledRowNumWidth + ch * channelWidth + userOffset.x
                    if (cellX + channelWidth < 0 || cellX > size.width) continue
                    drawCell(
                        measurer = textMeasurer,
                        cache = textCache,
                        cell = rowCells[ch],
                        style = baseStyle,
                        colors = colors,
                        x = cellX,
                        y = rowY
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawCell(
    measurer: TextMeasurer,
    cache: TextCache,
    cell: NoteCell,
    style: TextStyle,
    colors: PatternColors,
    x: Float,
    y: Float
) {
    val gap = style.fontSize.toPx() * 0.25f

    // note
    val noteLayout = cached(measurer, cache, cell.noteStr, style)
    drawText(
        textLayoutResult = noteLayout,
        color = if (cell.note == 0) colors.empty else colors.note,
        topLeft = Offset(x, y)
    )

    // instrument
    val insX = x + noteLayout.size.width + gap
    val insLayout = cached(measurer, cache, cell.instrumentStr, style)
    drawText(
        textLayoutResult = insLayout,
        color = if (cell.instrument == 0) colors.empty else colors.instrument,
        topLeft = Offset(insX, y)
    )

    // TODO apply secondary affect to view
    // effect — fall back to the secondary slot (volume column) when the primary is empty
    val fxX = insX + insLayout.size.width + gap
    val showSecondary = cell.fxType < 0 && cell.fx2Type >= 0
    if (cell.fxType < 0 && !showSecondary) {
        drawText(
            textLayoutResult = cached(measurer, cache, "...", style),
            color = colors.empty,
            topLeft = Offset(fxX, y)
        )
    } else {
        val typeStr = if (showSecondary) cell.effect2TypeStr else cell.effectTypeStr
        val paramStr = if (showSecondary) cell.effect2ParamStr else cell.effectParamStr
        val typeLayout = cached(measurer, cache, typeStr, style)
        drawText(
            textLayoutResult = typeLayout,
            color = colors.effectType,
            topLeft = Offset(fxX, y)
        )
        drawText(
            textLayoutResult = cached(measurer, cache, paramStr, style),
            color = colors.effectParam,
            topLeft = Offset(fxX + typeLayout.size.width, y)
        )
    }
}

private fun cached(
    measurer: TextMeasurer,
    cache: TextCache,
    text: String,
    style: TextStyle
): TextLayoutResult = cache.getOrPut(text) { measurer.measure(text, style) }

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
                    NoteCell(fxParam = 0, fxType = 0, instrument = -1, note = -1)
                }
            }.toImmutableList()
        }.toImmutableList(),
    )
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PatternView(
                module = ModuleEntity(),
                pattern = sample,
                currentRow = 12,
                showRowNumbers = true
            )
        }
    }
}
