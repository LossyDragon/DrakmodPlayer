package com.lossydragon.modplayer.ui.screens.browser

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Modifier
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
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.activityViewModel
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.components.ProgressbarIndicator
import com.lossydragon.modplayer.ui.screens.browser.components.BreadCrumbs
import com.lossydragon.modplayer.ui.screens.browser.components.BrowserInputField
import com.lossydragon.modplayer.ui.screens.browser.components.BrowserListItem
import com.lossydragon.modplayer.ui.screens.browser.components.ModuleList
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.asString
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun FileBrowserScreen(
    onNavigateToPlayer: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val browserViewModel = activityViewModel<FileBrowserViewModel>()
    val playerViewModel = activityViewModel<PlayerViewModel>()
    val scope = rememberCoroutineScope()

    val browserState by browserViewModel.state.collectAsStateWithLifecycle()
    val repeatMode by remember(playerViewModel) {
        playerViewModel.state.map { it.repeatMode }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerViewModel.state.value.repeatMode)
    val playlists by browserViewModel.playlists.collectAsStateWithLifecycle()

    // Pending "add to playlist" target: a single file, or null meaning the current folder.
    var pendingAdd by remember { mutableStateOf<ModuleEntity?>(null) }
    var pendingFolderAdd by remember { mutableStateOf<FileItem?>(null) }

    // setRootFolder persists the grant itself.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(browserViewModel::onRootFolderPicked) }
    )

    LaunchedEffect(browserViewModel) {
        browserViewModel.events.collect { event ->
            val text = when (event) {
                is BrowserEvent.AddedToPlaylist -> if (event.skipped > 0) {
                    resources.getString(
                        R.string.snack_added_to_playlist_skipped,
                        event.added,
                        event.playlist.name,
                        event.skipped,
                    )
                } else {
                    resources.getString(
                        R.string.snack_added_to_playlist,
                        event.added,
                        event.playlist.name,
                    )
                }

                is BrowserEvent.Error -> event.message.asString(context)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    BackHandler {
        if (!browserViewModel.navigateUp()) {
            onBack()
        }
    }

    val dialogTarget = pendingAdd ?: if (pendingFolderAdd != null) pendingFolderAdd else null
    if (dialogTarget != null) {
        PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = {
                pendingAdd = null
                pendingFolderAdd = null
            },
            onPick = { playlist ->
                val file = pendingAdd
                val folder = pendingFolderAdd
                if (file != null) {
                    browserViewModel.addFileToPlaylist(playlist, file)
                } else if (folder != null) {
                    browserViewModel.addFolderToPlaylist(playlist, folder)
                }
                pendingAdd = null
                pendingFolderAdd = null
            },
        )
    }

    FileBrowserScreenContent(
        modifier = modifier,
        browserState = browserState,
        repeatMode = repeatMode,
        onRefresh = browserViewModel::onRefresh,
        onFilter = browserViewModel::setFilter,
        onShuffle = browserViewModel::setShuffle,
        onRepeatMode = playerViewModel::toggleLoop,
        onFolderPick = { folderPicker.launch(null) },
        onSortOrder = browserViewModel::setSortOrder,
        onBreadcrumb = browserViewModel::navigateToBreadcrumb,
        onPlayAll = {
            scope.launch {
                val files = browserViewModel.getRecursiveModules().toImmutableList()
                if (files.isNotEmpty()) {
                    playerViewModel.playAll(
                        files = files,
                        startAt = if (browserState.isShuffle) files.indices.random() else 0,
                        isShuffle = browserState.isShuffle,
                    )
                    onNavigateToPlayer()
                } else {
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
            )
            onNavigateToPlayer()
        },
        onPlaySingle = { file ->
            playerViewModel.play(file)
            onNavigateToPlayer()
        },
        onDir = browserViewModel::navigateInto,
        onAddToPlaylist = { pendingAdd = it },
        onAddFolderToPlaylist = { pendingFolderAdd = it },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileBrowserScreenContent(
    modifier: Modifier = Modifier,
    browserState: BrowserUiState,
    repeatMode: Int,
    onRefresh: () -> Unit,
    onFilter: (String) -> Unit,
    onShuffle: () -> Unit,
    onRepeatMode: () -> Unit,
    onFolderPick: () -> Unit,
    onSortOrder: (BrowserSortOrder) -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onSelect: (ModuleEntity) -> Unit,
    onPlaySingle: (ModuleEntity) -> Unit,
    onDir: (FileItem) -> Unit,
    onAddToPlaylist: (ModuleEntity) -> Unit,
    onAddFolderToPlaylist: (FileItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarWithGapState()
    val textFieldState = rememberTextFieldState()
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val appBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors(
        scrolledSearchBarContainerColor = Color.Unspecified,
        scrolledAppBarContainerColor = Color.Unspecified,
    )
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    // A refresh keeps the list on screen behind the pull indicator; other loads replace it.
    val isRefreshing = browserState.isLoading && browserState.loadingReason == R.string.reindexing

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
        topBar = {
            Column {
                AppBarWithSearch(
                    scrollBehavior = scrollBehavior,
                    state = searchBarState,
                    colors = appBarWithSearchColors,
                    inputField = inputField,
                    shape = MaterialTheme.shapes.small,
                )
                // Quick-pick: the filtered files, where a tap plays just that file.
                ExpandedDockedSearchBarWithGap(
                    state = searchBarState,
                    inputField = inputField,
                    shape = MaterialTheme.shapes.small,
                    content = {
                        when {
                            browserState.filterQuery.isBlank() -> QuickPickHint(
                                text = stringResource(R.string.search_quick_play_hint)
                            )

                            browserState.files.isEmpty() -> QuickPickHint(
                                text = stringResource(R.string.message_no_modules)
                            )

                            else -> LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                                content = {
                                    items(
                                        items = browserState.files,
                                        key = { it.filePath },
                                        itemContent = { file ->
                                            BrowserListItem(
                                                title = file.name,
                                                comment = file.type,
                                                leadingIcon = Icons.Default.AudioFile,
                                                onClick = {
                                                    scope.launch {
                                                        searchBarState.animateToCollapsed()
                                                    }
                                                    onPlaySingle(file)
                                                },
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    }
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
                visible = browserState.hasStorageAccess,
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
                                    contentDescription = stringResource(R.string.fab_play_all)
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
                                    contentDescription = stringResource(R.string.desc_shuffle),
                                    tint = if (browserState.isShuffle) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        )

                        IconButton(
                            onClick = onRepeatMode,
                            content = {
                                Icon(
                                    imageVector = when (repeatMode) {
                                        Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOneOn
                                        Player.REPEAT_MODE_ALL -> Icons.Default.RepeatOn
                                        else -> Icons.Default.Repeat
                                    },
                                    contentDescription = stringResource(R.string.desc_repeat),
                                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) {
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
        content = { padding ->
            val state = rememberPullToRefreshState()

            PullToRefreshBox(
                state = state,
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                when {
                    browserState.isLoading && !isRefreshing -> ProgressbarIndicator(
                        modifier = Modifier.fillMaxSize(),
                        text = browserState.loadingReason?.let { stringResource(it) }
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

                    browserState.error != null -> MessageBox(
                        modifier = Modifier.fillMaxSize(),
                        icon = Icons.Default.ErrorOutline,
                        text = browserState.error,
                        actions = {
                            TextButton(
                                onClick = onRefresh,
                                content = { Text(text = stringResource(R.string.retry)) }
                            )
                        }
                    )

                    browserState.files.isEmpty() &&
                        browserState.directories.isEmpty() -> MessageBox(
                        modifier = Modifier.fillMaxSize(),
                        text = stringResource(R.string.message_no_modules_in_folder),
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
                        onAddToPlaylist = onAddToPlaylist,
                        onAddFolderToPlaylist = onAddFolderToPlaylist,
                    )
                }
            }
        }
    )
}

@Composable
private fun QuickPickHint(text: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaylistPickerDialog(
    playlists: ImmutableList<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPick: (PlaylistEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.dialog_title_pick_playlist)) },
        text = {
            if (playlists.isEmpty()) {
                Text(text = stringResource(R.string.dialog_message_empty_playlist))
            } else {
                LazyColumn {
                    itemsIndexed(playlists) { idx, playlist ->
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            shapes = ListItemDefaults.shapes(
                                shape = MaterialTheme.shapes.small,
                                focusedShape = MaterialTheme.shapes.small,
                                pressedShape = MaterialTheme.shapes.small,
                            ),
                            onClick = { onPick(playlist) },
                            content = { Text(text = playlist.name) },
                            supportingContent = if (playlist.comment.isNotBlank()) {
                                { Text(text = playlist.comment) }
                            } else {
                                null
                            }
                        )
                        if (idx < playlists.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Preview
 */

private data class BrowserPreviewState(
    val browserState: BrowserUiState,
    val repeatMode: Int,
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
        BrowserPreviewState(
            description = "Browsing - shuffle, repeat one",
            browserState = baseBrowserState.copy(isShuffle = true),
            repeatMode = Player.REPEAT_MODE_ONE,
        ),
        BrowserPreviewState(
            description = "Browsing",
            browserState = baseBrowserState,
            repeatMode = Player.REPEAT_MODE_OFF,
        ),
        BrowserPreviewState(
            description = "No storage access",
            browserState = BrowserUiState(hasStorageAccess = false, isLoading = false),
            repeatMode = Player.REPEAT_MODE_OFF,
        ),
        BrowserPreviewState(
            description = "Loading",
            browserState = BrowserUiState(
                hasStorageAccess = true,
                isLoading = true,
                loadingReason = R.string.indexing,
            ),
            repeatMode = Player.REPEAT_MODE_OFF,
        ),
        BrowserPreviewState(
            description = "Refreshing",
            browserState = baseBrowserState.copy(
                isLoading = true,
                loadingReason = R.string.reindexing,
            ),
            repeatMode = Player.REPEAT_MODE_OFF,
        ),
        BrowserPreviewState(
            description = "Error",
            browserState = baseBrowserState.copy(error = "Permission denied"),
            repeatMode = Player.REPEAT_MODE_OFF,
        ),
        BrowserPreviewState(
            description = "Empty directory",
            browserState = baseBrowserState.copy(
                files = persistentListOf(),
                directories = persistentListOf(),
                breadcrumbs = persistentListOf("Xmp", "Empty"),
            ),
            repeatMode = Player.REPEAT_MODE_OFF,
        ),
        BrowserPreviewState(
            description = "Filtered - 'mod'",
            browserState = baseBrowserState.copy(
                filterQuery = "mod",
                files = persistentListOf(sampleFiles[2], sampleFiles[3]),
                directories = persistentListOf(),
            ),
            repeatMode = Player.REPEAT_MODE_ALL,
        ),
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
            repeatMode = Player.REPEAT_MODE_OFF,
        ),
    )

    override fun getDisplayName(index: Int): String = values.toList()[index].description
}

@Preview(showBackground = true)
@Composable
private fun Preview(
    @PreviewParameter(BrowserPreviewParameter::class) params: BrowserPreviewState
) {
    AppTheme {
        FileBrowserScreenContent(
            browserState = params.browserState,
            repeatMode = params.repeatMode,
            onRefresh = {},
            onFilter = {},
            onShuffle = {},
            onRepeatMode = {},
            onFolderPick = {},
            onSortOrder = {},
            onBreadcrumb = {},
            onPlayAll = {},
            onSelect = {},
            onPlaySingle = {},
            onDir = {},
            onAddToPlaylist = {},
            onAddFolderToPlaylist = {},
        )
    }
}
