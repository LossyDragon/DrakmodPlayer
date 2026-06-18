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
internal fun PlaylistsFabMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onNewPlaylist: () -> Unit
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
                onClick = onImport,
                text = { Text(text = stringResource(R.string.fab_import_playlists)) },
                icon = { Icon(imageVector = Icons.Default.Download, contentDescription = null) }
            )
            FloatingActionButtonMenuItem(
                onClick = onExport,
                text = { Text(text = stringResource(R.string.fab_export_playlists)) },
                icon = { Icon(imageVector = Icons.Default.Upload, contentDescription = null) }
            )
            FloatingActionButtonMenuItem(
                onClick = onNewPlaylist,
                text = { Text(text = stringResource(R.string.fab_new_playlist)) },
                icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) }
            )
        },
    )
}

private class PlaylistsPreviewParameter : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview
@Composable
private fun Preview(
    @PreviewParameter(PlaylistsPreviewParameter::class) expanded: Boolean
) {
    AppTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                PlaylistsFabMenu(
                    expanded = expanded,
                    onExpand = {},
                    onImport = {},
                    onExport = {},
                    onNewPlaylist = {},
                )
            },
            content = {}
        )
    }
}
