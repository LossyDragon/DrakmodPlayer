package com.lossydragon.modplayer.ui.screens.player

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.SheetValue.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.PlaybackStatus
import com.lossydragon.modplayer.player.PlayerUiState
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.screens.player.components.ChipList
import com.lossydragon.modplayer.ui.screens.player.components.DurationsSheet
import com.lossydragon.modplayer.ui.screens.player.components.PatternInfoRow
import com.lossydragon.modplayer.ui.screens.player.components.PlaybackProgress
import com.lossydragon.modplayer.ui.screens.player.components.PlayerBottomAppBar
import com.lossydragon.modplayer.ui.screens.player.components.QueueSheet
import com.lossydragon.modplayer.ui.screens.player.components.TransportRow
import com.lossydragon.modplayer.ui.screens.player.components.views.ChannelView
import com.lossydragon.modplayer.ui.screens.player.components.views.DebugView
import com.lossydragon.modplayer.ui.screens.player.components.views.PatternView
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.helllabs.libxmp.model.FrameInfo
import org.helllabs.libxmp.model.ModVars
import org.helllabs.libxmp.model.Sequence
import org.koin.compose.viewmodel.koinViewModel

private sealed class PlayerAction {
    data class OnDurationClick(val int: Int) : PlayerAction()
    data class OnDurationSheet(val open: Boolean) : PlayerAction()
    data class OnQueueClick(val int: Int) : PlayerAction()
    data class OnQueueSheet(val open: Boolean) : PlayerAction()
    data class OnSeek(val seek: Long) : PlayerAction()
    data object OnAllSequences : PlayerAction()
    data object OnAudioInfo : PlayerAction()
    data object OnBack : PlayerAction()
    data object OnInstruments : PlayerAction()
    data object OnLoop : PlayerAction()
    data object OnModInfo : PlayerAction()
    data object OnNext : PlayerAction()
    data object OnPlayPause : PlayerAction()
    data object OnPlayerView : PlayerAction()
    data object OnPrevious : PlayerAction()
    data object OnShuffle : PlayerAction()
    data object OnSongMessage : PlayerAction()
    data object OnStop : PlayerAction()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val resource = LocalResources.current

    val snackBarHostState = remember { SnackbarHostState() }
    val viewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queueSheetState = rememberBottomSheetState(
        initialValue = Hidden,
        enabledValues = setOf(Hidden, Expanded)
    )
    val durationsSheetState = rememberBottomSheetState(
        initialValue = Hidden,
        enabledValues = setOf(Hidden, Expanded)
    )

    var showQueue by remember { mutableStateOf(false) }
    var showDurations by remember { mutableStateOf(false) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var showAudioInfo by remember { mutableStateOf(false) }
    var audioInfoText by remember { mutableStateOf("") }
    var showInstruments by remember { mutableStateOf(false) }
    var showModInfo by remember { mutableStateOf(false) }
    var showCommentInfo by remember { mutableStateOf(false) }

    val patternIndex = state.frameInfo.pattern
    val channels = state.modVars.chn
    val patternData = if (patternIndex < 0 || channels == 0) {
        PatternData()
    } else {
        viewModel.getPatternData(patternIndex)
    }

    LaunchedEffect(state.currentModule) {
        if (state.currentModule != null) hasLoadedOnce = true
        if (hasLoadedOnce && state.currentModule == null) onBack()
    }

    LaunchedEffect(showAudioInfo) {
        while (isActive && showAudioInfo) {
            audioInfoText = viewModel.getAudioStats()
            delay(3.seconds)
        }
    }

    if (showModInfo) {
        PlayerAlertDialog(
            onDismissRequest = { showModInfo = false },
            icon = Icons.Default.MusicNote,
            title = stringResource(R.string.dialog_title_mod_info),
            content = {
                Text(
                    text = stringResource(
                        R.string.player_module_info,
                        state.modVars.chn,
                        state.modVars.ins,
                        state.modVars.pat,
                        state.modVars.smp,
                        state.modVars.miNumSequences,
                    )
                )
            },
        )
    }

    if (showAudioInfo) {
        PlayerAlertDialog(
            onDismissRequest = { showAudioInfo = false },
            icon = Icons.Default.Info,
            title = stringResource(R.string.dialog_title_audio_info),
            content = { Text(text = audioInfoText) },
        )
    }

    if (showInstruments && state.modVars.instruments.isNotEmpty()) {
        PlayerAlertDialog(
            onDismissRequest = { showInstruments = false },
            icon = Icons.AutoMirrored.Filled.List,
            title = stringResource(R.string.dialog_title_song_instruments),
            content = {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    content = {
                        items(
                            items = state.modVars.instruments,
                            itemContent = { Text(text = it) }
                        )
                    }
                )
            },
        )
    }

