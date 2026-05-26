package com.lossydragon.modplayer.ui.screens.preferences

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.tooling.preview.*
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceBrowser
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceInfo
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceInterface
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferencePlayer
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PreferencesScreen(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onFormats: () -> Unit,
    onAbout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    var isShowingResetDialog by remember { mutableStateOf(false) }
    if (isShowingResetDialog) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null
                )
            },
            title = { Text(text = "Reset Settings") },
            text = {
                Text(
                    text = "Are you sure you want to reset all settings to their original value?" +
                        "\nThis action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            prefs.resetAll()
                            snackbarHostState.showSnackbar("Reset all settings to default values.")
                        }
                        isShowingResetDialog = false
                    },
                    content = { Text(text = "Confirm") }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { isShowingResetDialog = false },
                    content = { Text(text = "Cancel") }
                )
            }
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Settings") },
                navigationIcon = { BackButton(onBack = onBack) },
                actions = {
                    IconButton(
                        onClick = { isShowingResetDialog = true },
                        content = {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null
                            )
                        }
                    )
                }
            )
        },
        content = { paddingValues ->
            val scrollState = rememberScrollState()
            val colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )

            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                content = {
                    PreferenceInterface(colors)
                    PreferenceBrowser(colors)
                    PreferencePlayer(colors)
                    PreferenceInfo(
                        colors = colors,
                        onFormats = onFormats,
                        onAbout = onAbout
                    )
                }
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PreferencesScreen(
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onAbout = {},
            onFormats = {},
        )
    }
}
