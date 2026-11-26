package com.lossydragon.modplayer.ui.screens.downloads.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.data.DownloadHistoryItem
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.ConfirmDialog
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.screens.downloads.DownloadsViewModel
import com.lossydragon.modplayer.ui.screens.downloads.components.DownloadListItem
import com.lossydragon.modplayer.ui.theme.AppTheme
import java.time.LocalDate
import java.time.ZoneId
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
        ConfirmDialog(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.dialog_title_clear_history),
            text = stringResource(R.string.dialog_message_clear_history),
            confirmLabel = stringResource(R.string.clear),
            onConfirm = viewModel::clearHistory,
            onDismiss = { showClearDialog = false },
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
                        icon = Icons.Default.History,
                        text = stringResource(R.string.message_no_history),
                        onBack = onBack,
                    )
                } else {
                    val sections = remember(history) { groupByDay(history) }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        content = {
                            sections.forEach { (bucket, items) ->
                                item(
                                    key = bucket.name,
                                    contentType = "header",
                                    content = {
                                        Text(
                                            modifier = Modifier
                                                .animateItem()
                                                .padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                                            text = stringResource(bucket.label),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                )
                                itemsIndexed(
                                    items = items,
                                    key = { _, item -> item.id },
                                    contentType = { _, _ -> "module" },
                                    itemContent = { index, item ->
                                        DownloadListItem(
                                            modifier = Modifier
                                                .animateItem()
                                                .padding(bottom = ListItemDefaults.SegmentedGap),
                                            text = item.songtitle,
                                            supportingText = item.artist,
                                            leadingText = item.format,
                                            trailingText = stringResource(
                                                R.string.size_kb,
                                                item.bytes / 1024
                                            ),
                                            segmentedShapes = ListItemDefaults.segmentedShapes(
                                                index = index,
                                                count = items.size,
                                            ),
                                            onClick = { onModuleClick(item.id) }
                                        )
                                    }
                                )
                            }
                        }
                    )
                }
            }
        )
    }
}

private enum class HistoryBucket(@param:StringRes val label: Int) {
    TODAY(R.string.history_today),
    YESTERDAY(R.string.history_yesterday),
    EARLIER(R.string.history_earlier)
}

/** Splits history into day buckets, keeping the incoming (newest first) order inside each. */
private fun groupByDay(
    history: List<DownloadHistoryItem>
): List<Pair<HistoryBucket, List<DownloadHistoryItem>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val startOfYesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    return history
        .groupBy {
            when {
                it.viewedAt >= startOfToday -> HistoryBucket.TODAY
                it.viewedAt >= startOfYesterday -> HistoryBucket.YESTERDAY
                else -> HistoryBucket.EARLIER
            }
        }
        .toSortedMap()
        .map { it.key to it.value }
}

@Preview
@Composable
private fun Preview_Empty() {
    AppTheme {
        DownloadHistoryContent(
            history = emptyList<DownloadHistoryItem>().toPersistentList(),
            onBack = {},
            onModuleClick = {},
            onShowDialog = {},
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
                    viewedAt = System.currentTimeMillis() - it * 9 * 3_600_000L,
                )
            }.toPersistentList(),
            onBack = {},
            onModuleClick = {},
            onShowDialog = {},
        )
    }
}
