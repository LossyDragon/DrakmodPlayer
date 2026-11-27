package com.lossydragon.modplayer.ui.screens.downloads.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.components.LeadingBadge
import com.lossydragon.modplayer.ui.components.segmentedRowColors
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.fromHtml

@Composable
internal fun DownloadListItem(
    modifier: Modifier = Modifier,
    text: String,
    supportingText: String? = null,
    trailingText: String? = null,
    leadingText: String? = null,
    leadingIcon: ImageVector? = null,
    segmentedShapes: ListItemShapes? = null,
    onClick: () -> Unit
) {
    val leadingContent: (@Composable () -> Unit)? = when {
        leadingText != null -> {
            {
                if (segmentedShapes != null) {
                    LeadingBadge(text = leadingText)
                } else {
                    FormatsIcon(text = leadingText)
                }
            }
        }

        leadingIcon != null -> {
            { LeadingBadge(icon = leadingIcon) }
        }

        else -> null
    }
    val content: @Composable () -> Unit = {
        Text(
            text = text.fromHtml().ifBlank { stringResource(R.string.untitled) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    val supportingContent: (@Composable () -> Unit)? = supportingText?.let {
        {
            Text(
                text = it.fromHtml(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    val trailingContent: (@Composable () -> Unit)? = trailingText?.let {
        {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    if (segmentedShapes != null) {
        SegmentedListItem(
            modifier = modifier,
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
            modifier = modifier,
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

@Composable
private fun FormatsIcon(text: String) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(2.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
        content = {
            Text(
                text = text,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    )
}

@Preview
@Composable
private fun ArtistListItem_Preview() {
    AppTheme {
        Surface {
            DownloadListItem(text = "Artist Alias", onClick = {})
        }
    }
}

@Preview
@Composable
private fun ModuleListItem_Preview() {
    AppTheme {
        Surface {
            DownloadListItem(
                text = stringResource(R.string.app_name),
                supportingText = stringResource(R.string.app_name),
                leadingText = "669",
                trailingText = stringResource(R.string.size_kb, 99999),
                onClick = {},
            )
        }
    }
}

@Preview
@Composable
private fun SegmentedListItem_Preview() {
    AppTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                content = {
                    repeat(3) { index ->
                        DownloadListItem(
                            text = stringResource(R.string.app_name),
                            supportingText = stringResource(R.string.app_name),
                            leadingText = "669",
                            trailingText = stringResource(R.string.size_kb, 99999),
                            segmentedShapes = ListItemDefaults.segmentedShapes(index, 3),
                            onClick = {},
                        )
                    }
                }
            )
        }
    }
}
