package com.lossydragon.modplayer.ui.screens.preferences

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
import com.lossydragon.renderer.Player
import com.lossydragon.renderer.RenderingBackend
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable

private sealed interface NavKeyPreferences : NavKey {
    @Serializable
    data object Preferences : NavKeyPreferences

    @Serializable
    data object About : NavKeyPreferences

    @Serializable
    data class Formats(val backendId: Int) : NavKeyPreferences
}

@Composable
fun NavPreferences(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val backStack = rememberNavBackStack(NavKeyPreferences.Preferences)

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
                    onMptFormats = {
                        backStack.add(NavKeyPreferences.Formats(RenderingBackend.OPENMPT.id))
                    },
                    onXmpFormats = {
                        backStack.add(NavKeyPreferences.Formats(RenderingBackend.LIBXMP.id))
                    },
                    onAbout = { backStack.add(NavKeyPreferences.About) }
                )
            }
            entry<NavKeyPreferences.About> {
                PreferencesAbout(
                    modifier = modifier,
                    onBack = backStack::removeLastOrNull
                )
            }
            entry<NavKeyPreferences.Formats> {
                val context = LocalContext.current
                val formats = remember(it.backendId) {
                    Player.getFormats(RenderingBackend.fromId(it.backendId))
                        .sorted()
                        .toPersistentList()
                }
                PreferencesFormats(
                    modifier = modifier,
                    onBack = backStack::removeLastOrNull,
                    formatList = formats,
                    onClick = context::shareLink
                )
            }
        }
    )
}
