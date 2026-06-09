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
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    sheetState: SheetState,
    queue: ImmutableList<ModuleEntity>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex in queue.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        shape = MaterialTheme.shapes.small,
        content = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
                content = {
                    Text(
                        text = stringResource(R.string.title_queue_sheet, queue.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .align(Alignment.CenterEnd),
                        onClick = onDismiss,
                        content = {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null)
                        }
                    )
                }
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = {
                    itemsIndexed(
                        items = queue,
                        key = { _, f -> f.uri.toString() },
                        itemContent = { index, file ->
                            QueueListItem(
                                isCurrentItem = index == currentIndex,
                                index = index,
                                file = file,
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
private fun QueueListItem(
    isCurrentItem: Boolean,
    index: Int,
    file: ModuleEntity,
    onItemClick: (Int) -> Unit
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onItemClick(index) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        shapes = ListItemDefaults.shapes(
            shape = MaterialTheme.shapes.small,
            focusedShape = MaterialTheme.shapes.small,
            pressedShape = MaterialTheme.shapes.small,
        ),
        content = {
            Text(
                text = file.name,
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
        leadingContent = {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (isCurrentItem) {
            {
                Icon(
                    imageVector = Icons.Default.Equalizer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        supportingContent = {
            Text(
                text = file.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
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
    var currentIndex by remember { mutableIntStateOf(3) }
    AppTheme {
        Scaffold { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            )
            QueueSheet(
                sheetState = sheetState,
                queue = List(10) {
                    ModuleEntity(
                        filePath = "content://preview/$it",
                        filename = "Item $it",
                        fileExtension = "669",
                        fileSize = 669L,
                    )
                }.toPersistentList(),
                currentIndex = currentIndex,
                onItemClick = { currentIndex = it },
                onDismiss = {},
            )
        }
    }
}
