package com.lossydragon.modplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.google.common.util.concurrent.ListenableFuture
import com.lossydragon.modplayer.player.PlayerService
import com.lossydragon.modplayer.ui.MainNavigation
import com.lossydragon.modplayer.ui.NavKeyRoot
import com.lossydragon.modplayer.ui.screens.player.PlayerScreen
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.requestNotificationPermission
import com.lossydragon.modplayer.util.requestWriteStoragePermission
import com.lossydragon.modplayer.util.setEdgeToEdgeConfig

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission = registerForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        callback = { /* No-Op */ }
    )

    private val storagePermission = registerForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        callback = { /* No-Op */ }
    )

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlayerService::class.java)
        )
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setEdgeToEdgeConfig()

        requestNotificationPermission {
            @SuppressLint("InlinedApi")
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestWriteStoragePermission {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        setContent {
            AppTheme {
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
                                onBack = ::finish,
                            )
                        }
                        entry<NavKeyRoot.Player> {
                            PlayerScreen(onBack = backStack::removeLastOrNull)
                        }
                    }
                )
            }
        }
    }
}
