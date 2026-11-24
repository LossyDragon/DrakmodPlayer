package com.lossydragon.modplayer.ui.screens.player

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
import com.lossydragon.modplayer.player.PlayerView
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.player.model.PatternData
import com.lossydragon.modplayer.ui.activityViewModel
import com.lossydragon.modplayer.ui.components.AppSnackbarHostState
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.PlayerErrorEffect
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
import com.lossydragon.renderer.model.AudioStats
import com.lossydragon.renderer.model.FrameInfo
import com.lossydragon.renderer.model.ModVars
import com.lossydragon.renderer.model.Sequence
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    snackbarHostState: SnackbarHostState = AppSnackbarHostState,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val resource = LocalResources.current

    val viewModel = activityViewModel<PlayerViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queueSheetState = rememberBottomSheetState(
        initialValue = Hidden,
        enabledValues = setOf(Hidden, Expanded)
    )
    val durationsSheetState = rememberBottomSheetState(
        initialValue = Hidden,
        enabledValues = setOf(Hidden, Expanded)
    )

    // The booleans are the single source of truth; ModalBottomSheet animates in on its own.
    var showQueue by remember { mutableStateOf(false) }
    var showDurations by remember { mutableStateOf(false) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var showAudioInfo by remember { mutableStateOf(false) }
    var audioStats by remember { mutableStateOf<AudioStats?>(null) }
    var showInstruments by remember { mutableStateOf(false) }
    var showModInfo by remember { mutableStateOf(false) }
    var showCommentInfo by remember { mutableStateOf(false) }

    // Only the pattern index is needed here; deriving it keeps the 50 Hz flow out of this scope.
    val frame = viewModel.frameInfo.collectAsStateWithLifecycle()
    val patternIndex by remember(frame) { derivedStateOf { frame.value.pattern } }

    // Pattern data is fetched over JNI (one call per row) and cached in the engine. Resolve it
    // off the composition path so a cache miss never blocks a frame; keyed on the module too,
    // since the same pattern index means something else after a queue change.
    val channels = state.modVars.chn
    val patternData by produceState(PatternData(), patternIndex, channels, state.currentModule) {
        value = if (patternIndex < 0 || channels == 0) {
            PatternData()
        } else {
            withContext(Dispatchers.Default) { viewModel.getPatternData(patternIndex) }
        }
    }

    LaunchedEffect(state.currentModule) {
        if (state.currentModule != null) hasLoadedOnce = true
        if (hasLoadedOnce && state.currentModule == null) onBack()
    }

    PlayerErrorEffect(state.errorMessage, snackbarHostState)

    LaunchedEffect(showAudioInfo) {
        while (isActive && showAudioInfo) {
            audioStats = viewModel.getAudioStats()
            delay(3.seconds)
        }
    }

    fun closeSheet(sheet: SheetState, onHidden: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onHidden() }
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
            content = { audioStats?.let { Text(text = it.asText()) } },
        )
    }

    if (showInstruments && state.instruments.isNotEmpty()) {
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
                            items = state.instruments,
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
        snackBarHostState = snackbarHostState,
        state = state,
        frameInfo = viewModel.frameInfo,
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
                    if (state.instruments.isEmpty()) {
                        scope.launch {
                            val text = resource.getString(R.string.snack_no_instruments)
                            snackbarHostState.showSnackbar(message = text)
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
                            snackbarHostState.showSnackbar(message = text)
                        }
                    } else {
                        showCommentInfo = true
                    }
                }

                PlayerAction.OnPlayerView -> viewModel.onPlayerView()

                is PlayerAction.OnSeek -> viewModel.seek(action.seek)

                is PlayerAction.OnQueueClick -> viewModel.playAtIndex(action.int)

                is PlayerAction.OnQueueSheet -> {
                    if (action.open) {
                        showQueue = true
                    } else {
                        closeSheet(queueSheetState) { showQueue = false }
                    }
                }

                is PlayerAction.OnDurationClick -> {
                    viewModel.setSequence(action.int)
                    closeSheet(durationsSheetState) { showDurations = false }
                }

                is PlayerAction.OnDurationSheet -> {
                    if (action.open) {
                        showDurations = true
                    } else {
                        closeSheet(durationsSheetState) { showDurations = false }
                    }
                }
            }
        }
    )
}

