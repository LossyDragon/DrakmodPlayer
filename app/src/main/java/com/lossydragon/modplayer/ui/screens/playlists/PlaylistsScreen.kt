package com.lossydragon.modplayer.ui.screens.playlists

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.db.entity.PlaylistEntity
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.MessageBox
import com.lossydragon.modplayer.ui.components.ProgressbarIndicator
import com.lossydragon.modplayer.ui.screens.player.components.MiniPlayerBar
import com.lossydragon.modplayer.ui.screens.playlists.components.DeletePlaylistDialog
import com.lossydragon.modplayer.ui.screens.playlists.components.NewPlaylistDialog
import com.lossydragon.modplayer.ui.screens.playlists.components.PlaylistEntriesFabMenu
import com.lossydragon.modplayer.ui.screens.playlists.components.PlaylistEntryItem
import com.lossydragon.modplayer.ui.screens.playlists.components.PlaylistListItem
import com.lossydragon.modplayer.ui.screens.playlists.components.PlaylistsFabMenu
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModVars
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private sealed interface NavKeyPlaylists : NavKey {
    @Serializable data object List : NavKeyPlaylists

    @Serializable data class Entries(
        val playlistId: Long,
        val playlistName: String,
        val playlistComment: String
    ) : NavKeyPlaylists
}

