package com.lossydragon.modplayer.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.retain.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.di.appModule
import com.lossydragon.modplayer.ui.screens.browser.FileBrowserScreen
import com.lossydragon.modplayer.ui.screens.downloads.NavDownloads
import com.lossydragon.modplayer.ui.screens.playlists.NavPlaylists
import com.lossydragon.modplayer.ui.screens.preferences.NavPreferences
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

@Serializable
private sealed class NavKeyMain(@param:StringRes val title: Int) : NavKey {
    abstract val selectedIcon: ImageVector
    abstract val unselectedIcon: ImageVector

    @Serializable
    data object Browser : NavKeyMain(com.lossydragon.modplayer.R.string.nav_browser) {
        override val selectedIcon = Icons.Filled.Folder
        override val unselectedIcon = Icons.Outlined.Folder
    }

    @Serializable
    data object Playlists : NavKeyMain(com.lossydragon.modplayer.R.string.nav_playlists) {
        override val selectedIcon = Icons.AutoMirrored.Filled.List
        override val unselectedIcon = Icons.AutoMirrored.Outlined.List
    }

    @Serializable
    data object Downloads : NavKeyMain(com.lossydragon.modplayer.R.string.nav_downloads) {
        override val selectedIcon = Icons.Filled.Download
        override val unselectedIcon = Icons.Outlined.Download
    }

    @Serializable
    data object Settings : NavKeyMain(R.string.nav_settings) {
        override val selectedIcon = Icons.Filled.Settings
        override val unselectedIcon = Icons.Outlined.Settings
    }
}

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
                        label = { Text(text = stringResource(destination.title)) },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == destination) {
                                    destination.selectedIcon
                                } else {
                                    destination.unselectedIcon
                                },
                                contentDescription = stringResource(destination.title),
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
