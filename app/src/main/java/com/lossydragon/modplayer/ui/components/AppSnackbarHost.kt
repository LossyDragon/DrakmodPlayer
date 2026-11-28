package com.lossydragon.modplayer.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * The one snackbar queue for the activity. [com.lossydragon.modplayer.ui.MainNavigation] and
 * [com.lossydragon.modplayer.ui.screens.player.PlayerScreen] are sibling root nav entries, so
 * they share it through this default rather than a parameter from MainActivity.
 */
val AppSnackbarHostState = SnackbarHostState()

private var shownPlayerError: String? = null

/** Shows a player error once, whichever root entry is on screen when it arrives. */
@Composable
fun PlayerErrorEffect(
    message: String?,
    hostState: SnackbarHostState = AppSnackbarHostState
) {
    LaunchedEffect(message) {
        if (message == shownPlayerError) return@LaunchedEffect
        shownPlayerError = message
        message?.let { hostState.showSnackbar(it) }
    }
}
