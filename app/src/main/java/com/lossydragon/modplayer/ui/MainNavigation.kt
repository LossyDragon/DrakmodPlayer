package com.lossydragon.modplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.retain.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.tooling.preview.*
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lossydragon.modplayer.di.appModule
import com.lossydragon.modplayer.ui.screens.browser.FileBrowserScreen
import com.lossydragon.modplayer.ui.screens.downloads.NavDownloads
import com.lossydragon.modplayer.ui.screens.playlists.NavPlaylists
import com.lossydragon.modplayer.ui.screens.preferences.NavPreferences
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

private val bottomBarItems = persistentListOf(
    NavKeyMain.Browser,
    NavKeyMain.Playlists,
    NavKeyMain.Downloads,
    NavKeyMain.Settings,
)

@Composable
fun MainNavigation(
    onNavigateToPlayer: () -> Unit,
    onBack: () -> Unit
) {
    val mainBackStack = rememberNavBackStack(NavKeyMain.Browser)
    var currentTab by remember { mutableStateOf<NavKeyMain>(NavKeyMain.Browser) }

    val snackBarHostState = retain { SnackbarHostState() }

    BackHandler(enabled = currentTab != NavKeyMain.Browser) {
        mainBackStack.removeAt(mainBackStack.lastIndex)
        mainBackStack.add(NavKeyMain.Browser)
        currentTab = NavKeyMain.Browser
    }

    Scaffold(
        bottomBar = {
            ShortNavigationBar {
                bottomBarItems.forEach { destination ->
                    ShortNavigationBarItem(
                        selected = currentTab == destination,
                        label = { Text(destination.title) },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == destination) {
                                    destination.selectedIcon
                                } else {
                                    destination.unselectedIcon
                                },
                                contentDescription = destination.title,
                            )
                        },
                        onClick = {
                            if (currentTab != destination) {
                                mainBackStack.removeAt(mainBackStack.lastIndex)
                                mainBackStack.add(destination)
                                currentTab = destination
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavDisplay(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            backStack = mainBackStack,
            onBack = { mainBackStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<NavKeyMain.Browser> {
                    FileBrowserScreen(
                        modifier = Modifier.consumeWindowInsets(padding),
                        onNavigateToPlayer = onNavigateToPlayer,
                        onBack = onBack,
                    )
                }
                entry<NavKeyMain.Playlists> {
                    NavPlaylists(
                        modifier = Modifier.consumeWindowInsets(padding),
                        snackbarHostState = snackBarHostState,
                        onBack = {
                            mainBackStack.removeAt(mainBackStack.lastIndex)
                            mainBackStack.add(NavKeyMain.Browser)
                            currentTab = NavKeyMain.Browser
                        },
                        onNavigateToPlayer = onNavigateToPlayer,
                    )
                }
                entry<NavKeyMain.Downloads> {
                    NavDownloads(
                        modifier = Modifier.consumeWindowInsets(padding),
                        snackbarHostState = snackBarHostState,
                        onBack = {
                            mainBackStack.removeAt(mainBackStack.lastIndex)
                            mainBackStack.add(NavKeyMain.Browser)
                            currentTab = NavKeyMain.Browser
                        },
                        onNavigateToPlayer = onNavigateToPlayer,
                    )
                }
                entry<NavKeyMain.Settings> {
                    NavPreferences(
                        modifier = Modifier.consumeWindowInsets(padding),
                        snackbarHostState = snackBarHostState,
                        onBack = {
                            mainBackStack.removeAt(mainBackStack.lastIndex)
                            mainBackStack.add(NavKeyMain.Browser)
                            currentTab = NavKeyMain.Browser
                        },
                    )
                }
            }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    val context = LocalContext.current
    startKoin {
        androidContext(context)
        modules(appModule)
    }
    AppTheme {
        MainNavigation(
            onNavigateToPlayer = {},
            onBack = {},
        )
    }
}
