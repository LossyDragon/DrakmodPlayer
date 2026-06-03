package com.lossydragon.modplayer.ui.screens.preferences

import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.lossydragon.modplayer.util.shareLink
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable
import org.helllabs.libxmp.Xmp

private sealed interface NavKeyPreferences : NavKey {
    @Serializable data object Preferences : NavKeyPreferences

    @Serializable data object About : NavKeyPreferences

    @Serializable data object Formats : NavKeyPreferences
}

@Composable
fun NavPreferences(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val backStack = rememberNavBackStack(NavKeyPreferences.Preferences)

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
            entry<NavKeyPreferences.Preferences> {
                PreferencesScreen(
                    modifier = modifier,
                    snackbarHostState = snackbarHostState,
                    onBack = onBack,
                    onFormats = { backStack.add(NavKeyPreferences.Formats) },
                    onAbout = { backStack.add(NavKeyPreferences.About) }
                )
            }
            entry<NavKeyPreferences.About> {
                //   onBack = backStack::removeLastOrNull,
            }
            entry<NavKeyPreferences.Formats> {
                val context = LocalContext.current
                val formats = remember { Xmp.getFormats().toPersistentList() }
                PreferencesFormats(
                    modifier = modifier,
                    snackbarHostState = snackbarHostState,
                    onBack = backStack::removeLastOrNull,
                    formatList = formats,
                    onClick = context::shareLink
                )
            }
        }
    )
}
