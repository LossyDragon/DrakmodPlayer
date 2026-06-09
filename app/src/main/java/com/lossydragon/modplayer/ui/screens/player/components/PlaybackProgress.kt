package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.formatMs
import org.helllabs.libxmp.model.FrameInfo

@Composable
internal fun PlaybackProgress(
    state: PlayerUiState,
    onSeek: (Long) -> Unit
) {
    val duration = state.frameInfo.totalTime.toFloat().coerceAtLeast(1f)

    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val displayValue = if (isSeeking) {
        seekPosition
    } else {
        (state.frameInfo.time.toFloat() / duration).coerceIn(0f, 1f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            Text(
                modifier = Modifier.padding(horizontal = 6.dp),
                text = if (isSeeking) {
                    (seekPosition * duration).toLong().formatMs()
                } else {
                    state.frameInfo.time.toLong().formatMs()
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Slider(
                modifier = Modifier.weight(1f),
                value = displayValue,
                onValueChange = {
                    isSeeking = true
                    seekPosition = it
                },
                onValueChangeFinished = {
                    onSeek((seekPosition * duration).toLong())
                    isSeeking = false
                },
            )

            Text(
                modifier = Modifier.padding(horizontal = 6.dp),
                text = state.frameInfo.totalTime.toLong().formatMs(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            PlaybackProgress(
                state = PlayerUiState(
                    frameInfo = FrameInfo(
                        time = 47_000,
                        totalTime = 237_000,
                    )
                ),
                onSeek = {}
            )
        }
    }
}
