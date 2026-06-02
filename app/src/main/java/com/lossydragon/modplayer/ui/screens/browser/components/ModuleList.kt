package com.lossydragon.modplayer.ui.screens.browser.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.core.net.toUri
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.model.ModuleFile
import com.lossydragon.modplayer.ui.screens.browser.BrowserUiState
import com.lossydragon.modplayer.ui.screens.browser.FileItem
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun ModuleList(
    state: BrowserUiState,
    padding: PaddingValues,
    listState: LazyListState,
    onDir: (FileItem) -> Unit,
    onSelect: (ModuleFile) -> Unit
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 88.dp, // FAB
            start = padding.calculateStartPadding(LocalLayoutDirection.current),
            end = padding.calculateEndPadding(LocalLayoutDirection.current),
        ),
        modifier = Modifier.fillMaxSize(),
        content = {
            items(
                items = state.directories,
                key = { it.uri.toString() },
                itemContent = { dir ->
                    BrowserListItem(
                        title = dir.name,
                        comment = stringResource(R.string.directory),
                        leadingIcon = Icons.Default.Folder,
                        onClick = { onDir(dir) }
                    )
                }
            )
            items(
                items = state.files,
                key = { it.uri.toString() },
                itemContent = { file ->
                    BrowserListItem(
                        title = file.resolvedName.ifBlank { file.name },
                        comment = file.resolvedType.ifBlank { file.extension },
                        leadingIcon = Icons.Default.AudioFile,
                        onClick = { onSelect(file) },
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
            state = BrowserUiState(
                files = List(10) {
                    ModuleFile(
                        uri = "$it".toUri(),
                        name = "Item $it",
                        sizeBytes = it + 1L * it,
                        extension = "669",
                    )
                }.toImmutableList(),
            ),
            padding = PaddingValues(),
            listState = LazyListState(),
            onDir = {},
            onSelect = {},
        )
    }
}
