package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.data.ModuleRepository
import com.lossydragon.modplayer.ui.components.rememberAppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceBrowser(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val isPreview = LocalView.current.isInEditMode

    val prefs = rememberAppPreferences()
    val repo = if (isPreview) null else koinInject<ModuleRepository>()

    // setRootFolder persists the grant, stores the uri and starts the index.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) scope.launch { repo?.setRootFolder(uri) }
        }
    )
    val currentFolder by remember(prefs) {
        prefs.getLastDirectoryFlow().map { it.orEmpty() }
    }.collectAsStateWithLifecycle("")

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_browser),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_start_location)) },
                subtitle = {
                    val emptyText = stringResource(R.string.not_set)
                    Text(text = currentFolder.ifEmpty { emptyText })
                },
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                colors = colors,
                onClick = { folderPicker.launch(null) },
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
            PreferenceBrowser(colors)
        }
    }
}
