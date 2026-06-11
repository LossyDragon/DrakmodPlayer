package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.alorma.compose.settings.ui.expressive.SettingsSwitch
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceItem
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.native.RenderingBackend
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferencePlayer(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val autoResume by prefs.getAutoResumeFlow()
        .collectAsStateWithLifecycle(initialValue = false)
    val rowNumbers by prefs.getRowNumbersFlow()
        .collectAsStateWithLifecycle(initialValue = false)
    val renderingBackend by prefs.getRenderingBackendFlow()
        .collectAsStateWithLifecycle(initialValue = RenderingBackend.OPENMPT)

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
                    Text(
                        text = stringResource(id)
                    )
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
