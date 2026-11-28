package com.lossydragon.modplayer.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

/** Floating toolbar with a Play FAB; [content] holds the secondary actions. */
@Composable
fun PlayFloatingToolbar(
    expanded: Boolean,
    onPlay: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    HorizontalFloatingToolbar(
        expanded = expanded,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        floatingActionButton = {
            FloatingToolbarDefaults.StandardFloatingActionButton(
                onClick = onPlay,
                content = {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.fab_play_all)
                    )
                }
            )
        },
        content = content,
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PlayFloatingToolbar(
            expanded = true,
            onPlay = {},
            content = {
                IconButton(
                    onClick = {},
                    content = {
                        Icon(imageVector = Icons.Default.Shuffle, contentDescription = null)
                    }
                )

                IconButton(
                    onClick = {},
                    content = {
                        Icon(imageVector = Icons.Default.Repeat, contentDescription = null)
                    }
                )
            }
        )
    }
}
