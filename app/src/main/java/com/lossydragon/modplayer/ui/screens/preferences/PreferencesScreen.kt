package com.lossydragon.modplayer.ui.screens.preferences

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.core.FileLoggingTree
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.ConfirmDialog
import com.lossydragon.modplayer.ui.components.rememberAppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceBrowser
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceDownloads
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceInfo
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceInterface
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceOboe
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferencePlayer
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceXmp
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
internal fun PreferencesScreen(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onMptFormats: () -> Unit,
    onXmpFormats: () -> Unit,
    modifier: Modifier = Modifier,
    onAbout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val resource = LocalResources.current
    val prefs = rememberAppPreferences()
    val fileLoggingTree = if (LocalView.current.isInEditMode) {
        FileLoggingTree(LocalContext.current)
    } else {
        koinInject<FileLoggingTree>()
    }

    var isShowingResetDialog by remember { mutableStateOf(false) }
    if (isShowingResetDialog) {
        ConfirmDialog(
            icon = Icons.Default.RestartAlt,
            title = stringResource(R.string.dialog_title_pref_reset),
            text = stringResource(R.string.dialog_message_pref_reset),
            confirmLabel = stringResource(R.string.confirm),
            onConfirm = {
                scope.launch {
                    val text = resource.getString(R.string.snack_pref_reset_ok)
                    prefs.resetAll()
                    snackbarHostState.showSnackbar(text)
                }
            },
            onDismiss = { isShowingResetDialog = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.title_preferences)) },
                navigationIcon = { BackButton(onBack = onBack) },
                actions = {
                    IconButton(
                        onClick = { isShowingResetDialog = true },
                        content = {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = stringResource(R.string.desc_preferences_reset)
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
                    PreferenceDownloads(colors)
                    PreferencePlayer(colors)
                    PreferenceInfo(
                        colors = colors,
                        onMptFormats = onMptFormats,
                        onXmpFormats = onXmpFormats,
                        onAbout = onAbout,
                        onExportLogs = {
                            scope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    fileLoggingTree.exportToDownloads()
                                }
                                snackbarHostState.showSnackbar(
                                    resource.getString(
                                        if (success) {
                                            R.string.snack_export_logs_ok
                                        } else {
                                            R.string.snack_export_logs_fail
                                        }
                                    )
                                )
                            }
                        }
                    )
                    Text(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.surfaceTint,
                        text = stringResource(R.string.pref_advanced_options)
                    )
                    PreferenceXmp(colors)
                    PreferenceOboe(colors)
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
            onMptFormats = {},
            onXmpFormats = {},
        )
    }
}
