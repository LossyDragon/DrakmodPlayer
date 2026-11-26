package com.lossydragon.modplayer.ui.screens.playlists.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlin.math.abs
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Composable
internal fun ReorderableCollectionItemScope.PlaylistEntryItem(
    index: Int,
    file: ModuleEntity,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val removeLabel = stringResource(R.string.desc_remove_from_playlist)
    var componentWidth by remember { mutableFloatStateOf(0f) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    val isSwiping by remember { derivedStateOf { swipeOffset < -2f } }

    LaunchedEffect(isSwiping) {
        if (isSwiping) hapticFeedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
    }

    Box(
        modifier = modifier
            .onSizeChanged { componentWidth = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    swipeOffset = (swipeOffset + delta).coerceIn(-componentWidth, 0f)
                },
                onDragStopped = {
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
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = shapes.shape,
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
            SegmentedListItem(
                modifier = Modifier.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(removeLabel) {
                            onRemove()
                            true
                        }
                    )
                },
                onClick = onPlay,
                shapes = shapes,
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                content = {
                    Text(
                        text = file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = file.moduleType.ifBlank { file.fileExtension.uppercase() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                        content = {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
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
                                contentDescription = stringResource(R.string.desc_drag_handle),
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
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                onPlay = {},
                onRemove = {},
            )
        }
    }
}