    if (showCommentInfo) {
        PlayerAlertDialog(
            onDismissRequest = { showCommentInfo = false },
            icon = Icons.AutoMirrored.Filled.Message,
            title = stringResource(R.string.dialog_title_song_message),
            content = {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    content = { Text(text = state.modVars.miComment) }
                )
            },
        )
    }

    PlayerScreenContent(
        modifier = modifier,
        snackBarHostState = snackBarHostState,
        state = state,
        patternData = patternData,
        queueSheetState = queueSheetState,
        durationsSheetState = durationsSheetState,
        showQueue = showQueue,
        showDurations = showDurations,
        onAction = { action ->
            when (action) {
                PlayerAction.OnBack -> onBack()

                PlayerAction.OnPlayPause -> viewModel.togglePlayPause()

                PlayerAction.OnStop -> viewModel.stop()

                PlayerAction.OnPrevious -> viewModel.previous()

                PlayerAction.OnNext -> viewModel.next()

                PlayerAction.OnShuffle -> viewModel.toggleShuffle()

                PlayerAction.OnLoop -> viewModel.toggleLoop()

                PlayerAction.OnAllSequences -> viewModel.toggleAllSequences()

                PlayerAction.OnInstruments -> {
                    if (state.modVars.instruments.isEmpty()) {
                        scope.launch {
                            val text = resource.getString(R.string.snack_no_instruments)
                            snackBarHostState.showSnackbar(message = text)
                        }
                    } else {
                        showInstruments = true
                    }
                }

                PlayerAction.OnModInfo -> showModInfo = true

                PlayerAction.OnAudioInfo -> showAudioInfo = true

                PlayerAction.OnSongMessage -> {
                    if (state.modVars.miComment.isEmpty()) {
                        scope.launch {
                            val text = resource.getString(R.string.snack_no_song_message)
                            snackBarHostState.showSnackbar(message = text)
                        }
                    } else {
                        showCommentInfo = true
                    }
                }

                PlayerAction.OnPlayerView -> viewModel.onPlayerView()

                is PlayerAction.OnSeek -> viewModel.seek(action.seek)

                is PlayerAction.OnQueueClick -> viewModel.playAtIndex(action.int)

                is PlayerAction.OnQueueSheet -> {
                    showQueue = action.open
                    scope.launch {
                        if (showQueue) {
                            queueSheetState.expand()
                        } else {
                            queueSheetState.hide()
                        }
                    }
                }

                is PlayerAction.OnDurationClick -> {
                    viewModel.setSequence(action.int)
                    scope.launch { durationsSheetState.hide() }
                }

                is PlayerAction.OnDurationSheet -> {
                    showDurations = action.open
                    scope.launch {
                        if (showDurations) {
                            durationsSheetState.expand()
                        } else {
                            durationsSheetState.hide()
                        }
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreenContent(
    snackBarHostState: SnackbarHostState,
    state: PlayerUiState,
    patternData: PatternData,
    queueSheetState: SheetState,
    durationsSheetState: SheetState,
    showQueue: Boolean,
    showDurations: Boolean,
    modifier: Modifier = Modifier,
    onAction: (PlayerAction) -> Unit
) {
    if (state.currentModule == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackBarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = {
                            Text(
                                text = state.modVars.name.trim().ifBlank {
                                    state.currentModule.name
                                },
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = state.modVars.type.trim(),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                },
                navigationIcon = { BackButton(onBack = { onAction(PlayerAction.OnBack) }) },
                actions = {
                    IconButton(
                        onClick = { onAction(PlayerAction.OnPlayerView) },
                        content = {
                            Icon(
                                imageVector = Icons.Default.ViewCarousel,
                                contentDescription = null
                            )
                        }
                    )
                }
            )
        },
        bottomBar = {
            PlayerBottomAppBar(
                contentPadding = PaddingValues(bottom = 8.dp),
                content = {
                    HorizontalDivider(modifier = Modifier.fillMaxWidth())
                    PatternInfoRow(fi = state.frameInfo)
                    PlaybackProgress(state = state, onSeek = { onAction(PlayerAction.OnSeek(it)) })
                    Spacer(modifier = Modifier.height(4.dp))
                    ChipList(
                        isShuffle = state.isShuffle,
                        repeatMode = state.repeatMode,
                        isSubSongs = state.playAllSequences,
                        onShuffle = { onAction(PlayerAction.OnShuffle) },
                        onLoop = { onAction(PlayerAction.OnLoop) },
                        onModInfo = { onAction(PlayerAction.OnModInfo) },
                        onShowSongMessage = { onAction(PlayerAction.OnSongMessage) },
                        onShowSongInstruments = { onAction(PlayerAction.OnInstruments) },
                        onPlaySubSongs = { onAction(PlayerAction.OnAllSequences) },
                        onShowDurations = { onAction(PlayerAction.OnDurationSheet(true)) },
                        onAudioInfo = { onAction(PlayerAction.OnAudioInfo) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TransportRow(
                        status = state.status,
                        hasNext = state.queue.isNotEmpty() && (
                            state.repeatMode == Player.REPEAT_MODE_ALL ||
                                state.repeatMode == Player.REPEAT_MODE_ONE ||
                                state.currentQueueIndex < state.queue.lastIndex
                            ),
                        hasPrev = state.queue.isNotEmpty() && (
                            state.repeatMode == Player.REPEAT_MODE_ALL ||
                                state.repeatMode == Player.REPEAT_MODE_ONE ||
                                state.currentQueueIndex > 0
                            ),
                        onStop = { onAction(PlayerAction.OnStop) },
                        onPrev = { onAction(PlayerAction.OnPrevious) },
                        onPlayPause = { onAction(PlayerAction.OnPlayPause) },
                        onNext = { onAction(PlayerAction.OnNext) },
                        onQueueSheet = { onAction(PlayerAction.OnQueueSheet(true)) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            )
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                when (state.playerView) {
                    0 -> PatternView(
                        modifier = Modifier.fillMaxSize(),
                        module = state.currentModule,
                        pattern = patternData,
                        currentRow = state.frameInfo.row,
                        showRowNumbers = state.showRowNumbers,
                    )

                    1 -> ChannelView(
                        modifier = Modifier.fillMaxSize(),
                        numChannels = state.modVars.chn,
                        instrumentNames = state.modVars.instruments.toPersistentList(),
                        isPlaying = (state.status == PlaybackStatus.PLAYING) &&
                            !queueSheetState.isVisible && !durationsSheetState.isVisible
                    )

                    2 -> DebugView(
                        modifier = Modifier.fillMaxSize(),
                        state = state,
                        isPlaying = (state.status == PlaybackStatus.PLAYING) &&
                            !queueSheetState.isVisible && !durationsSheetState.isVisible
                    )
                }
            }

            if (showDurations) {
                DurationsSheet(
                    sheetState = durationsSheetState,
                    seqData = state.modVars.seqData.toPersistentList(),
                    currentSequence = state.currentSequence,
                    onItemClick = { onAction(PlayerAction.OnDurationClick(it)) },
                    onDismiss = { onAction(PlayerAction.OnDurationSheet(false)) },
                )
            }

            if (showQueue) {
                QueueSheet(
                    sheetState = queueSheetState,
                    queue = state.queue,
                    currentIndex = state.currentQueueIndex,
                    onItemClick = { onAction(PlayerAction.OnQueueClick(it)) },
                    onDismiss = { onAction(PlayerAction.OnQueueSheet(false)) },
                )
            }
        }
    )
}

@Composable
private fun PlayerAlertDialog(
    onDismissRequest: () -> Unit,
    icon: ImageVector,
    title: String,
    content: @Composable (() -> Unit)
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        title = { Text(text = title) },
        text = content,
        confirmButton = {
            TextButton(
                onClick = onDismissRequest,
                content = { Text(text = stringResource(R.string.close)) }
            )
        }
    )
}

/***********
 * Preview *
 ***********/

private val previewQueue = Array(10) {
    val number = it + 1
    ModuleEntity(
        filePath = "content://preview/$number",
        filename = "a_journey_into_sound.far",
        fileExtension = "far",
        fileSize = 123456L,
        moduleName = "A Journey Into Sound",
        moduleType = "Farandole Composer",
    )
}.toPersistentList()

private val previewPlayerState = PlayerUiState(
    status = PlaybackStatus.PLAYING,
    currentModule = previewQueue[1],
    queue = previewQueue,
    currentQueueIndex = 1,
    repeatMode = 2,
    currentSequence = 2,
    modVars = ModVars(
        name = "Beneath the Fallen Stars",
        type = "Impulse Tracker",
        seqData = arrayOf(
            Sequence(entryPoint = 0, duration = 237_000),
            Sequence(entryPoint = 0, duration = 237_000),
            Sequence(entryPoint = 0, duration = 237_000),
        ),
        instruments = Array(12) { "Instrument ${it + 1}" },
    ),
    frameInfo = FrameInfo(
        pos = 3,
        pattern = 5,
        row = 14,
        numRows = 64,
        speed = 6,
        bpm = 125,
        time = 47_000,
        totalTime = 237_000,
    ),
)

private class PlayerPreviewParameter :
    PreviewParameterProvider<Triple<PlayerUiState, Boolean, Boolean>> {
    override val values = sequenceOf(
        Triple(previewPlayerState, false, false),
        Triple(previewPlayerState, true, false), // queue sheet
        Triple(previewPlayerState, false, true), // durations sheet
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun Preview(
    @PreviewParameter(PlayerPreviewParameter::class) params: Triple<PlayerUiState, Boolean, Boolean>
) {
    val (state, showQueue, showDurations) = params
    val queueSheetState = SheetState(
        enabledValues = setOf(Hidden, Expanded),
        initialValue = if (showQueue) Expanded else Hidden,
        positionalThreshold = { 1f },
        velocityThreshold = { 1f },
    )
    val durationsSheetState = SheetState(
        enabledValues = setOf(Hidden, Expanded),
        initialValue = if (showDurations) Expanded else Hidden,
        positionalThreshold = { 1f },
        velocityThreshold = { 1f },
    )
    AppTheme {
        PlayerScreenContent(
            state = state,
            snackBarHostState = SnackbarHostState(),
            patternData = PatternData(),
            queueSheetState = queueSheetState,
            durationsSheetState = durationsSheetState,
            showQueue = showQueue,
            showDurations = showDurations,
            onAction = {},
        )
    }
}
