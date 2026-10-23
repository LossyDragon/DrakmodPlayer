package com.lossydragon.modplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.google.common.util.concurrent.ListenableFuture
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.PlayerService
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.MainNavigation
import com.lossydragon.modplayer.ui.screens.player.PlayerScreen
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.requestNotificationPermission
import com.lossydragon.modplayer.util.requestWriteStoragePermission
import com.lossydragon.modplayer.util.setEdgeToEdgeConfig
import com.lossydragon.modplayer.util.takeReadPermission
import com.lossydragon.modplayer.util.toModuleEntity
import com.lossydragon.native.RenderingBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel
import timber.log.Timber

private sealed class NavKeyRoot : NavKey {
    @Serializable
    data object Main : NavKeyRoot()

    @Serializable
    data object Player : NavKeyRoot()
}

class MainActivity : ComponentActivity() {

    private val pendingPlayerModule = MutableStateFlow<ModuleEntity?>(null)

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission = registerForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        callback = { /* No-Op */ }
    )

    private val storagePermission = registerForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        callback = { /* No-Op */ }
    )

    /**
     * The UI drives [com.lossydragon.modplayer.player.ModPlayer] directly (it is a Koin
     * singleton in this process), so this controller is never used for commands. It exists
     * only to keep [PlayerService] bound — and therefore alive — while the activity is
     * visible, which is what lets the session/notification come up before playback starts.
     */
    override fun onStart() {
        super.onStart()
        if (controllerFuture != null) return
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * Handles an [Intent.ACTION_VIEW] intent from an external app (e.g. a file manager).
     * Converts the URI to a [ModuleEntity] and queues it for navigation to the player screen.
     */
    private fun handleViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        Timber.d("Handle Intent Uri: $uri")
        // The grant that comes with ACTION_VIEW is usually temporary, but the module ends up in
        // the persisted queue. Persist the grant when the sender allows it so restoring the
        // queue after a restart doesn't hit a SecurityException.
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            try {
                takeReadPermission(uri)
            } catch (e: SecurityException) {
                Timber.w(e, "Could not persist grant for $uri")
            }
        }
        val module = uri.toModuleEntity(this) ?: return
        pendingPlayerModule.value = module
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

        handleViewIntent(intent)

        setContent {
            AppTheme {
                val backStack = rememberNavBackStack(NavKeyRoot.Main)

                val playerViewModel = koinViewModel<PlayerViewModel>(
                    viewModelStoreOwner = LocalActivity.current as ComponentActivity
                )

                val needsBackendSetup by playerViewModel.needsBackendSetup.collectAsState()
                if (needsBackendSetup) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(text = stringResource(R.string.backend_dialog_title)) },
                        text = { Text(text = stringResource(R.string.backend_dialog_text)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    playerViewModel.selectBackend(RenderingBackend.LIBXMP)
                                },
                                content = { Text(text = stringResource(R.string.backend_libxmp)) }
                            )
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    playerViewModel.selectBackend(RenderingBackend.OPENMPT)
                                },
                                content = {
                                    Text(text = stringResource(R.string.backend_libopenmpt))
                                }
                            )
                        }
                    )
                }

                LaunchedEffect(Unit) {
                    pendingPlayerModule.collect { module ->
                        if (module != null) {
                            playerViewModel.play(module)
                            if (backStack.lastOrNull() !is NavKeyRoot.Player) {
                                backStack.add(NavKeyRoot.Player)
                            }
                            pendingPlayerModule.value = null
                        }
                    }
                }

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
