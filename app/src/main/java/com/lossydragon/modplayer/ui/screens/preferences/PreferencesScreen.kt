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
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.screens.preferences.section.PreferenceBrowser
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
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
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }
    val fileLoggingTree = if (LocalView.current.isInEditMode) {
        FileLoggingTree(LocalContext.current)
    } else {
        koinInject<FileLoggingTree>()
    }

    var isShowingResetDialog by remember { mutableStateOf(false) }
    if (isShowingResetDialog) {
        AlertDialog(
            onDismissRequest = { isShowingResetDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null
                )
            },
            title = { Text(text = stringResource(R.string.dialog_title_pref_reset)) },
            text = { Text(text = stringResource(R.string.dialog_message_pref_reset)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val text = resource.getString(R.string.snack_pref_reset_ok)
                            prefs.resetAll()
                            snackbarHostState.showSnackbar(text)
                        }
                        isShowingResetDialog = false
                    },
                    content = { Text(text = stringResource(R.string.confirm)) }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { isShowingResetDialog = false },
                    content = { Text(text = stringResource(R.string.cancel)) }
                )
            }
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    PreferencePlayer(colors)
                    PreferenceInfo(
                        colors = colors,
                        onMptFormats = onMptFormats,
                        onXmpFormats = onXmpFormats,
                        onAbout = onAbout,
                        onExportLogs = {
                            scope.launch(Dispatchers.IO) {
                                val success = fileLoggingTree.exportToDownloads()
                                val msg = resource.getString(
                                    if (success) {
                                        R.string.snack_export_logs_ok
                                    } else {
                                        R.string.snack_export_logs_fail
                                    }
                                )
                                withContext(Dispatchers.Main) {
                                    snackbarHostState.showSnackbar(msg)
                                }
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
