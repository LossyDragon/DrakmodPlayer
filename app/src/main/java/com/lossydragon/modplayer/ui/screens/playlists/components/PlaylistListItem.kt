package com.lossydragon.modplayer.ui.screens.playlists.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.toReadableDate

@Composable
internal fun PlaylistListItem(
    item: PlaylistEntity,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    SegmentedListItem(
        modifier = modifier,
        onClick = onSelect,
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        content = {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (item.comment.isNotBlank()) {
                    Text(
                        text = item.comment,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.playlist_created,
                        item.createdAt.toReadableDate()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
                content = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.desc_more_options)
                    )
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
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                content = {
                    repeat(3) { index ->
                        PlaylistListItem(
                            item = PlaylistEntity(
                                name = "Name Name Name",
                                comment = "Comment Comment"
                            ),
                            shapes = ListItemDefaults.segmentedShapes(index, 3),
                            onSelect = {},
                            onDelete = {},
                            onRename = {},
                        )
                    }
                }
            )
        }
    }
}
