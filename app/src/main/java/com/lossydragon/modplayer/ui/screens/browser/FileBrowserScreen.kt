package com.lossydragon.modplayer.ui.screens.browser

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.components.ProgressbarIndicator
import com.lossydragon.modplayer.ui.screens.browser.components.BreadCrumbs
import com.lossydragon.modplayer.ui.screens.browser.components.BrowserInputField
import com.lossydragon.modplayer.ui.screens.browser.components.ModuleList
import com.lossydragon.modplayer.ui.screens.player.components.MiniPlayerBar
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.takeReadWritePermission
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun FileBrowserScreen(
    onNavigateToPlayer: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val browserViewModel = koinViewModel<FileBrowserViewModel>()
    val playerViewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val scope = rememberCoroutineScope()

    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                context.takeReadWritePermission(it)
                browserViewModel.onRootFolderPicked(it)
            }
        }
    )

    BackHandler {
        if (!browserViewModel.navigateUp()) {
            onBack()
        }
    }

    FileBrowserScreenContent(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        browserState = browserState,
        playerState = playerState,
        onRefresh = browserViewModel::onRefresh,
        onFilter = browserViewModel::setFilter,
        onShuffle = browserViewModel::setShuffle,
        onRepeatMode = browserViewModel::setRepeatMode,
        onFolderPick = { folderPicker.launch(null) },
        onSortOrder = browserViewModel::setSortOrder,
        onBreadcrumb = browserViewModel::navigateToBreadcrumb,
        onPlayAll = {
            if (browserState.files.isNotEmpty()) {
                playerViewModel.playAll(
                    files = browserState.files,
                    startAt = 0,
                    isShuffle = browserState.isShuffle,
                    repeatMode = browserState.repeatMode,
                )
                onNavigateToPlayer()
            } else {
                scope.launch {
                    val text = resources.getString(R.string.snack_nothing_to_play)
                    snackbarHostState.showSnackbar(text)
                }
            }
        },
        onSelect = { file ->
            val index = browserState.files.indexOf(file)
            playerViewModel.playAll(
                files = browserState.files,
                startAt = if (index >= 0) index else 0,
                isShuffle = browserState.isShuffle,
                repeatMode = browserState.repeatMode,
            )
            onNavigateToPlayer()
        },
        onDir = browserViewModel::navigateInto,
        onMiniPlayerTap = onNavigateToPlayer,
        onMiniPlayerToggle = playerViewModel::togglePlayPause,
        onMiniPlayerNext = playerViewModel::next,
        onMiniPlayerPrev = playerViewModel::previous,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileBrowserScreenContent(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState,
    browserState: BrowserUiState,
    playerState: PlayerUiState,
    onRefresh: () -> Unit,
    onFilter: (String) -> Unit,
    onShuffle: () -> Unit,
    onRepeatMode: (Int) -> Unit,
    onFolderPick: () -> Unit,
    onSortOrder: (BrowserSortOrder) -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onSelect: (ModuleEntity) -> Unit,
    onDir: (FileItem) -> Unit,
    onMiniPlayerTap: () -> Unit,
    onMiniPlayerToggle: () -> Unit,
    onMiniPlayerNext: () -> Unit,
    onMiniPlayerPrev: () -> Unit
) {
    val searchBarState = rememberSearchBarWithGapState()
    val textFieldState = rememberTextFieldState()
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val appBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors(
        scrolledSearchBarContainerColor = Color.Unspecified,
        scrolledAppBarContainerColor = Color.Unspecified,
    )
    val listState = rememberLazyListState()
    val hasModule = playerState.currentModule != null // Its fine not being wrapped in remember.
    val layoutDirection = LocalLayoutDirection.current

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { onFilter(it) }
    }

    val inputField: @Composable () -> Unit = {
        BrowserInputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            colors = appBarWithSearchColors.searchBarColors.inputFieldColors,
            sortOrder = browserState.sortOrder,
            onSortOrder = onSortOrder,
            onFolderPick = onFolderPick,
            onFilter = onFilter,
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                AppBarWithSearch(
                    scrollBehavior = scrollBehavior,
                    state = searchBarState,
                    colors = appBarWithSearchColors,
                    inputField = inputField,
                    shape = MaterialTheme.shapes.small,
                )
                ExpandedDockedSearchBarWithGap(
                    state = searchBarState,
                    inputField = inputField,
                    shape = MaterialTheme.shapes.small,
                    content = { /* TODO maybe add this, for single plays */ }
                )
                if (browserState.breadcrumbs.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        BreadCrumbs(
                            breadcrumbs = browserState.breadcrumbs,
                            onCrumbClick = onBreadcrumb,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !hasModule && browserState.hasStorageAccess,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                HorizontalFloatingToolbar(
                    modifier = Modifier.offset(y = 12.dp),
                    expanded = true,
                    shape = MaterialTheme.shapes.small,
                    floatingActionButton = {
                        FloatingActionButton(
                            shape = MaterialTheme.shapes.small,
                            onClick = onPlayAll,
                            content = {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                            }
                        )
                    },
                    content = {
                        IconButton(
                            onClick = onShuffle,
                            content = {
                                val icon = if (browserState.isShuffle) {
                                    Icons.Default.ShuffleOn
                                } else {
                                    Icons.Default.Shuffle
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (browserState.isShuffle) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        )

                        IconButton(
                            onClick = {
                                val next = when (browserState.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                onRepeatMode(next)
                            },
                            content = {
                                Icon(
                                    imageVector = when (browserState.repeatMode) {
                                        Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOneOn
                                        Player.REPEAT_MODE_ALL -> Icons.Default.RepeatOn
                                        else -> Icons.Default.Repeat
                                    },
                                    contentDescription = "Repeat",
                                    tint = if (browserState.repeatMode !=
                                        Player.REPEAT_MODE_OFF
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        )
                    }
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = hasModule,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                MiniPlayerBar(
                    state = playerState,
                    onTap = onMiniPlayerTap,
                    onPlayPause = onMiniPlayerToggle,
                    onNext = onMiniPlayerNext,
                    onPrevious = onMiniPlayerPrev,
                )
            }
        },
        content = { padding ->
            val state = rememberPullToRefreshState()

            PullToRefreshBox(
                state = state,
                isRefreshing = browserState.isLoading,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                when {
                    browserState.isLoading -> ProgressbarIndicator(
                        modifier = Modifier.fillMaxSize(),
                        text = browserState.loadingReason
                    )

                    !browserState.hasStorageAccess -> MessageBox(
                        modifier = Modifier,
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(R.string.no_folder_selected),
                        text = stringResource(R.string.no_folder_selected_message),
                        actions = {
                            TextButton(
                                onClick = onFolderPick,
                                content = { Text(text = stringResource(R.string.choose_directory)) }
                            )
                        }
                    )

                    browserState.files.isEmpty() &&
                        browserState.directories.isEmpty() -> MessageBox(
                        modifier = Modifier.fillMaxSize(),
                        text = "No module files found in this folder.",
                    )

                    else -> ModuleList(
                        state = browserState,
                        padding = PaddingValues(
                            bottom = padding.calculateBottomPadding(),
                            start = padding.calculateStartPadding(layoutDirection),
                            end = padding.calculateEndPadding(layoutDirection),
                        ),
                        listState = listState,
                        onDir = onDir,
                        onSelect = onSelect,
                    )
                }
            }
        }
    )
}

/**
 * Preview
 */

private data class BrowserPreviewState(
    val browserState: BrowserUiState,
    val playerState: PlayerUiState,
    val description: String
)

private class BrowserPreviewParameter : PreviewParameterProvider<BrowserPreviewState> {

    private val sampleFiles = persistentListOf(
        ModuleEntity(
            filePath = "content://preview/1",
            filename = "a_journey_into_sound.far",
            fileExtension = "far",
            fileSize = 123_456L,
            moduleName = "A Journey Into Sound",
            moduleType = "Farandole Composer",
        ),
        ModuleEntity(
            filePath = "content://preview/2",
            filename = "aegis_-_beneath_the_fallen_stars.it",
            fileExtension = "it",
            fileSize = 1_820_792L,
            moduleName = "Beneath the Fallen Stars",
            moduleType = "Impulse Tracker",
        ),
        ModuleEntity(
            filePath = "content://preview/3",
            filename = "alpharapii.mod",
            fileExtension = "mod",
            fileSize = 45_678L,
            moduleName = "alpharapii",
            moduleType = "Amiga Protracker/Compatible",
        ),
        ModuleEntity(
            filePath = "content://preview/4",
            filename = "chiptune_no_184.mod",
            fileExtension = "mod",
            fileSize = 6_658L,
            moduleName = "Chiptune No. 184",
            moduleType = "Amiga Protracker/Compatible",
        ),
    )

    private val sampleDirs = persistentListOf(
        FileItem(name = "TheModArchive", uri = "1".toUri(), isDirectory = true, size = 0L),
        FileItem(name = "Demos", uri = "2".toUri(), isDirectory = true, size = 0L),
    )

    private val playingState = PlayerUiState(
        status = PlaybackStatus.PLAYING,
        currentModule = sampleFiles[1],
        moduleName = "Beneath the Fallen Stars",
        moduleType = "Impulse Tracker",
        positionMs = 62_000L,
        durationMs = 252_849L,
        currentQueueIndex = 1,
    )

    private val baseBrowserState = BrowserUiState(
        currentPath = "primary:Xmp/Modules",
        hasStorageAccess = true,
        isLoading = false,
        isShuffle = false,
        breadcrumbs = persistentListOf("Xmp", "Modules"),
        directories = sampleDirs,
        files = sampleFiles,
        sortOrder = BrowserSortOrder.NAME,
    )

    override val values = sequenceOf(
        // Normal browsing, no playback
        BrowserPreviewState(
            description = "Browsing - idle",
            browserState = baseBrowserState.copy(
                isShuffle = true,
                repeatMode = Player.REPEAT_MODE_ONE
            ),
            playerState = PlayerUiState(),
        ),
        // Browsing with mini player visible
        BrowserPreviewState(
            description = "Browsing - playing",
            browserState = baseBrowserState,
            playerState = playingState,
        ),
        // No storage access yet
        BrowserPreviewState(
            description = "No storage access",
            browserState = BrowserUiState(hasStorageAccess = false, isLoading = false),
            playerState = PlayerUiState(),
        ),
        // Loading state
        BrowserPreviewState(
            description = "Loading",
            browserState = BrowserUiState(hasStorageAccess = true, isLoading = true),
            playerState = PlayerUiState(),
        ),
        // Empty directory
        BrowserPreviewState(
            description = "Empty directory",
            browserState = baseBrowserState.copy(
                files = persistentListOf(),
                directories = persistentListOf(),
                breadcrumbs = persistentListOf("Xmp", "Empty"),
            ),
            playerState = PlayerUiState(),
        ),
        // Filtered results
        BrowserPreviewState(
            description = "Filtered - 'mod'",
            browserState = baseBrowserState.copy(
                filterQuery = "mod",
                files = persistentListOf(sampleFiles[2], sampleFiles[3]),
                directories = persistentListOf(),
            ),
            playerState = playingState,
        ),
        // Sorted by size
        BrowserPreviewState(
            description = "Sorted by size",
            browserState = baseBrowserState.copy(
                sortOrder = BrowserSortOrder.SIZE,
                files = persistentListOf(
                    sampleFiles[3], // 6KB
                    sampleFiles[2], // 45KB
                    sampleFiles[0], // 123KB
                    sampleFiles[1], // 1.8MB
                ),
            ),
            playerState = PlayerUiState(),
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun Preview(
    @PreviewParameter(BrowserPreviewParameter::class) params: BrowserPreviewState
) {
    AppTheme {
        FileBrowserScreenContent(
            snackbarHostState = SnackbarHostState(),
            browserState = params.browserState,
            playerState = params.playerState,
            onRefresh = {},
            onFilter = {},
            onShuffle = {},
            onRepeatMode = {},
            onFolderPick = {},
            onSortOrder = {},
            onBreadcrumb = {},
            onPlayAll = {},
            onSelect = {},
            onDir = {},
            onMiniPlayerTap = {},
            onMiniPlayerToggle = {},
            onMiniPlayerNext = {},
            onMiniPlayerPrev = {},
        )
    }
}
