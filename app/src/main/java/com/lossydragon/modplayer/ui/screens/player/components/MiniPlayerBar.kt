package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.media3.common.Player
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlin.math.abs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModVars

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val duration = state.frameInfo.totalTime.toFloat().coerceAtLeast(1f)
    val progress = (state.frameInfo.time.toFloat() / duration).coerceIn(0f, 1f)
    val isPlaying = state.status == PlaybackStatus.PLAYING

    var componentWidth by remember { mutableFloatStateOf(0f) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    val dismissDirection by remember {
        derivedStateOf {
            when {
                swipeOffset > 2f -> SwipeToDismissBoxValue.StartToEnd
                swipeOffset < -2f -> SwipeToDismissBoxValue.EndToStart
                else -> SwipeToDismissBoxValue.Settled
            }
        }
    }

    val hasNext = when {
        state.queue.isEmpty() -> false

        state.repeatMode == Player.REPEAT_MODE_ALL ||
            state.repeatMode == Player.REPEAT_MODE_ONE -> true

        else -> state.currentQueueIndex < state.queue.lastIndex
    }
    val hasPrev = when {
        state.queue.isEmpty() -> false

        state.repeatMode == Player.REPEAT_MODE_ALL ||
            state.repeatMode == Player.REPEAT_MODE_ONE -> true

        else -> state.currentQueueIndex > 0
    }

    LaunchedEffect(dismissDirection) {
        if (dismissDirection != SwipeToDismissBoxValue.Settled) {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        }
    }

    val bgAlignment = when (dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
    }

    Box(
        modifier = Modifier
            .onSizeChanged { componentWidth = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    swipeOffset = (swipeOffset + delta).coerceIn(-componentWidth, componentWidth)
                },
                onDragStopped = {
                    scope.launch {
                        if (componentWidth > 0f && abs(swipeOffset) / componentWidth >= 0.75f) {
                            val target = if (swipeOffset > 0f) componentWidth else -componentWidth
                            animate(initialValue = swipeOffset, targetValue = target) { v, _ ->
                                swipeOffset = v
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDismiss()
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
                .padding(6.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 20.dp),
            contentAlignment = bgAlignment,
        ) {
            Icon(
                imageVector = Icons.Default.StopCircle,
                contentDescription = stringResource(R.string.desc_stop),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Box(modifier = Modifier.offset { IntOffset(swipeOffset.toInt(), 0) }) {
            ListItem(
                modifier = Modifier.padding(6.dp),
                shapes = ListItemDefaults.shapes(
                    shape = MaterialTheme.shapes.small,
                    focusedShape = MaterialTheme.shapes.small,
                    pressedShape = MaterialTheme.shapes.small,
                ),
                onClick = onTap,
                colors = ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.onSecondary
                ),
                leadingContent = {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick = onPrevious,
                            enabled = hasPrev,
                            content = {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = null
                                )
                            }
                        )
                        IconButton(
                            onClick = onPlayPause,
                            content = {
                                val icon = if (isPlaying) {
                                    Icons.Default.Pause
                                } else {
                                    Icons.Default.PlayArrow
                                }
                                Icon(imageVector = icon, contentDescription = null)
                            }
                        )
                        IconButton(
                            onClick = onNext,
                            enabled = hasNext,
                            content = {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                },
                content = {
                    Text(
                        text = state.modVars.modName.ifEmpty {
                            state.currentModule?.name.orEmpty()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Column {
                        Text(
                            text = state.modVars.modType,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        LinearWavyProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            amplitude = if (isPlaying) {
                                WavyProgressIndicatorDefaults.indicatorAmplitude
                            } else {
                                { 0f }
                            }
                        )
                    }
                }
            )
        }
    }
}

/***********
 * Preview *
 ***********/

private data class MiniPlayerPreviewState(
    val description: String,
    val state: PlayerUiState
)

private class MiniPlayerPreviewParameter : PreviewParameterProvider<MiniPlayerPreviewState> {

    private val sampleFiles = persistentListOf(
        ModuleEntity(
            fileExtension = "far",
            moduleName = "A Journey Into Sound",
            moduleType = "Farandole Composer",
        ),
        ModuleEntity(
            fileExtension = "it",
            moduleName = "Beneath the Fallen Stars",
            moduleType = "Impulse Tracker",
        ),
        ModuleEntity(
            fileExtension = "mod",
            moduleName = "alpharapii",
            moduleType = "Amiga Protracker/Compatible",
        ),
        ModuleEntity(
            fileExtension = "mod",
            moduleName = "Chiptune No. 184",
            moduleType = "Amiga Protracker/Compatible",
        ),
    )

    private val itModVars = ModVars(name = "Beneath the Fallen Stars", type = "Impulse Tracker")

    private val baseFrameInfo = FrameInfo(time = 47_000, totalTime = 237_000)

    override val values = sequenceOf(
        MiniPlayerPreviewState(
            description = "Playing - mid queue",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[1],
                currentQueueIndex = 1,
                queue = sampleFiles,
                modVars = itModVars,
                frameInfo = baseFrameInfo,
            ),
        ),
        MiniPlayerPreviewState(
            description = "Paused - mid queue",
            state = PlayerUiState(
                status = PlaybackStatus.PAUSED,
                currentModule = sampleFiles[1],
                currentQueueIndex = 1,
                queue = sampleFiles,
                modVars = itModVars,
                frameInfo = baseFrameInfo,
            ),
        ),
        MiniPlayerPreviewState(
            description = "Playing - first track (no prev)",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[0],
                currentQueueIndex = 0,
                queue = sampleFiles,
                modVars = itModVars,
                frameInfo = baseFrameInfo.copy(time = 12_000),
            ),
        ),
        MiniPlayerPreviewState(
            description = "Playing - last track (no next)",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[3],
                currentQueueIndex = 3,
                queue = sampleFiles,
                modVars = itModVars,
                frameInfo = baseFrameInfo.copy(time = 190_000),
            ),
        ),
        MiniPlayerPreviewState(
            description = "Playing - repeat all",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[3],
                currentQueueIndex = 3,
                queue = sampleFiles,
                repeatMode = Player.REPEAT_MODE_ALL,
                modVars = itModVars,
                frameInfo = baseFrameInfo,
            ),
        ),
    )

    override fun getDisplayName(index: Int): String {
        return values.toList()[index].description
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview(
    @PreviewParameter(MiniPlayerPreviewParameter::class) params: MiniPlayerPreviewState
) {
    AppTheme {
        MiniPlayerBar(
            state = params.state,
            onTap = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onDismiss = {},
        )
    }
}
