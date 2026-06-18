package com.lossydragon.modplayer.ui.screens.playlists.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

@Composable
internal fun NewPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    onError: (String) -> Unit,
    initialName: String = "",
    initialComment: String = ""
) {
    val resource = LocalResources.current
    val isEditing = initialName.isNotBlank()

    var name by remember { mutableStateOf(initialName) }
    var comment by remember { mutableStateOf(initialComment) }

    val dismiss: () -> Unit = {
        name = ""
        comment = ""
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismiss,
        icon = {
            Icon(
                imageVector = if (isEditing) {
                    Icons.Default.Edit
                } else {
                    Icons.AutoMirrored.Filled.PlaylistAdd
                },
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = stringResource(
                    if (isEditing) {
                        R.string.dialog_title_edit_playlist
                    } else {
                        R.string.dialog_title_new_playlist
                    }
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = stringResource(R.string.name)) },
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(text = stringResource(R.string.comment)) },
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        val text = resource.getString(R.string.error_playlist_no_name)
                        onError(text)
                    } else {
                        onCreate(name, comment)
                    }
                    dismiss()
                },
                content = {
                    Text(
                        text = stringResource(
                            if (isEditing) {
                                R.string.save
                            } else {
                                R.string.create
                            }
                        )
                    )
                }
            )
        },
        dismissButton = {
            TextButton(
                onClick = dismiss,
                content = { Text(text = stringResource(R.string.cancel)) }
            )
        }
    )
}

@Preview
@Composable
private fun Preview_Edit() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NewPlaylistDialog(
                onDismiss = {},
                onCreate = { _, _ -> },
                onError = {},
                initialName = stringResource(R.string.app_name),
                initialComment = stringResource(R.string.app_name)
            )
        }
    }
}

@Preview
@Composable
private fun Preview_New() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NewPlaylistDialog(
                onDismiss = {},
                onCreate = { _, _ -> },
                onError = {},
            )
        }
    }
}
