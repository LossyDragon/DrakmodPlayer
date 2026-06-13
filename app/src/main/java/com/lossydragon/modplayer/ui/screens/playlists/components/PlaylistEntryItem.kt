package com.lossydragon.modplayer.ui.screens.playlists.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlin.math.abs
import kotlinx.coroutines.launch
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReorderableCollectionItemScope.PlaylistEntryItem(
    index: Int,
    file: ModuleEntity,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var componentWidth by remember { mutableFloatStateOf(0f) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    val isSwiping by remember { derivedStateOf { swipeOffset < -2f } }

    LaunchedEffect(isSwiping) {
        if (isSwiping) hapticFeedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
    }

    Box(
        modifier = Modifier
            .onSizeChanged { componentWidth = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    swipeOffset = (swipeOffset + delta).coerceIn(-componentWidth, 0f)
                },
                onDragStopped = {
                    scope.launch {
                        if (componentWidth > 0f && abs(swipeOffset) / componentWidth >= 0.4f) {
                            animate(
                                initialValue = swipeOffset,
                                targetValue = -componentWidth,
                            ) { v, _ -> swipeOffset = v }
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onRemove()
                        } else {
                            animate(
                                initialValue = swipeOffset,
                                targetValue = 0f,
                                animationSpec = spring(),
                            ) { v, _ -> swipeOffset = v }
                        }
                    }
                }
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Box(modifier = Modifier.offset { IntOffset(swipeOffset.toInt(), 0) }) {
            ListItem(
                onClick = onPlay,
                shapes = ListItemDefaults.shapes(
                    shape = MaterialTheme.shapes.small,
                    focusedShape = MaterialTheme.shapes.small,
                    pressedShape = MaterialTheme.shapes.small,
                ),
                content = {
                    Text(
                        text = file.name,
                        maxLines = 1,
                    )
                },
                supportingContent = {
                    Text(text = file.moduleType.ifBlank { file.fileExtension.uppercase() })
                },
                leadingContent = {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                trailingContent = {
                    IconButton(
                        modifier = Modifier.draggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(
                                    HapticFeedbackType.GestureThresholdActivate
                                )
                            },
                            onDragStopped = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            },
                        ),
                        onClick = {},
                        content = {
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = null,
                            )
                        }
                    )
                }
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    val fakeScope = remember {
        object : ReorderableCollectionItemScope {
            override fun Modifier.draggableHandle(
                enabled: Boolean,
                interactionSource: MutableInteractionSource?,
                onDragStarted: (startedPosition: Offset) -> Unit,
                onDragStopped: () -> Unit,
                dragGestureDetector: DragGestureDetector
            ): Modifier = this

            override fun Modifier.longPressDraggableHandle(
                enabled: Boolean,
                interactionSource: MutableInteractionSource?,
                onDragStarted: (startedPosition: Offset) -> Unit,
                onDragStopped: () -> Unit
            ): Modifier = this
        }
    }

    AppTheme {
        with(fakeScope) {
            PlaylistEntryItem(
                index = 1,
                file = ModuleEntity(
                    filePath = "content://preview/1",
                    filename = "aegis_-_beneath_the_fallen_stars.it",
                    fileExtension = "it",
                    fileSize = 1_820_792L,
                    moduleName = "Beneath the Fallen Stars",
                    moduleType = "Impulse Tracker",
                ),
                onPlay = {},
                onRemove = {},
            )
        }
    }
}