@Composable
private fun AudioStats.asText(): String = stringResource(
    R.string.audio_stats_text,
    audioApi,
    sharingMode,
    perfMode,
    audioFormat,
    sampleRate,
    framesPerBurst,
    bufferSize,
    bufferCapacity,
    xrunCount,
    underrunCount,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreenContent(
    snackBarHostState: SnackbarHostState,
    state: PlayerUiState,
    frameInfo: StateFlow<FrameInfo>,
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

    // Not delegated: read only inside PatternView's draw lambda, and only redrawn per row.
    val frame = frameInfo.collectAsStateWithLifecycle()
    // Holds the last row while the next pattern's data is still loading.
    val row = remember(frame, patternData) {
        derivedStateOf {
            val fi = frame.value
            if (fi.pattern == patternData.patternIndex) fi.row else patternData.numRows - 1
        }
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
                                text = state.modVars.modName.ifBlank {
                                    state.currentModule.name
                                },
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = state.modVars.modType,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
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
                                contentDescription = stringResource(R.string.desc_player_view)
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
                    PatternInfoRow(frameInfo = frameInfo)
                    PlaybackProgress(
                        frameInfo = frameInfo,
                        onSeek = { onAction(PlayerAction.OnSeek(it)) },
                    )
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
                        hasNext = state.hasNext,
                        hasPrev = state.hasPrevious,
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
            val isPlaying = state.status == PlaybackStatus.PLAYING &&
                !queueSheetState.isVisible && !durationsSheetState.isVisible

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                when (state.playerView) {
                    PlayerView.PATTERN -> PatternView(
                        modifier = Modifier.fillMaxSize(),
                        module = state.currentModule,
                        pattern = patternData,
                        currentRow = { row.value },
                        showRowNumbers = state.showRowNumbers,
                    )

                    PlayerView.CHANNELS -> ChannelView(
                        modifier = Modifier.fillMaxSize(),
                        numChannels = state.modVars.chn,
                        instrumentNames = state.instruments,
                        isPlaying = isPlaying,
                    )

                    PlayerView.DEBUG -> DebugView(
                        modifier = Modifier.fillMaxSize(),
                        state = state,
                        frameInfo = frameInfo,
                        isPlaying = isPlaying,
                    )
                }
            }

            if (showDurations) {
                DurationsSheet(
                    sheetState = durationsSheetState,
                    seqData = state.sequences,
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

private val previewSequences = List(3) { Sequence(entryPoint = 0, duration = 237_000) }
    .toPersistentList()

private val previewPlayerState = PlayerUiState(
    status = PlaybackStatus.PLAYING,
    currentModule = previewQueue[1],
    queue = previewQueue,
    currentQueueIndex = 1,
    repeatMode = Player.REPEAT_MODE_ALL,
    currentSequence = 2,
    hasNext = true,
    hasPrevious = true,
    modVars = ModVars(
        name = "Beneath the Fallen Stars",
        type = "Impulse Tracker",
        seqData = previewSequences.toTypedArray(),
        instruments = Array(12) { "Instrument ${it + 1}" },
    ),
    instruments = List(12) { "Instrument ${it + 1}" }.toPersistentList(),
    sequences = previewSequences,
)

private val previewFrameInfo = FrameInfo(
    pos = 3,
    pattern = 5,
    row = 14,
    numRows = 64,
    speed = 6,
    bpm = 125,
    time = 47_000,
    totalTime = 237_000,
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
            frameInfo = MutableStateFlow(previewFrameInfo),
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
