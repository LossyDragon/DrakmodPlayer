package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.alorma.compose.settings.ui.expressive.SettingsSwitch
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.components.collectPref
import com.lossydragon.modplayer.ui.components.rememberAppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceItem
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.renderer.RenderingBackend
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

private val backendOptions = persistentListOf(
    PreferenceItem(
        key = RenderingBackend.OPENMPT.id.toString(),
        title = R.string.pref_backend_libopenmpt,
        description = R.string.pref_backend_desc,
    ),
    PreferenceItem(
        key = RenderingBackend.LIBXMP.id.toString(),
        title = R.string.pref_backend_libxmp,
        description = R.string.pref_backend_desc,
    ),
)

@Composable
fun PreferencePlayer(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = rememberAppPreferences()

    val autoResume by prefs.collectPref(false) { getAutoResumeFlow() }
    val rowNumbers by prefs.collectPref(false) { getRowNumbersFlow() }
    val renderingBackend by prefs.collectPref(RenderingBackend.OPENMPT) {
        getRenderingBackendFlow()
    }

    var isBackendShowing by remember { mutableStateOf(false) }
    if (isBackendShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = renderingBackend.id.toString(),
            items = backendOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()
                    ?.let(RenderingBackend::fromId)
                    ?.let { scope.launch { prefs.setRenderingBackend(it) } }
                isBackendShowing = false
            },
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_player),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_backend)) },
                subtitle = { Text(text = stringResource(R.string.pref_backend_desc)) },
                action = {
                    val id = when (renderingBackend) {
                        RenderingBackend.OPENMPT -> R.string.pref_backend_libopenmpt
                        RenderingBackend.LIBXMP -> R.string.pref_backend_libxmp
                        else -> R.string.pref_backend_invalid
                    }
                    Text(text = stringResource(id))
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 3),
                onClick = { isBackendShowing = true }
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_auto_resume)) },
                subtitle = { Text(text = stringResource(R.string.pref_auto_resume_desc)) },
                state = autoResume,
                onCheckedChange = { scope.launch { prefs.setAutoResume(it) } },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 3),
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_show_row_numbers)) },
                subtitle = { Text(text = stringResource(R.string.pref_show_row_numbers_desc)) },
                state = rowNumbers,
                onCheckedChange = { scope.launch { prefs.setRowNumbers(it) } },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(2, 3),
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            val colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            PreferencePlayer(colors = colors)
        }
    }
}
