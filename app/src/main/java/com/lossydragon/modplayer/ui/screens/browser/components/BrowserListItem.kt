package com.lossydragon.modplayer.ui.screens.browser.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import com.lossydragon.modplayer.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BrowserListItem(
    title: String,
    comment: String? = null,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
    onTrailingClick: (() -> Unit)? = null
) {
    ListItem(
        onClick = onClick,
        shapes = ListItemDefaults.shapes(
            shape = MaterialTheme.shapes.small,
            focusedShape = MaterialTheme.shapes.small,
            pressedShape = MaterialTheme.shapes.small,
        ),
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        supportingContent = comment?.let { { Text(text = it) } },
        leadingContent = leadingIcon?.let { { Icon(imageVector = it, contentDescription = null) } },
        trailingContent = onTrailingClick?.let {
            {
                IconButton(
                    onClick = { it.invoke() },
                    content = {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null
                        )
                    }
                )
            }
        },
    )
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
