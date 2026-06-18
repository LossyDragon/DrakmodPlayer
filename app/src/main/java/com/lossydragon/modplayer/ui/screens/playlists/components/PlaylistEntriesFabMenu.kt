package com.lossydragon.modplayer.ui.screens.playlists.components

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistEntriesFabMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    FloatingActionButtonMenu(
        modifier = Modifier.offset(x = 16.dp, y = 16.dp),
        expanded = expanded,
        button = {
            FloatingActionButton(
                onClick = onExpand,
                shape = MaterialTheme.shapes.small,
                content = {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.PlaylistRemove
                        } else {
                            Icons.AutoMirrored.Filled.PlaylistPlay
                        },
                        contentDescription = null,
                    )
                }
            )
        },
        content = {
            FloatingActionButtonMenuItem(
                onClick = onShuffle,
                text = { Text(text = stringResource(R.string.fab_play_shuffled)) },
                icon = { Icon(imageVector = Icons.Default.Shuffle, contentDescription = null) }
            )
            FloatingActionButtonMenuItem(
                onClick = onPlayAll,
                text = { Text(text = stringResource(R.string.fab_play_all)) },
                icon = { Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null) }
            )
        },
    )
}

private class PlaylistEntriesPreviewParameter : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview
@Composable
private fun Preview(
    @PreviewParameter(PlaylistEntriesPreviewParameter::class) expanded: Boolean
) {
    AppTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                PlaylistEntriesFabMenu(
                    expanded = expanded,
                    onExpand = {},
                    onPlayAll = {},
                    onShuffle = {},
                )
            },
            content = {}
        )
    }
}
