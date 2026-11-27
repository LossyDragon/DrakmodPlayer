package com.lossydragon.modplayer.ui.screens.browser.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.components.LeadingBadge
import com.lossydragon.modplayer.ui.components.segmentedRowColors
import com.lossydragon.modplayer.ui.theme.AppTheme

/** Segmented when [segmentedShapes] is given, otherwise a plain row. */
@Composable
internal fun BrowserListItem(
    title: String,
    comment: String? = null,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    segmentedShapes: ListItemShapes? = null,
    onClick: () -> Unit,
    onTrailingClick: (() -> Unit)? = null
) {
    val content: @Composable () -> Unit = {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val supportingContent: (@Composable () -> Unit)? = comment?.let {
        {
            Text(
                text = it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val leadingContent: (@Composable () -> Unit)? = leadingIcon?.let {
        {
            if (segmentedShapes != null) {
                LeadingBadge(icon = it)
            } else {
                Icon(imageVector = it, contentDescription = null)
            }
        }
    }
    val trailingContent: (@Composable () -> Unit)? = onTrailingClick?.let {
        {
            IconButton(
                onClick = it,
                content = {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.dialog_title_pick_playlist)
                    )
                }
            )
        }
    }

    if (segmentedShapes != null) {
        SegmentedListItem(
            modifier = modifier.fillMaxWidth(),
            onClick = onClick,
            shapes = segmentedShapes,
            colors = segmentedRowColors(),
            leadingContent = leadingContent,
            content = content,
            supportingContent = supportingContent,
            trailingContent = trailingContent,
        )
    } else {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            onClick = onClick,
            shapes = ListItemDefaults.shapes(
                shape = MaterialTheme.shapes.small,
                focusedShape = MaterialTheme.shapes.small,
                pressedShape = MaterialTheme.shapes.small,
            ),
            leadingContent = leadingContent,
            content = content,
            supportingContent = supportingContent,
            trailingContent = trailingContent,
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            BrowserListItem(
                title = "Title Item",
                comment = "Comment Item",
                leadingIcon = Icons.Default.AudioFile,
                onClick = {},
                onTrailingClick = {},
            )
        }
    }
}

@Preview
@Composable
private fun Preview_Segmented() {
    AppTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                content = {
                    repeat(3) { index ->
                        BrowserListItem(
                            title = "Title Item",
                            comment = "Comment Item",
                            leadingIcon = Icons.Default.AudioFile,
                            segmentedShapes = ListItemDefaults.segmentedShapes(index, 3),
                            onClick = {},
                            onTrailingClick = {},
                        )
                    }
                }
            )
        }
    }
}
