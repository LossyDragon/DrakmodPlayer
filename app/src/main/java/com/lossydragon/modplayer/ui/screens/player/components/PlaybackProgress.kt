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
import com.lossydragon.renderer.model.FrameInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val THUMB_STEPS = 500

/** Collects the 50 Hz frame flow itself; the thumb follows it, the labels recompose per second. */
@Composable
internal fun PlaybackProgress(
    frameInfo: StateFlow<FrameInfo>,
    onSeek: (Long) -> Unit
) {
    val fi = frameInfo.collectAsStateWithLifecycle()
    val totalMs by remember(fi) { derivedStateOf { fi.value.totalTime } }
    val duration = totalMs.toFloat().coerceAtLeast(1f)

    var isSeeking by remember { mutableStateOf(false) }
    val state = rememberSliderState()
    // The engine drives the thumb except while the user is dragging it.
    // Stepped: each thumb move recomposes the whole Slider.
    LaunchedEffect(fi) {
        snapshotFlow {
            val info = fi.value
            val total = info.totalTime.toFloat().coerceAtLeast(1f)
            (info.time / total * THUMB_STEPS).toInt().coerceIn(0, THUMB_STEPS)
        }.collect { step ->
            if (!isSeeking) state.value = step / THUMB_STEPS.toFloat()
        }
    }
    val shownSec by remember(fi) {
        derivedStateOf {
            val info = fi.value
            val ms = if (isSeeking) {
                state.value * info.totalTime.toFloat().coerceAtLeast(1f)
            } else {
                info.time.toFloat()
            }
            (ms / 1000f).toLong()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            Text(
                modifier = Modifier.padding(horizontal = 6.dp),
                text = (shownSec * 1000L).formatMs(),
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
                text = totalMs.toLong().formatMs(),
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
