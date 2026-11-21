package com.lossydragon.modplayer.ui.components

import androidx.compose.material3.SnackbarHostState

/**
 * The one snackbar queue for the activity. [com.lossydragon.modplayer.ui.MainNavigation] and
 * [com.lossydragon.modplayer.ui.screens.player.PlayerScreen] are sibling root nav entries, so
 * they share it through this default rather than a parameter from MainActivity.
 */
val AppSnackbarHostState = SnackbarHostState()
