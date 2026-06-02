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
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.model.ArtistResult
import com.lossydragon.modplayer.model.Item
import com.lossydragon.modplayer.model.Items
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.model.SearchListResult
import com.lossydragon.modplayer.model.Sponsor
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.components.ProgressbarIndicator
import com.lossydragon.modplayer.ui.screens.downloads.components.DownloadListItem
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.DownloadSearchState
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.DownloadViewModel
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.SearchResult
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.SearchType
import com.lossydragon.modplayer.ui.theme.AppTheme
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun DownloadResultScreen(
    searchType: SearchType,
    query: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onModuleClick: (Int) -> Unit
) {
    val viewModel = koinViewModel<DownloadViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (searchType == SearchType.ARTIST) {
            viewModel.searchArtist(query)
        } else {
            viewModel.searchFileOrTitle(query)
        }
    }

    DownloadScreenContent(
        modifier = modifier,
        state = state,
        onBack = onBack,
        onModuleClick = onModuleClick,
        onArtistClick = viewModel::getArtistById,
    )
}

@Composable
private fun DownloadScreenContent(
    state: DownloadSearchState,
    onBack: () -> Unit,
    onModuleClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onArtistClick: (Int) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = state.title.ifBlank { stringResource(R.string.results) },
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
            if (state.isLoading) {
                ProgressbarIndicator(modifier = Modifier.align(Alignment.Center))
            }

            state.error?.let {
                MessageBox(
                    text = it,
                    actions = {
                        TextButton(
                            onClick = onBack,
                            content = { Text(text = stringResource(R.string.desc_back_button)) }
                        )
                    }
                )
            }

            if (!state.isLoading && state.error == null) {
                when (val result = state.result) {
                    is SearchResult.Modules -> if (result.data.module.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            content = {
                                items(
                                    items = result.data.module,
                                    itemContent = { module ->
                                        DownloadListItem(
                                            text = module.songtitle,
                                            supportingText = module.artist,
                                            leadingText = module.format,
                                            trailingText = stringResource(
                                                R.string.size_kb,
                                                module.sizeKb
                                            ),
                                            onClick = { onModuleClick(module.id) }
                                        )
                                    }
                                )
                            }
                        )
                    } else {
                        MessageBox(
                            text = stringResource(R.string.message_no_modules),
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

                    is SearchResult.Artists -> if (result.data.items.item.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            content = {
                                items(
                                    items = result.data.listItems,
                                    itemContent = { artist ->
                                        DownloadListItem(
                                            text = artist.alias,
                                            onClick = { onArtistClick(artist.id) }
                                        )
                                    }
                                )
                            }
                        )
                    } else {
                        MessageBox(
                            text = stringResource(R.string.message_no_artists),
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

                    null -> Unit
                }
            }
        }
    }
}

private class DownloadPreviewParameter : PreviewParameterProvider<DownloadSearchState> {
    override val values = sequenceOf(
        DownloadSearchState(isLoading = true),
        DownloadSearchState(result = SearchResult.Modules(data = SearchListResult())),
        DownloadSearchState(result = SearchResult.Artists(data = ArtistResult())),
        DownloadSearchState(
            error = "Modules Error",
            title = "Modules",
            result = SearchResult.Modules(data = SearchListResult()),
        ),
        DownloadSearchState(
            error = "Artists Error",
            title = "Artists",
            result = SearchResult.Artists(data = ArtistResult()),
        ),
        DownloadSearchState(
            title = "Modules",
            result = SearchResult.Modules(
                data = SearchListResult(module = Array(10) { Module() }.toList())
            ),
        ),
        DownloadSearchState(
            title = "Artists",
            result = SearchResult.Artists(
                data = ArtistResult(
                    sponsor = Sponsor(),
                    items = Items(item = Array(10) { Item() }.toList()),
                )
            ),
        ),
    )
}

@Preview
@Composable
private fun Preview(
    @PreviewParameter(DownloadPreviewParameter::class) state: DownloadSearchState
) {
    AppTheme {
        DownloadScreenContent(
            state = state,
            onBack = {},
            onModuleClick = {},
            onArtistClick = {},
        )
    }
}
