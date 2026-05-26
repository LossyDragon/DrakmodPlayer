package com.lossydragon.modplayer.ui.screens.preferences

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.platform.*
import com.lossydragon.modplayer.ui.components.BackButton
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PreferencesFormats(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    formatList: ImmutableList<String>,
    onClick: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Formats (${formatList.size})") },
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
                                    shape = MaterialTheme.shapes.small,
                                    focusedShape = MaterialTheme.shapes.small,
                                    pressedShape = MaterialTheme.shapes.small,
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
