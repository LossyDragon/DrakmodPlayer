package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceInfo(
    colors: ListItemColors,
    onFormats: () -> Unit,
    onAbout: () -> Unit
) {
    PreferenceSection(
        title = {
            Text(
                text = "About",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = "Formats") },
                subtitle = { Text(text = "Supported Trackers libxmp can play") },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 2),
                onClick = onFormats
            )
            SettingsMenuLink(
                title = { Text(text = "About") },
                subtitle = { Text(text = "Version information and library credits") },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 2),
                onClick = onAbout
            )
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            val colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            PreferenceInfo(colors = colors, onFormats = {}, onAbout = {})
        }
    }
}
