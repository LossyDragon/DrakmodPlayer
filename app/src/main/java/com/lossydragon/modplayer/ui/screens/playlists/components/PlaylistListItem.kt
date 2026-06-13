package com.lossydragon.modplayer.ui.screens.playlists.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.toReadableDate

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistListItem(
    item: PlaylistEntity,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        onClick = onSelect,
        shapes = ListItemDefaults.shapes(
            shape = MaterialTheme.shapes.small,
            focusedShape = MaterialTheme.shapes.small,
            pressedShape = MaterialTheme.shapes.small,
        ),
        content = { Text(text = item.name) },
        supportingContent = {
            Column {
                if (item.comment.isNotBlank()) Text(text = item.comment)
                Text(
                    text = "Created: ${item.createdAt.toReadableDate()}",
                    fontSize = 10.sp,
                )
            }
        },
        leadingContent = {
            Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.delete)) },
                        onClick = {
                            expanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.rename)) },
                        onClick = {
                            expanded = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PlaylistListItem(
            item = PlaylistEntity(
                name = "Name Name Name",
                comment = "Comment Comment"
            ),
            onSelect = {},
            onDelete = {},
            onRename = {},
        )
    }
}
