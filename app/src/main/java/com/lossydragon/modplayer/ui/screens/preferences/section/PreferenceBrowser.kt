package com.lossydragon.modplayer.ui.screens.preferences.section

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.materialkolor.ktx.toHex
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceBrowser(colors: ListItemColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)

                scope.launch { prefs.setLastDirectoryUri(it.toString()) }
            }
        }
    )
    val currentFolder by remember(prefs) {
        prefs.getLastDirectoryFlow().map { it.orEmpty() }
    }.collectAsStateWithLifecycle("")

    PreferenceSection(
        title = {
            Text(
                text = "File Browser",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = "Set start location") },
                subtitle = { Text(text = currentFolder) },
                shapes = ListItemDefaults.segmentedShapes(0, 0),
                colors = colors,
                onClick = { folderPicker.launch(null) },
            )
        }
    )
}
