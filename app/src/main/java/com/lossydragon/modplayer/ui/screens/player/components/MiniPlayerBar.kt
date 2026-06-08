package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.core.net.toUri
import androidx.media3.common.Player
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val duration = state.frameInfo.totalTime.toFloat().coerceAtLeast(1f)
    val progress = (state.frameInfo.time.toFloat() / duration).coerceIn(0f, 1f)
    val isPlaying = state.status == PlaybackStatus.PLAYING

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
                text = state.modVars.name.trim(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = state.modVars.type.trim(),
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

@Preview
@Composable
private fun PreviewPlaying() {
    AppTheme {
        MiniPlayerBar(
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = ModuleEntity(
                    filePath = "content://preview/1",
                    filename = "a_journey_into_sound.far",
                    fileExtension = "far",
                    fileSize = 123456L,
                ),
            ),
            onTap = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
        )
    }
}

// TODO fix preview

@Preview
@Composable
private fun PreviewPaused() {
    AppTheme {
        MiniPlayerBar(
            state = PlayerUiState(
                status = PlaybackStatus.PAUSED,
                currentModule = ModuleEntity(
                    filePath = "content://preview/1",
                    filename = "aegis_-_beneath_the_fallen_stars.it",
                    fileExtension = "it",
                    fileSize = 1820792L,
                ),
            ),
            onTap = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
        )
    }
}
