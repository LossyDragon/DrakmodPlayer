package com.lossydragon.modplayer.ui.screens.downloads.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.data.DownloadHistoryItem
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.screens.downloads.DownloadsViewModel
import com.lossydragon.modplayer.ui.screens.downloads.components.DownloadListItem
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

@Composable
internal fun DownloadHistoryScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onModuleClick: (Int) -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text(text = stringResource(R.string.dialog_title_clear_history)) },
            text = { Text(text = stringResource(R.string.dialog_message_clear_history)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    },
                    content = { Text(text = stringResource(R.string.clear)) }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    content = { Text(text = stringResource(R.string.cancel)) }
                )
            }
        )
    }

    DownloadHistoryContent(
        modifier = modifier,
        history = history,
        onBack = onBack,
        onModuleClick = onModuleClick,
        onShowDialog = { showClearDialog = it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadHistoryContent(
    history: ImmutableList<DownloadHistoryItem>,
    onBack: () -> Unit,
    onModuleClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onShowDialog: (Boolean) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.title_history)) },
                navigationIcon = { BackButton(onBack = onBack) },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(
                            onClick = { onShowDialog(true) },
                            content = {
                                Icon(
                                    imageVector = Icons.Default.ClearAll,
                                    contentDescription = stringResource(R.string.desc_clear_history)
                                )
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
            content = {
                if (history.isEmpty()) {
                    MessageBox(
                        text = stringResource(R.string.message_no_history),
                        actions = {
                            TextButton(
                                onClick = onBack,
                                content = {
                                    Text(text = stringResource(R.string.desc_back_button))
                                }
                            )
                        }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            items(
                                items = history,
                                key = { it.id },
                                itemContent = { item ->
                                    DownloadListItem(
                                        text = item.songtitle,
                                        supportingText = item.artist,
                                        leadingText = item.format,
                                        onClick = { onModuleClick(item.id) }
                                    )
                                }
                            )
                        }
                    )
                }
            }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        DownloadHistoryContent(
            history = Array(10) {
                DownloadHistoryItem(
                    id = it,
                    filename = "song_$it.mod",
                    songtitle = "Song Title $it",
                    format = "MOD",
                    bytes = (it + 1) * 1234,
                    artist = "Song Artist $it",
                    viewedAt = System.currentTimeMillis() - it * 60_000L,
                )
            }.toPersistentList(),
            onBack = {},
            onModuleClick = {},
            onShowDialog = {},
        )
    }
}
