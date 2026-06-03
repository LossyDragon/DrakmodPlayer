package com.lossydragon.modplayer.ui.screens.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lossydragon.modplayer.BuildConfig
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.screens.downloads.screen.DownloadHistoryScreen
import com.lossydragon.modplayer.ui.screens.downloads.screen.DownloadModuleScreen
import com.lossydragon.modplayer.ui.screens.downloads.screen.DownloadResultScreen
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.SearchType
import com.lossydragon.modplayer.util.findDownloadedModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

private sealed interface NavKeyDownload : NavKey {
    @Serializable data object Search : NavKeyDownload

    @Serializable data object History : NavKeyDownload

    @Serializable data class SearchResult(val query: String, val type: SearchType) : NavKeyDownload

    @Serializable data class Module(val moduleId: Int) : NavKeyDownload
}

@Composable
fun NavDownloads(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToPlayer: () -> Unit
) {
    val viewModel: PlayerViewModel = koinViewModel(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )

    val backStack = rememberNavBackStack(NavKeyDownload.Search)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hasApiKey = remember { BuildConfig.API_KEY.isNotBlank() }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeLastOrNull()
    }

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
                    onBack = { backStack.removeLastOrNull() },
                    onModuleClick = { backStack.add(NavKeyDownload.Module(it)) },
                )
            }

            entry<NavKeyDownload.SearchResult> { it ->
                DownloadResultScreen(
                    modifier = modifier,
                    searchType = it.type,
                    query = it.query,
                    onBack = backStack::removeLastOrNull,
                    onModuleClick = { backStack.add(NavKeyDownload.Module(it)) },
                )
            }

            entry<NavKeyDownload.Module> {
                DownloadModuleScreen(
                    modifier = modifier,
                    moduleId = it.moduleId,
                    onBack = { backStack.removeLastOrNull() },
                    onPlay = { module ->
                        scope.launch(Dispatchers.IO) {
                            val rootUriStr = viewModel.getLastDirectoryUri() ?: return@launch
                            val rootUri = rootUriStr.toUri()
                            val filename = module.url.substringAfterLast('#')

                            // Walk the download dir to find the file URI
                            val fileUri = context.findDownloadedModule(
                                rootUri = rootUri,
                                artist = module.artist,
                                filename = filename
                            ) ?: return@launch

                            val moduleFile = ModuleEntity(
                                filePath = fileUri.toString(),
                                filename = module.songtitle.ifBlank { filename },
                                fileSize = module.bytes.toLong(),
                                fileExtension = filename.substringAfterLast('.', ""),
                            )

                            withContext(Dispatchers.Main) {
                                viewModel.play(moduleFile)
                                onNavigateToPlayer()
                            }
                        }
                    },
                )
            }
        }
    )
}
