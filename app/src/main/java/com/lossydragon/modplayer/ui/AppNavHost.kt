package com.lossydragon.modplayer.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import com.lossydragon.modplayer.ui.screens.player.PlayerScreen

@Composable
fun AppNavHost(onBack: () -> Unit) {
    val backStack = rememberNavBackStack(NavKeyRoot.Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<NavKeyRoot.Main> {
                MainNavigation(
                    onNavigateToPlayer = {
                        if (backStack.lastOrNull() !is NavKeyRoot.Player) {
                            backStack.add(NavKeyRoot.Player)
                        }
                    },
                    onBack = onBack,
                )
            }
            entry<NavKeyRoot.Player> {
                PlayerScreen(onBack = backStack::removeLastOrNull)
            }
        }
    )
}
