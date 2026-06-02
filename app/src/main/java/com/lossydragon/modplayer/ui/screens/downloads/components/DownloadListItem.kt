package com.lossydragon.modplayer.ui.screens.downloads.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.fromHtml

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DownloadListItem(
    text: String,
    supportingText: String? = null,
    trailingText: String? = null,
    leadingText: String? = null,
    onClick: () -> Unit
) {
    ListItem(
        onClick = onClick,
        shapes = ListItemDefaults.shapes(
            shape = MaterialTheme.shapes.small,
            focusedShape = MaterialTheme.shapes.small,
            pressedShape = MaterialTheme.shapes.small,
        ),
        leadingContent = leadingText?.let { { FormatsIcon(text = it) } },
        content = {
            Text(
                text = text.fromHtml().ifBlank { stringResource(R.string.untitled) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = supportingText?.let {
            {
                Text(
                    text = it.fromHtml(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = trailingText?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
    )
}

@Composable
private fun FormatsIcon(text: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color(0xff404040), shape = MaterialTheme.shapes.extraSmall)
            .border(
                2.dp,
                Color(0xff808080),
                shape = MaterialTheme.shapes.extraSmall
            ),
        contentAlignment = Alignment.Center,
        content = {
            Text(
                text = text,
                fontSize = 11.sp,
                color = Color.White
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
