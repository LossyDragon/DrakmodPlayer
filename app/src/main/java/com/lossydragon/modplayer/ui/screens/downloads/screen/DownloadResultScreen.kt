package com.lossydragon.modplayer.ui.screens.downloads.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.model.Item
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.model.ResultItem
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.components.ProgressbarIndicator
import com.lossydragon.modplayer.ui.screens.downloads.DownloadSearchState
import com.lossydragon.modplayer.ui.screens.downloads.DownloadsViewModel
import com.lossydragon.modplayer.ui.screens.downloads.SearchResultType
import com.lossydragon.modplayer.ui.screens.downloads.SearchType
import com.lossydragon.modplayer.ui.screens.downloads.components.DownloadListItem
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.UiText
import com.lossydragon.modplayer.util.asString
import com.lossydragon.modplayer.util.sizeKb
import kotlinx.coroutines.flow.flowOf

/** What a results screen lists; each one is its own nav entry. */
sealed interface ResultRequest {
    data class Search(val query: String, val type: SearchType) : ResultRequest
    data class Artist(val id: Int, val alias: String) : ResultRequest
}

@Composable
internal fun DownloadResultScreen(
    viewModel: DownloadsViewModel,
    request: ResultRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onModuleClick: (Int) -> Unit,
    onArtistClick: (Int, String) -> Unit
) {
    val state by viewModel.searchState.collectAsStateWithLifecycle()
    val pagingItems = viewModel.pagingData.collectAsLazyPagingItems()

    // The ViewModel ignores a repeat of its current command, so re-entry keeps the loaded pages.
    LaunchedEffect(request) {
        when (request) {
            is ResultRequest.Search -> if (request.type == SearchType.ARTIST) {
                viewModel.searchArtist(request.query)
            } else {
                viewModel.searchFileOrTitle(request.query)
            }

            is ResultRequest.Artist -> viewModel.getArtistById(request.id, request.alias)
        }
    }

    DownloadScreenContent(
        modifier = modifier,
        state = state,
        pagingItems = pagingItems,
        onBack = onBack,
        onModuleClick = onModuleClick,
        onArtistClick = onArtistClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadScreenContent(
    state: DownloadSearchState,
    pagingItems: LazyPagingItems<ResultItem>,
    onBack: () -> Unit,
    onModuleClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onArtistClick: (Int, String) -> Unit
) {
    val noResultsText = when (state.activeType) {
        SearchResultType.ARTISTS -> stringResource(R.string.message_no_artists)
        else -> stringResource(R.string.message_no_modules)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = state.title?.asString() ?: stringResource(R.string.results),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { BackButton(onBack = onBack) },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                pagingItems.loadState.refresh is LoadState.Loading -> ProgressbarIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                pagingItems.loadState.refresh is LoadState.Error ||
                    (pagingItems.itemCount == 0 && state.activeType != SearchResultType.NONE) -> {
                    val text = (pagingItems.loadState.refresh as? LoadState.Error)
                        ?.error?.message ?: noResultsText
                    MessageBox(
                        text = text,
                        actions = {
                            TextButton(onClick = onBack) {
                                Text(text = stringResource(R.string.desc_back_button))
                            }
                        }
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        count = pagingItems.itemCount,
                        key = pagingItems.itemKey { it.id }
                    ) { idx ->
                        when (val item = pagingItems[idx]) {
                            is Module -> DownloadListItem(
                                text = item.songtitle,
                                supportingText = item.artist,
                                leadingText = item.format,
                                trailingText = stringResource(R.string.size_kb, item.sizeKb),
                                onClick = { onModuleClick(item.id) }
                            )

                            is Item -> DownloadListItem(
                                text = item.alias,
                                onClick = { onArtistClick(item.id, item.alias) }
                            )
                        }
                    }
                    if (pagingItems.loadState.append is LoadState.Loading) {
                        item {
                            LoadingIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewModules() {
    val items = flowOf(PagingData.from<ResultItem>(Array(10) { Module(id = it) }.toList()))
    AppTheme {
        DownloadScreenContent(
            state = DownloadSearchState(
                title = UiText.Raw("Results: foo"),
                activeType = SearchResultType.MODULES
            ),
            pagingItems = items.collectAsLazyPagingItems(),
            onBack = {},
            onModuleClick = {},
            onArtistClick = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun PreviewArtists() {
    val items = flowOf(PagingData.from<ResultItem>(Array(10) { Item(id = it) }.toList()))
    AppTheme {
        DownloadScreenContent(
            state = DownloadSearchState(
                title = UiText.Raw("Artists: foo"),
                activeType = SearchResultType.ARTISTS
            ),
            pagingItems = items.collectAsLazyPagingItems(),
            onBack = {},
            onModuleClick = {},
            onArtistClick = { _, _ -> },
        )
    }
}
