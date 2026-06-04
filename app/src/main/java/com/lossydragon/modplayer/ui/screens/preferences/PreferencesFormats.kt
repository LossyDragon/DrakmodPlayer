package com.lossydragon.modplayer.ui.screens.preferences

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.components.BackButton
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PreferencesFormats(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    formatList: ImmutableList<String>,
    modifier: Modifier = Modifier,
    onClick: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(text = stringResource(R.string.title_formats, formatList.size))
                },
                navigationIcon = { BackButton(onBack = onBack) }
            )
        },
        content = { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                state = listState,
                content = {
                    items(
                        items = formatList,
                        key = { it },
                        itemContent = { item ->
                            ListItem(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onClick(item)
                                },
                                shapes = ListItemDefaults.shapes(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    focusedShape = MaterialTheme.shapes.extraSmall,
                                    pressedShape = MaterialTheme.shapes.extraSmall,
                                ),
                                content = { Text(text = item) }
                            )
                        }
                    )
                }
            )
        }
    )
}
