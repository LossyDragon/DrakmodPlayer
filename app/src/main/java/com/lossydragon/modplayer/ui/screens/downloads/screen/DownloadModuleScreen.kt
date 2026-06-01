package com.lossydragon.modplayer.ui.screens.downloads.screen

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.model.DownloadStatus
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.model.ModuleResult
import com.lossydragon.modplayer.model.ModuleResultState
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.components.ProgressbarIndicator
import com.lossydragon.modplayer.ui.screens.downloads.components.ModuleDetailLayout
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.ModuleResultViewModel
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.shareLink
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun DownloadModuleScreen(
    moduleId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (Module) -> Unit
) {
    val viewModel = koinViewModel<ModuleResultViewModel>()

    val state by viewModel.state.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Only fetch if we don't already have a module loaded
        // or if the requested ID differs from what's currently loaded
        if (state.result == null || (moduleId >= 0 && state.result?.module?.id != moduleId)) {
            viewModel.getModuleById(moduleId)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(text = stringResource(R.string.dialog_title_delete_module)) },
            text = {
                val name = state.result?.module?.filename
                    .orEmpty()
                    .ifEmpty { stringResource(R.string.untitled) }
                Text(text = stringResource(R.string.dialog_message_delete_module, name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.result?.module?.let { viewModel.deleteModule(it) }
                        showDeleteDialog = false
                    },
                    content = { Text(text = stringResource(R.string.delete)) }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    content = { Text(text = stringResource(R.string.cancel)) }
                )
            }
        )
    }

    DownloadModuleContent(
        modifier = modifier,
        state = state,
        onBack = onBack,
        onShowDialog = { showDeleteDialog = it },
        onPlay = onPlay,
        onDownloadModule = viewModel::downloadModule,
        onRandomModule = viewModel::getRandomModule,
    )
}

@Composable
private fun DownloadModuleContent(
    state: ModuleResultState,
    onBack: () -> Unit,
    onShowDialog: (Boolean) -> Unit,
    onDownloadModule: (Module) -> Unit,
    onRandomModule: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (Module) -> Unit
) {
    val resource = LocalResources.current
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (state.isRandom) {
                            stringResource(R.string.title_download_random)
                        } else {
                            stringResource(R.string.title_download_module)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onBack = onBack) },
                actions = {
                    if (state.moduleExists) {
                        IconButton(
                            onClick = { onShowDialog(true) },
                            content = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                    state.result?.module?.infopage?.let { url ->
                        val context = LocalContext.current
                        if (url.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val module = state.result.module
                                    fun Module.shareText() = resource.getString(
                                        R.string.module_download_share,
                                        songtitle.ifBlank { filename },
                                        artist,
                                        infopage
                                    )
                                    context.shareLink(message = module.shareText())
                                },
                                content = {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (val status = state.downloadStatus) {
                        is DownloadStatus.Progress -> LinearProgressIndicator(
                            progress = { status.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        is DownloadStatus.Loading -> LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )

                        else -> Spacer(modifier = Modifier.height(4.dp)) // keep layout stable
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = {
                            val module = state.result?.module
                            val isDownloading = state.downloadStatus is DownloadStatus.Loading ||
                                state.downloadStatus is DownloadStatus.Progress
                            val buttonLabel = when {
                                state.isLoading -> stringResource(R.string.loading)

                                isDownloading -> when (val s = state.downloadStatus) {
                                    is DownloadStatus.Progress -> "%.0f%%".format(s.percent)
                                    else -> stringResource(R.string.downloading)
                                }

                                state.moduleExists -> stringResource(R.string.play)

                                module?.isSupported == false -> stringResource(R.string.unsupported)

                                else -> stringResource(R.string.download)
                            }

                            Button(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small,
                                enabled = !state.isLoading && !isDownloading &&
                                    module?.isSupported != false,
                                onClick = {
                                    if (state.moduleExists) {
                                        module?.let { onPlay(it) }
                                    } else {
                                        module?.let { onDownloadModule(it) }
                                    }
                                },
                                content = { Text(text = buttonLabel) }
                            )

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small,
                                enabled = !state.isLoading && !isDownloading,
                                onClick = onRandomModule,
                                content = { Text(text = stringResource(R.string.random)) }
                            )
                        }
                    )
                }
            }
        },
        content = { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
                content = {
                    if (state.isLoading) ProgressbarIndicator()

                    state.softError?.let {
                        MessageBox(
                            text = it,
                            actions = {
                                TextButton(
                                    onClick = onBack,
                                    content = {
                                        Text(text = stringResource(R.string.desc_back_button))
                                    }
                                )
                            }
                        )
                    }

                    state.result?.let { result ->
                        ModuleDetailLayout(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            moduleResult = result,
                        )
                    }
                }
            )
        }
    )
}

private class DownloadModulePreviewParameter : PreviewParameterProvider<ModuleResultState> {
    private val sampleModule = ModuleResult(
        module = Module(
            filename = "alpharapii.mod",
            songtitle = "alpharapii",
            format = "MOD",
            bytes = 45678,
            infopage = "website"
        )
    )

    override val values = sequenceOf(
        ModuleResultState(isLoading = true),
        ModuleResultState(isRandom = true, result = sampleModule),
        ModuleResultState(result = sampleModule),
        ModuleResultState(result = sampleModule, moduleExists = true),
        ModuleResultState(result = sampleModule, downloadStatus = DownloadStatus.Loading),
        ModuleResultState(result = sampleModule, downloadStatus = DownloadStatus.Progress(66f)),
        ModuleResultState(result = sampleModule, downloadStatus = DownloadStatus.Success),
        ModuleResultState(softError = "Could not fetch module."),
    )
}

@Preview
@Composable
private fun Preview(
    @PreviewParameter(DownloadModulePreviewParameter::class) state: ModuleResultState
) {
    AppTheme {
        DownloadModuleContent(
            state = state,
            onBack = {},
            onShowDialog = {},
            onDownloadModule = {},
            onRandomModule = {},
            onPlay = {},
        )
    }
}
