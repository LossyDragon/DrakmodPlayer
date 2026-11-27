package com.lossydragon.modplayer.ui.screens.browser.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.ui.components.SegmentedListSpacing
import com.lossydragon.modplayer.ui.screens.browser.FileItem
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ModuleList(
    directories: ImmutableList<FileItem>,
    files: ImmutableList<ModuleEntity>,
    padding: PaddingValues,
    listState: LazyListState,
    onDir: (FileItem) -> Unit,
    onSelect: (ModuleEntity) -> Unit,
    onAddToPlaylist: ((ModuleEntity) -> Unit)? = null,
    onAddFolderToPlaylist: ((FileItem) -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + SegmentedListSpacing.Vertical,
            bottom = padding.calculateBottomPadding() + SegmentedListSpacing.FabClearance,
            start = padding.calculateStartPadding(LocalLayoutDirection.current) +
                SegmentedListSpacing.Horizontal,
            end = padding.calculateEndPadding(LocalLayoutDirection.current) +
                SegmentedListSpacing.Horizontal,
        ),
        content = {
            itemsIndexed(
                items = directories,
                key = { _, dir -> dir.uri.toString() },
                itemContent = { idx, dir ->
                    BrowserListItem(
                        modifier = Modifier
                            .animateItem()
                            .padding(bottom = ListItemDefaults.SegmentedGap),
                        title = dir.name,
                        comment = stringResource(R.string.directory),
                        leadingIcon = Icons.Default.Folder,
                        segmentedShapes = ListItemDefaults.segmentedShapes(idx, directories.size),
                        onClick = { onDir(dir) },
                        onTrailingClick = onAddFolderToPlaylist?.let { { it(dir) } },
                    )
                }
            )
            if (directories.isNotEmpty() && files.isNotEmpty()) {
                item(key = "group_gap") {
                    Spacer(
                        modifier = Modifier
                            .animateItem()
                            .height(12.dp)
                    )
                }
            }
            itemsIndexed(
                items = files,
                key = { _, file -> file.filePath },
                itemContent = { idx, file ->
                    BrowserListItem(
                        modifier = Modifier
                            .animateItem()
                            .padding(bottom = ListItemDefaults.SegmentedGap),
                        title = file.name,
                        comment = file.moduleType.ifBlank { file.fileExtension },
                        leadingIcon = Icons.Default.AudioFile,
                        segmentedShapes = ListItemDefaults.segmentedShapes(idx, files.size),
                        onClick = { onSelect(file) },
                        onTrailingClick = onAddToPlaylist?.let { { it(file) } },
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
        ModuleList(
            directories = persistentListOf(),
            files = List(10) {
                ModuleEntity(
                    filePath = "content://preview/$it",
                    filename = "Item $it",
                    fileExtension = "669",
                    fileSize = it + 1L * it,
                )
            }.toImmutableList(),
            padding = PaddingValues(),
            listState = LazyListState(),
            onDir = {},
            onSelect = {},
        )
    }
}
