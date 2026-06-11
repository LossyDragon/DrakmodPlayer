package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceInfo(
    colors: ListItemColors,
    onMptFormats: () -> Unit,
    onXmpFormats: () -> Unit,
    onAbout: () -> Unit
) {
    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_about),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_formats_mpt)) },
                subtitle = { Text(text = stringResource(R.string.pref_formats_mpt_desc)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 3),
                onClick = onMptFormats
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_formats_xmp)) },
                subtitle = { Text(text = stringResource(R.string.pref_formats_xmp_desc)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 3),
                onClick = onXmpFormats
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_about)) },
                subtitle = { Text(text = stringResource(R.string.pref_about_desc)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(2, 3),
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
            PreferenceInfo(colors = colors, onMptFormats = {}, onXmpFormats = {}, onAbout = {})
        }
    }
}
