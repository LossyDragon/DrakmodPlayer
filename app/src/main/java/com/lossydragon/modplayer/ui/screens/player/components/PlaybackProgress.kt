package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.formatMs
import com.lossydragon.native.model.FrameInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Collects the 50 Hz frame flow itself so only this row recomposes per frame. */
@Composable
internal fun PlaybackProgress(
    frameInfo: StateFlow<FrameInfo>,
    onSeek: (Long) -> Unit
) {
    val fi by frameInfo.collectAsStateWithLifecycle()
    val duration = fi.totalTime.toFloat().coerceAtLeast(1f)

    var isSeeking by remember { mutableStateOf(false) }
    val state = rememberSliderState()
    // The engine drives the thumb except while the user is dragging it.
    LaunchedEffect(Unit) {
        snapshotFlow { fi }.collect { info ->
            if (!isSeeking) {
                val total = info.totalTime.toFloat().coerceAtLeast(1f)
                state.value = (info.time.toFloat() / total).coerceIn(0f, 1f)
            }
        }
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
                    fi.time.toLong().formatMs()
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
                text = fi.totalTime.toLong().formatMs(),
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
                frameInfo = MutableStateFlow(
                    FrameInfo(
                        time = 47_000,
                        totalTime = 237_000,
                    )
                ),
                onSeek = {}
            )
        }
    }
}
