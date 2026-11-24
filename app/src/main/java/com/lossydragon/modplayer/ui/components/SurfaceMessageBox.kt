package com.lossydragon.modplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

@Composable
fun SurfaceMessageBox(
    icon: ImageVector? = null,
    title: String,
    message: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        content = {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = {
                    icon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme(darkTheme = true) {
        SurfaceMessageBox(
            icon = Icons.Default.Key,
            title = stringResource(R.string.search_no_api_title),
            message = stringResource(R.string.search_no_api_message),
        )
    }
}
