package com.lossydragon.modplayer.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.di.appModule
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.components.AppSnackbarHostState
import com.lossydragon.modplayer.ui.screens.browser.FileBrowserScreen
import com.lossydragon.modplayer.ui.screens.downloads.NavDownloads
import com.lossydragon.modplayer.ui.screens.player.components.MiniPlayerBar
import com.lossydragon.modplayer.ui.screens.playlists.NavPlaylists
import com.lossydragon.modplayer.ui.screens.preferences.NavPreferences
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf
import org.koin.android.ext.koin.androidContext
import org.koin.compose.KoinApplicationPreview

private sealed class MainTab(@param:StringRes val title: Int) {
    abstract val selectedIcon: ImageVector
    abstract val unselectedIcon: ImageVector

    data object Browser : MainTab(R.string.nav_browser) {
        override val selectedIcon = Icons.Filled.Folder
        override val unselectedIcon = Icons.Outlined.Folder
    }

    data object Playlists : MainTab(R.string.nav_playlists) {
        override val selectedIcon = Icons.AutoMirrored.Filled.List
        override val unselectedIcon = Icons.AutoMirrored.Outlined.List
    }

    data object Downloads : MainTab(R.string.nav_downloads) {
        override val selectedIcon = Icons.Filled.Download
        override val unselectedIcon = Icons.Outlined.Download
    }

    data object Settings : MainTab(R.string.nav_settings) {
        override val selectedIcon = Icons.Filled.Settings
        override val unselectedIcon = Icons.Outlined.Settings
    }
}

private val bottomBarItems = persistentListOf(
    MainTab.Browser,
    MainTab.Playlists,
    MainTab.Downloads,
    MainTab.Settings,
)

/**
 * The tab shell. Tabs are not a nav back stack: NavDisplay drops a popped entry's saved state
 * and ViewModelStore, so switching tabs would reset every tab. Instead the selected tab index is
 * saveable, each tab's content lives in a [SaveableStateHolder] slot that is kept while hidden
 * (sub back stacks, scroll positions), and the tab ViewModels are activity scoped.
 */
@Composable
fun MainNavigation(
    onNavigateToPlayer: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = AppSnackbarHostState
) {
    val playerViewModel = activityViewModel<PlayerViewModel>()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentTab = bottomBarItems[tabIndex]
    val selectTab = { tab: MainTab -> tabIndex = bottomBarItems.indexOf(tab) }
    val tabStateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = currentTab != MainTab.Browser) { selectTab(MainTab.Browser) }

    LaunchedEffect(playerState.errorMessage) {
        playerState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = playerState.currentModule != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    MiniPlayerBar(
                        state = playerState,
                        frameInfo = playerViewModel.frameInfo,
                        onTap = onNavigateToPlayer,
                        onPlayPause = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::next,
                        onPrevious = playerViewModel::previous,
                        onDismiss = playerViewModel::stop,
                    )
                }
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
                            onClick = { selectTab(destination) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            targetState = currentTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab",
        ) { tab ->
            tabStateHolder.SaveableStateProvider(tab.title) {
                val contentModifier = Modifier.consumeWindowInsets(padding)
                when (tab) {
                    MainTab.Browser -> FileBrowserScreen(
                        modifier = contentModifier,
                        snackbarHostState = snackbarHostState,
                        onNavigateToPlayer = onNavigateToPlayer,
                        onBack = onBack,
                    )

                    MainTab.Playlists -> NavPlaylists(
                        modifier = contentModifier,
                        snackbarHostState = snackbarHostState,
                        onBack = { selectTab(MainTab.Browser) },
                        onNavigateToPlayer = onNavigateToPlayer,
                    )

                    MainTab.Downloads -> NavDownloads(
                        modifier = contentModifier,
                        snackbarHostState = snackbarHostState,
                        onBack = { selectTab(MainTab.Browser) },
                        onNavigateToPlayer = onNavigateToPlayer,
                    )

                    MainTab.Settings -> NavPreferences(
                        modifier = contentModifier,
                        snackbarHostState = snackbarHostState,
                        onBack = { selectTab(MainTab.Browser) },
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    val context = LocalContext.current
    KoinApplicationPreview(
        application = {
            androidContext(context)
            modules(appModule)
        }
    ) {
        AppTheme {
            MainNavigation(
                onNavigateToPlayer = {},
                onBack = {},
            )
        }
    }
}
