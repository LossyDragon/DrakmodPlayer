package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.alorma.compose.settings.ui.expressive.SettingsSwitch
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceInfo(
    colors: ListItemColors,
    onMptFormats: () -> Unit,
    onXmpFormats: () -> Unit,
    onAbout: () -> Unit,
    onExportLogs: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }
    val fileLogging by prefs.getFileLoggingFlow().collectAsStateWithLifecycle(false)

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
                shapes = ListItemDefaults.segmentedShapes(0, 5),
                onClick = onMptFormats
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_formats_xmp)) },
                subtitle = { Text(text = stringResource(R.string.pref_formats_xmp_desc)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 5),
                onClick = onXmpFormats
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_about)) },
                subtitle = { Text(text = stringResource(R.string.pref_about_desc)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(2, 5),
                onClick = onAbout
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_logging)) },
                subtitle = { Text(text = stringResource(R.string.pref_logging_desc)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(3, 5),
                state = fileLogging,
                onCheckedChange = { scope.launch { prefs.setFileLogging(it) } }
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_export_logs)) },
                subtitle = { Text(text = stringResource(R.string.pref_export_logs_desc)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(4, 5),
                enabled = fileLogging,
                onClick = onExportLogs,
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
            PreferenceInfo(
                colors = colors,
                onMptFormats = {},
                onXmpFormats = {},
                onAbout = {},
                onExportLogs = {},
            )
        }
    }
}
