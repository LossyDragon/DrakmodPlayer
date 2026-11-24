package com.lossydragon.modplayer.ui.screens.downloads

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.components.SurfaceMessageBox
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.annotatedLinkString
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_QUERY = 3

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

    val submit: () -> Unit = {
        val error = when {
            !hasApiKey -> R.string.snack_no_api
            query.length < MIN_QUERY -> R.string.snack_query_too_short
            else -> null
        }
        if (error == null) {
            onSearch(query, type)
            focusManager.clearFocus()
        } else {
            scope.launch { snackbarHostState.showSnackbar(resource.getString(error)) }
        }
    }

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
                onClick = submit,
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
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                    content = {
                        if (!hasApiKey) {
                            SurfaceMessageBox(
                                icon = Icons.Default.Key,
                                title = stringResource(R.string.search_no_api_title),
                                message = stringResource(R.string.search_no_api_message),
                            )
                        }

                        SearchField(
                            modifier = Modifier.focusRequester(focusRequester),
                            query = query,
                            enabled = hasApiKey,
                            isError = hasInteracted && query.length < MIN_QUERY,
                            onQueryChange = {
                                query = it
                                hasInteracted = true
                            },
                            onSubmit = submit,
                        )

                        SectionLabel(text = stringResource(R.string.search_by))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                ButtonGroupDefaults.ConnectedSpaceBetween
                            ),
                            content = {
                                SearchTypeButton(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.search_selection_title),
                                    checked = type == SearchType.TITLE,
                                    enabled = hasApiKey,
                                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                    onClick = { type = SearchType.TITLE },
                                )
                                SearchTypeButton(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.search_selection_artist),
                                    checked = type == SearchType.ARTIST,
                                    enabled = hasApiKey,
                                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                    onClick = { type = SearchType.ARTIST },
                                )
                            }
                        )

                        SectionLabel(text = stringResource(R.string.search_discover))

                        val randomLabel = stringResource(R.string.random)
                        val historyLabel = stringResource(R.string.history)
                        ButtonGroup(
                            modifier = Modifier.fillMaxWidth(),
                            overflowIndicator = {},
                            content = {
                                customItem(
                                    buttonGroupContent = {
                                        val interactionSource =
                                            remember { MutableInteractionSource() }
                                        DiscoverButton(
                                            modifier = Modifier
                                                .weight(1f)
                                                .animateWidth(interactionSource),
                                            icon = Icons.Default.Shuffle,
                                            label = randomLabel,
                                            enabled = hasApiKey,
                                            interactionSource = interactionSource,
                                            onClick = onRandom,
                                        )
                                    },
                                    menuContent = {}
                                )
                                customItem(
                                    buttonGroupContent = {
                                        val interactionSource =
                                            remember { MutableInteractionSource() }
                                        DiscoverButton(
                                            modifier = Modifier
                                                .weight(1f)
                                                .animateWidth(interactionSource),
                                            icon = Icons.Default.History,
                                            label = historyLabel,
                                            enabled = hasApiKey,
                                            interactionSource = interactionSource,
                                            onClick = onHistory,
                                        )
                                    },
                                    menuContent = {}
                                )
                            }
                        )
                    }
                )
            }
        )
    }
}

/** Pill-shaped filled search input, matching the M3 Expressive search bar look. */
@Composable
private fun SearchField(
    query: String,
    enabled: Boolean,
    isError: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            errorContainerColor = container,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        enabled = enabled,
        value = query,
        onValueChange = onQueryChange,
        isError = isError,
        singleLine = true,
        placeholder = { Text(text = stringResource(R.string.search_placeholder)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(
                    onClick = { onQueryChange("") },
                    content = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.desc_search_clear),
                        )
                    }
                )
            }
        } else {
            null
        },
        supportingText = if (isError) {
            { Text(text = stringResource(R.string.snack_query_too_short)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SearchTypeButton(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    shapes: ToggleButtonShapes,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ToggleButton(
        modifier = modifier.semantics { role = Role.RadioButton },
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        buttonSize = ToggleButtonSize.Medium,
        shapes = shapes,
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    )
}

/** Medium tonal button whose corners morph on press. */
@Composable
private fun DiscoverButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val height = ButtonDefaults.MediumContainerHeight
    FilledTonalButton(
        modifier = modifier.heightIn(min = height),
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(height),
        enabled = enabled,
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
        interactionSource = interactionSource,
        content = {
            Icon(
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
                imageVector = icon,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(height)))
            Text(
                text = label,
                style = ButtonDefaults.textStyleFor(height),
                maxLines = 1,
            )
        }
    )
}

@Preview
@Composable
private fun Preview_NoApiKey() {
    AppTheme {
        DownloadSearchScreen(
            hasApiKey = false,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onSearch = { _, _ -> },
            onRandom = {},
            onHistory = {},
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
