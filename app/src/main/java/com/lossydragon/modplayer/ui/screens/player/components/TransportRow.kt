package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TransportRow(
    status: PlaybackStatus,
    hasNext: Boolean,
    hasPrev: Boolean,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onQueueSheet: () -> Unit
) {
    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onStop,
            content = {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.desc_stop_button),
                    modifier = Modifier.size(24.dp),
                )
            }
        )

        IconButton(
            onClick = onPrev,
            enabled = hasPrev,
            content = {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.desc_previous),
                    modifier = Modifier.size(32.dp),
                )
            }
        )

        FloatingActionButton(
            onClick = onPlayPause,
            content = {
                if (status == PlaybackStatus.PLAYING) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = stringResource(R.string.desc_pause),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.play),
                    )
                }
            }
        )

        IconButton(
            onClick = onNext,
            enabled = hasNext,
            content = {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.desc_next),
                    modifier = Modifier.size(32.dp),
                )
            }
        )

        IconButton(
            onClick = onQueueSheet,
            content = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.desc_open_queue),
                    modifier = Modifier.size(24.dp),
                )
            }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            TransportRow(
                status = PlaybackStatus.PLAYING,
                hasNext = false,
                hasPrev = true,
                onStop = {},
                onPrev = {},
                onPlayPause = {},
                onNext = {},
                onQueueSheet = {},
            )
        }
    }
}
