package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.formatMs
import com.lossydragon.native.model.FrameInfo

@Composable
internal fun PlaybackProgress(
    frameInfo: FrameInfo,
    onSeek: (Long) -> Unit
) {
    val duration = frameInfo.totalTime.toFloat().coerceAtLeast(1f)

    var isSeeking by remember { mutableStateOf(false) }
    val state = rememberSliderState()
    // The engine drives the thumb except while the user is dragging it.
    LaunchedEffect(frameInfo.time, duration) {
        if (!isSeeking) state.value = (frameInfo.time.toFloat() / duration).coerceIn(0f, 1f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            Text(
                modifier = Modifier.padding(horizontal = 6.dp),
                text = if (isSeeking) {
                    (state.value * duration).toLong().formatMs()
                } else {
                    frameInfo.time.toLong().formatMs()
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Slider(
                state = state,
                modifier = Modifier.weight(1f),
                onValueChange = {
                    isSeeking = true
                    state.value = it
                },
                onValueChangeFinished = {
                    onSeek((state.value * duration).toLong())
                    isSeeking = false
                },
            )

            Text(
                modifier = Modifier.padding(horizontal = 6.dp),
                text = frameInfo.totalTime.toLong().formatMs(),
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
                frameInfo = FrameInfo(
                    time = 47_000,
                    totalTime = 237_000,
                ),
                onSeek = {}
            )
        }
    }
}
