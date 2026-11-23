package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.SheetValue.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.formatMs
import com.lossydragon.native.model.Sequence
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DurationsSheet(
    sheetState: SheetState,
    seqData: ImmutableList<Sequence>,
    currentSequence: Int,
    onDismiss: () -> Unit,
    onItemClick: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    PlayerSheet(
        title = stringResource(R.string.title_subsong_sheet, seqData.size),
        sheetState = sheetState,
        onDismiss = onDismiss,
        content = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = {
                    itemsIndexed(
                        items = seqData,
                        key = { idx, _ -> idx },
                        itemContent = { idx, duration ->
                            DurationItem(
                                isCurrentItem = idx == currentSequence,
                                index = idx,
                                duration = duration.duration,
                                onItemClick = onItemClick,
                            )
                        }
                    )
                }
            )
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DurationItem(
    isCurrentItem: Boolean,
    index: Int,
    duration: Int,
    onItemClick: (Int) -> Unit
) {
    val label = if (index == 0) {
        stringResource(R.string.subsong_main)
    } else {
        stringResource(R.string.subsong_sub, index)
    }
    val timeText = stringResource(R.string.subsong_item, duration.toLong().formatMs(), label)

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onItemClick(index) },
        shapes = ListItemDefaults.shapes(
            shape = MaterialTheme.shapes.small,
            focusedShape = MaterialTheme.shapes.small,
            pressedShape = MaterialTheme.shapes.small,
        ),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        content = {
            Text(
                text = timeText,
                style = if (isCurrentItem) {
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { RadioButton(selected = isCurrentItem, onClick = null) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun Preview() {
    val sheetState = rememberBottomSheetState(
        initialValue = Expanded,
        enabledValues = setOf(Hidden, Expanded),
    )
    var currentSequence by remember { mutableIntStateOf(5) }
    AppTheme {
        Scaffold { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            )

            DurationsSheet(
                sheetState = sheetState,
                seqData = listOf(
                    Sequence(0, 183_000), // 3:03 - Main Song
                    Sequence(0, 94_000), // 1:34
                    Sequence(0, 211_000), // 3:31
                    Sequence(0, 57_000), // 0:57
                    Sequence(0, 142_000), // 2:22
                    Sequence(0, 78_000), // 1:18
                    Sequence(0, 305_000), // 5:05
                    Sequence(0, 33_000), // 0:33
                    Sequence(0, 167_000), // 2:47
                    Sequence(0, 120_000), // 2:00
                ).toPersistentList(),
                currentSequence = currentSequence,
                onItemClick = { currentSequence = it },
                onDismiss = {},
            )
        }
    }
}
