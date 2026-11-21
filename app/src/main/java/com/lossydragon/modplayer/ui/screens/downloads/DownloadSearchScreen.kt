package com.lossydragon.modplayer.ui.screens.downloads

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.annotatedLinkString
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadSearchScreen(
    hasApiKey: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSearch: (String, SearchType) -> Unit,
    onRandom: () -> Unit,
    modifier: Modifier = Modifier,
    onHistory: () -> Unit
) {
    val resource = LocalResources.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(SearchType.TITLE) }
    var hasInteracted by rememberSaveable { mutableStateOf(false) }
    var hasAutoFocused by rememberSaveable { mutableStateOf(false) }

    // Open the keyboard on the first visit only, not every time the user comes back.
    LaunchedEffect(Unit) {
        if (hasAutoFocused) return@LaunchedEffect
        hasAutoFocused = true
        delay(500L.milliseconds)
        focusRequester.requestFocus()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.title_download)) },
                navigationIcon = { BackButton(onBack = onBack) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                shape = MaterialTheme.shapes.small,
                onClick = {
                    scope.launch {
                        if (!hasApiKey) {
                            snackbarHostState.showSnackbar(
                                message = resource.getString(R.string.snack_no_api)
                            )
                        } else if (query.length < 3) {
                            snackbarHostState.showSnackbar(
                                message = resource.getString(R.string.snack_query_too_short)
                            )
                        } else {
                            onSearch(query, type)
                            focusManager.clearFocus()
                        }
                    }
                },
                text = { Text(text = stringResource(R.string.search)) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.desc_search_button)
                    )
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.imePadding(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                content = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        content = {
                            Text(
                                text = annotatedLinkString(
                                    text = stringResource(R.string.search_api_credits),
                                    url = Constants.TMA_BASE_URL,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            content = {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    shape = MaterialTheme.shapes.small,
                    enabled = hasApiKey,
                    value = query,
                    onValueChange = {
                        query = it
                        hasInteracted = true
                    },
                    isError = hasInteracted && query.length < 3,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.length >= 3) {
                                onSearch(query, type)
                                focusManager.clearFocus()
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = resource.getString(R.string.snack_query_too_short)
                                    )
                                }
                            }
                        }
                    ),
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ButtonGroup items are built outside composition; resolve the labels first.
                val titleLabel = stringResource(R.string.search_selection_title)
                val artistLabel = stringResource(R.string.search_selection_artist)
                ButtonGroup(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    expandedRatio = .20f,
                    overflowIndicator = {},
                    content = {
                        toggleableItem(
                            checked = type == SearchType.TITLE,
                            enabled = hasApiKey,
                            label = titleLabel,
                            onCheckedChange = { type = SearchType.TITLE },
                            weight = 1f,
                        )
                        toggleableItem(
                            checked = type == SearchType.ARTIST,
                            enabled = hasApiKey,
                            label = artistLabel,
                            onCheckedChange = { type = SearchType.ARTIST },
                            weight = 1f,
                        )
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                ButtonGroup(
                    overflowIndicator = {},
                    content = {
                        customItem(
                            buttonGroupContent = {
                                OutlinedButton(
                                    onClick = onRandom,
                                    shape = MaterialTheme.shapes.small,
                                    enabled = hasApiKey,
                                    modifier = Modifier.weight(1f),
                                    content = {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = stringResource(R.string.random))
                                    }
                                )
                            },
                            menuContent = {}
                        )
                        customItem(
                            buttonGroupContent = {
                                OutlinedButton(
                                    onClick = onHistory,
                                    shape = MaterialTheme.shapes.small,
                                    content = {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = stringResource(R.string.history))
                                    }
                                )
                            },
                            menuContent = {}
                        )
                    }
                )
            }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        DownloadSearchScreen(
            hasApiKey = true,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onSearch = { _, _ -> },
            onRandom = {},
            onHistory = {},
        )
    }
}