@Composable
fun NavPlaylists(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToPlayer: () -> Unit
) {
    val playerViewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val playlistsViewModel = koinViewModel<PlaylistsViewModel>()

    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val playlistsState by playlistsViewModel.state.collectAsStateWithLifecycle()

    val backStack = rememberNavBackStack(NavKeyPlaylists.List)

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
            entry<NavKeyPlaylists.List> {
                PlaylistListScreen(
                    modifier = modifier,
                    state = playlistsState,
                    playerState = playerState,
                    snackbarHostState = snackbarHostState,
                    onBack = onBack,
                    onNavigateToPlayer = onNavigateToPlayer,
                    onMiniPlayerToggle = playerViewModel::togglePlayPause,
                    onMiniPlayerNext = playerViewModel::next,
                    onMiniPlayerPrev = playerViewModel::previous,
                    onMiniPlayerStop = playerViewModel::stop,
                    onSelectPlaylist = { playlist ->
                        val entry = NavKeyPlaylists.Entries(
                            playlistId = playlist.id,
                            playlistName = playlist.name,
                            playlistComment = playlist.comment,
                        )
                        playlistsViewModel.selectPlaylist(playlist)
                        backStack.add(entry)
                    },
                    onCreatePlaylist = playlistsViewModel::createPlaylist,
                    onDeletePlaylist = playlistsViewModel::deletePlaylist,
                    onExport = playlistsViewModel::exportPlaylists,
                    onImport = playlistsViewModel::importPlaylists,
                    onDismissError = playlistsViewModel::dismissError,
                    onDismissImport = playlistsViewModel::dismissImportResult,
                    onDismissExport = playlistsViewModel::dismissExportSuccess,
                    onRenamePlaylist = { playlist, name, comment ->
                        playlistsViewModel.renamePlaylist(playlist, name, comment)
                    },
                )
            }

            entry<NavKeyPlaylists.Entries> { key ->
                PlaylistEntriesScreen(
                    modifier = modifier,
                    playlistName = key.playlistName,
                    state = playlistsState,
                    playerState = playerState,
                    snackbarHostState = snackbarHostState,
                    onBack = { backStack.removeLastOrNull() },
                    onNavigateToPlayer = onNavigateToPlayer,
                    onMiniPlayerToggle = playerViewModel::togglePlayPause,
                    onMiniPlayerNext = playerViewModel::next,
                    onMiniPlayerPrev = playerViewModel::previous,
                    onMiniPlayerStop = playerViewModel::stop,
                    onPlayEntry = playlistsViewModel::playEntry,
                    onPlayAll = { playlistsViewModel.playPlaylist() },
                    onShuffle = { playlistsViewModel.playPlaylist(isShuffle = true) },
                    onRemoveEntry = playlistsViewModel::removeFromPlaylist,
                    onReorderEntry = playlistsViewModel::reorderEntry,
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistListScreen(
    modifier: Modifier = Modifier,
    state: PlaylistsUiState,
    playerState: PlayerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onMiniPlayerToggle: () -> Unit,
    onMiniPlayerNext: () -> Unit,
    onMiniPlayerPrev: () -> Unit,
    onMiniPlayerStop: () -> Unit,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onDismissError: () -> Unit,
    onDismissImport: () -> Unit,
    onDismissExport: () -> Unit,
    onRenamePlaylist: (PlaylistEntity, String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val resource = LocalResources.current

    val hasModule by remember(playerState.currentModule) {
        mutableStateOf(playerState.currentModule != null)
    }
    var isFabExpanded by remember { mutableStateOf(false) }

    val showSnackBar: (String) -> Unit = {
        scope.launch { snackbarHostState.showSnackbar(message = it) }
    }

    // Import result snackbar
    LaunchedEffect(state.importResult) {
        state.importResult?.let { result ->
            val message = if (result.skipped > 0) {
                resource.getString(
                    R.string.snack_import_ok_skipped,
                    result.playlistsImported,
                    result.entriesImported,
                    result.skipped
                )
            } else {
                resource.getString(
                    R.string.snack_import_ok,
                    result.playlistsImported,
                    result.entriesImported
                )
            }
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = resource.getString(R.string.close)
            )
            onDismissImport()
        }
    }

    // Export success snackbar
    LaunchedEffect(state.exportSuccess) {
        if (state.exportSuccess) {
            val text = resource.getString(R.string.snack_export_ok)
            snackbarHostState.showSnackbar(text)
            onDismissExport()
        }
    }

    // New Playlist
    var isNewPlaylistDialog by remember { mutableStateOf(false) }
    if (isNewPlaylistDialog) {
        NewPlaylistDialog(
            onDismiss = { isNewPlaylistDialog = false },
            onCreate = onCreatePlaylist,
            onError = showSnackBar
        )
    }

    // Delete Playlist
    var isDeletePlaylistDialog by remember { mutableStateOf(false) }
    var deletePlaylistItem by remember { mutableStateOf<PlaylistEntity?>(null) }
    val showDeletePlaylistDialog: (PlaylistEntity) -> Unit = {
        deletePlaylistItem = it
        isDeletePlaylistDialog = true
    }
    val dismissDeletePlaylistDialog: () -> Unit = {
        isDeletePlaylistDialog = false
        deletePlaylistItem = null
    }
    if (isDeletePlaylistDialog && deletePlaylistItem != null) {
        DeletePlaylistDialog(
            playlist = deletePlaylistItem!!,
            onDismiss = dismissDeletePlaylistDialog,
            onConfirm = { onDeletePlaylist(deletePlaylistItem!!) },
        )
    }

    // Rename
    var isRenamePlaylistDialog by remember { mutableStateOf(false) }
    var renamePlaylistItem by remember { mutableStateOf<PlaylistEntity?>(null) }

    if (isRenamePlaylistDialog && renamePlaylistItem != null) {
        NewPlaylistDialog(
            initialName = renamePlaylistItem!!.name,
            initialComment = renamePlaylistItem!!.comment,
            onDismiss = {
                isRenamePlaylistDialog = false
                renamePlaylistItem = null
            },
            onCreate = { name, comment ->
                onRenamePlaylist(renamePlaylistItem!!, name, comment)
            },
            onError = showSnackBar,
        )
    }

    // Import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) {
                val text = resource.getString(R.string.snack_import_cancelled)
                showSnackBar(text)
                return@rememberLauncherForActivityResult
            }
            onImport(uri)
        }
    )

    // Export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            if (uri == null) {
                val text = resource.getString(R.string.snack_export_cancelled)
                showSnackBar(text)
                return@rememberLauncherForActivityResult
            }
            onExport(uri)
        }
    )

    // Content
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.playlists)) },
                navigationIcon = { BackButton(onBack = onBack) },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !state.isLoading && state.error == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                content = {
                    PlaylistsFabMenu(
                        expanded = isFabExpanded,
                        onExpand = { isFabExpanded = !isFabExpanded },
                        onImport = {
                            importLauncher.launch(arrayOf("application/json"))
                            isFabExpanded = false
                        },
                        onExport = {
                            exportLauncher.launch("dragon_player_playlists.json")
                            isFabExpanded = false
                        },
                        onNewPlaylist = {
                            isNewPlaylistDialog = true
                            isFabExpanded = false
                        },
                    )
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = hasModule,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                content = {
                    MiniPlayerBar(
                        state = playerState,
                        onTap = onNavigateToPlayer,
                        onPlayPause = onMiniPlayerToggle,
                        onNext = onMiniPlayerNext,
                        onPrevious = onMiniPlayerPrev,
                        onDismiss = onMiniPlayerStop,
                    )
                }
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()

        Crossfade(
            targetState = state.isLoading,
            content = { isLoading ->
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                        content = { ProgressbarIndicator() }
                    )
                } else {
                    if (!state.error.isNullOrBlank()) {
                        MessageBox(
                            text = state.error,
                            actions = {
                                TextButton(
                                    onClick = {
                                        onDismissError()
                                        onBack()
                                    },
                                    content = {
                                        Text(text = stringResource(R.string.desc_back_button))
                                    }
                                )
                            }
                        )
                    } else if (state.playlists.isEmpty()) {
                        MessageBox(
                            modifier = Modifier.padding(padding),
                            text = stringResource(R.string.message_no_playlists),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxSize(),
                            state = listState,
                            content = {
                                items(
                                    items = state.playlists,
                                    key = { it.id },
                                    itemContent = { item ->
                                        PlaylistListItem(
                                            item = item,
                                            onSelect = { onSelectPlaylist(item) },
                                            onDelete = { showDeletePlaylistDialog(item) },
                                            onRename = {
                                                renamePlaylistItem = item
                                                isRenamePlaylistDialog = true
                                            },
                                        )
                                    }
                                )
                            }
                        )
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistEntriesScreen(
    modifier: Modifier = Modifier,
    playlistName: String,
    state: PlaylistsUiState,
    playerState: PlayerUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    onMiniPlayerToggle: () -> Unit,
    onMiniPlayerNext: () -> Unit,
    onMiniPlayerPrev: () -> Unit,
    onMiniPlayerStop: () -> Unit,
    onPlayEntry: (ModuleEntity) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onRemoveEntry: (ModuleEntity) -> Unit,
    onReorderEntry: (Int, Int) -> Unit
) {
    val hasModule = playerState.currentModule != null
    var isFabExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(playlistName) },
                navigationIcon = { BackButton(onBack = onBack) },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = state.entries.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                PlaylistEntriesFabMenu(
                    expanded = isFabExpanded,
                    onExpand = { isFabExpanded = !isFabExpanded },
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = hasModule,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayerBar(
                    state = playerState,
                    onTap = onNavigateToPlayer,
                    onPlayPause = onMiniPlayerToggle,
                    onNext = onMiniPlayerNext,
                    onPrevious = onMiniPlayerPrev,
                    onDismiss = onMiniPlayerStop,
                )
            }
        },
    ) { padding ->
        val hapticFeedback = LocalHapticFeedback.current
        val lazyListState = rememberLazyListState()
        val reorderableLazyListState = rememberReorderableLazyListState(
            lazyListState = lazyListState,
            onMove = { from, to ->
                onReorderEntry(from.index, to.index)
                hapticFeedback.performHapticFeedback(
                    HapticFeedbackType.SegmentFrequentTick
                )
            }
        )

        Box(modifier = Modifier.padding(padding)) {
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    content = { ProgressbarIndicator() }
                )

                state.entries.isEmpty() -> MessageBox(
                    text = stringResource(R.string.message_no_playlist_items)
                )

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(
                            items = state.entries,
                            key = { _, f -> f.uri.toString() },
                        ) { index, file ->
                            ReorderableItem(
                                state = reorderableLazyListState,
                                key = file.filePath
                            ) { isDragging ->
                                val elevation by animateDpAsState(
                                    if (isDragging) 4.dp else 0.dp,
                                    label = "elevation"
                                )
                                Surface(shadowElevation = elevation) {
                                    with(this@ReorderableItem) {
                                        PlaylistEntryItem(
                                            index = index,
                                            file = file,
                                            onPlay = { onPlayEntry(file) },
                                            onRemove = { onRemoveEntry(file) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/***********
 * Preview *
 ***********/

private val samplePlaylists = persistentListOf(
    PlaylistEntity(
        id = 1L,
        name = "Amiga Classics",
        comment = "Comment Amiga Classics",
        createdAt = System.currentTimeMillis() - 86_400_000 * 7
    ),
    PlaylistEntity(
        id = 2L,
        name = "Fast Tracker II",
        comment = "Comment Fast Tracker II",
        createdAt = System.currentTimeMillis() - 86_400_000 * 3
    ),
    PlaylistEntity(
        id = 3L,
        name = "Impulse Tracker",
        comment = "Comment Impulse Tracker",
        createdAt = System.currentTimeMillis() - 86_400_000
    ),
    PlaylistEntity(
        id = 4L,
        name = "Scream Tracker 3",
        comment = "Comment Scream Tracker 3",
        createdAt = System.currentTimeMillis()
    ),
)

private val sampleEntries = persistentListOf(
    ModuleEntity(
        filePath = "content://preview/1",
        filename = "alpharapii.mod",
        fileExtension = "mod",
        fileSize = 45_678L,
        moduleName = "alpharapii",
        moduleType = "Amiga Protracker/Compatible",
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
        filename = "ballada-remix.mod",
        fileExtension = "mod",
        fileSize = 22_334L,
        moduleName = "ballada-remix",
        moduleType = "Amiga Protracker/Compatible",
    ),
    ModuleEntity(
        filePath = "content://preview/4",
        filename = "KRAKEN.IT",
        fileExtension = "it",
        fileSize = 312_456L,
        moduleName = "Kraken of the Sea",
        moduleType = "Impulse Tracker",
    ),
    ModuleEntity(
        filePath = "content://preview/5",
        filename = "SONG0.S3M",
        fileExtension = "s3m",
        fileSize = 98_123L,
        moduleName = "Escape",
        moduleType = "Scream Tracker 3",
    ),
)

private val playingState = PlayerUiState(
    status = PlaybackStatus.PLAYING,
    currentModule = sampleEntries[1],
    currentQueueIndex = 1,
    queue = sampleEntries,
    modVars = ModVars(
        name = "Beneath the Fallen Stars",
        type = "Impulse Tracker",
    ),
    frameInfo = FrameInfo(time = 47_000, totalTime = 237_000,),
)

private data class PlaylistPreviewState(
    val playlistsState: PlaylistsUiState,
    val playerState: PlayerUiState,
    val description: String
)

private class PlaylistListPreviewParameter : PreviewParameterProvider<PlaylistPreviewState> {
    override val values = sequenceOf(
        PlaylistPreviewState(
            description = "Empty",
            playlistsState = PlaylistsUiState(),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Playlists - idle",
            playlistsState = PlaylistsUiState(playlists = samplePlaylists),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Playlists - playing",
            playlistsState = PlaylistsUiState(playlists = samplePlaylists),
            playerState = playingState,
        ),
        PlaylistPreviewState(
            description = "Error",
            playlistsState = PlaylistsUiState(error = "Failed to load."),
            playerState = PlayerUiState(),
        ),
    )

    override fun getDisplayName(index: Int): String =
        "$index -" + values.toList()[index].description
}

private class PlaylistEntriesPreviewParameter : PreviewParameterProvider<PlaylistPreviewState> {
    override val values = sequenceOf(
        PlaylistPreviewState(
            description = "Loading",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[0],
                isLoading = true,
            ),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Entries - idle",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[0],
                entries = sampleEntries,
            ),
            playerState = PlayerUiState(),
        ),
        PlaylistPreviewState(
            description = "Entries - playing",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[1],
                entries = sampleEntries,
            ),
            playerState = playingState,
        ),
        PlaylistPreviewState(
            description = "Empty playlist",
            playlistsState = PlaylistsUiState(
                playlists = samplePlaylists,
                selectedPlaylist = samplePlaylists[2],
                entries = persistentListOf(),
            ),
            playerState = PlayerUiState(),
        ),
    )

    override fun getDisplayName(index: Int): String =
        "$index -" + values.toList()[index].description
}

@Preview(showBackground = true, name = "Playlist List Screen")
@Composable
private fun PreviewPlaylistListScreen(
    @PreviewParameter(PlaylistListPreviewParameter::class) params: PlaylistPreviewState
) {
    AppTheme {
        PlaylistListScreen(
            modifier = Modifier,
            state = params.playlistsState,
            playerState = params.playerState,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onNavigateToPlayer = {},
            onMiniPlayerToggle = {},
            onMiniPlayerNext = {},
            onMiniPlayerPrev = {},
            onMiniPlayerStop = {},
            onSelectPlaylist = {},
            onCreatePlaylist = { _, _ -> },
            onDeletePlaylist = {},
            onExport = {},
            onImport = {},
            onDismissError = {},
            onDismissImport = {},
            onDismissExport = {},
            onRenamePlaylist = { _, _, _ -> }
        )
    }
}

@Preview(showBackground = true, name = "Playlist Entries Screen")
@Composable
private fun PreviewPlaylistEntriesScreen(
    @PreviewParameter(PlaylistEntriesPreviewParameter::class) params: PlaylistPreviewState
) {
    AppTheme {
        PlaylistEntriesScreen(
            modifier = Modifier,
            playlistName = params.playlistsState.selectedPlaylist?.name ?: "AAAAA",
            state = params.playlistsState,
            playerState = params.playerState,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onNavigateToPlayer = {},
            onMiniPlayerToggle = {},
            onMiniPlayerNext = {},
            onMiniPlayerPrev = {},
            onMiniPlayerStop = {},
            onPlayEntry = {},
            onPlayAll = {},
            onShuffle = {},
            onRemoveEntry = {},
            onReorderEntry = { _, _ -> },
        )
    }
}
