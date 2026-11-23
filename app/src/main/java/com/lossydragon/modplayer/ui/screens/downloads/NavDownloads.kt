package com.lossydragon.modplayer.ui.screens.downloads

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lossydragon.modplayer.BuildConfig
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.activityViewModel
import com.lossydragon.modplayer.ui.screens.downloads.screen.DownloadHistoryScreen
import com.lossydragon.modplayer.ui.screens.downloads.screen.DownloadModuleScreen
import com.lossydragon.modplayer.ui.screens.downloads.screen.DownloadResultScreen
import com.lossydragon.modplayer.ui.screens.downloads.screen.ResultRequest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private sealed interface NavKeyDownload : NavKey {
    @Serializable data object Search : NavKeyDownload

    @Serializable data object History : NavKeyDownload

    @Serializable data class SearchResult(val query: String, val type: SearchType) : NavKeyDownload

    @Serializable data class Artist(val id: Int, val alias: String) : NavKeyDownload

    @Serializable data class Module(val moduleId: Int) : NavKeyDownload
}

@Composable
fun NavDownloads(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToPlayer: () -> Unit
) {
    val playerViewModel = activityViewModel<PlayerViewModel>()
    // One instance for the whole tab, so leaving the module screen doesn't cancel a download.
    val downloadsViewModel = activityViewModel<DownloadsViewModel>()

    val backStack = rememberNavBackStack(NavKeyDownload.Search)
    val scope = rememberCoroutineScope()
    val hasApiKey = remember { BuildConfig.API_KEY.isNotBlank() }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<NavKeyDownload.Search> {
                DownloadSearchScreen(
                    modifier = modifier,
                    hasApiKey = hasApiKey,
                    snackbarHostState = snackbarHostState,
                    onBack = onBack,
                    onSearch = { query, type ->
                        backStack.add(NavKeyDownload.SearchResult(query, type))
                    },
                    onRandom = { backStack.add(NavKeyDownload.Module(-1)) },
                    onHistory = { backStack.add(NavKeyDownload.History) },
                )
            }

            entry<NavKeyDownload.History> {
                DownloadHistoryScreen(
                    modifier = modifier,
                    viewModel = downloadsViewModel,
                    onBack = { backStack.removeLastOrNull() },
                    onModuleClick = { backStack.add(NavKeyDownload.Module(it)) },
                )
            }

            entry<NavKeyDownload.SearchResult> { key ->
                DownloadResultScreen(
                    modifier = modifier,
                    viewModel = downloadsViewModel,
                    request = ResultRequest.Search(key.query, key.type),
                    onBack = backStack::removeLastOrNull,
                    onModuleClick = { backStack.add(NavKeyDownload.Module(it)) },
                    onArtistClick = { id, alias ->
                        backStack.add(NavKeyDownload.Artist(id, alias))
                    },
                )
            }

            entry<NavKeyDownload.Artist> { key ->
                DownloadResultScreen(
                    modifier = modifier,
                    viewModel = downloadsViewModel,
                    request = ResultRequest.Artist(key.id, key.alias),
                    onBack = backStack::removeLastOrNull,
                    onModuleClick = { backStack.add(NavKeyDownload.Module(it)) },
                    onArtistClick = { id, alias ->
                        backStack.add(NavKeyDownload.Artist(id, alias))
                    },
                )
            }

            entry<NavKeyDownload.Module> { key ->
                DownloadModuleScreen(
                    modifier = modifier,
                    viewModel = downloadsViewModel,
                    snackbarHostState = snackbarHostState,
                    moduleId = key.moduleId,
                    onBack = { backStack.removeLastOrNull() },
                    onPlay = { module ->
                        scope.launch {
                            downloadsViewModel.findDownloaded(module)?.let {
                                playerViewModel.play(it)
                                onNavigateToPlayer()
                            }
                        }
                    },
                )
            }
        }
    )
}
