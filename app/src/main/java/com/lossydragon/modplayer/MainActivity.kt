package com.lossydragon.modplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.google.common.util.concurrent.ListenableFuture
import com.lossydragon.modplayer.data.SafDataSource
import com.lossydragon.modplayer.db.entity.ModuleEntity
import com.lossydragon.modplayer.player.PlayerService
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.MainNavigation
import com.lossydragon.modplayer.ui.activityViewModel
import com.lossydragon.modplayer.ui.screens.player.PlayerScreen
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.requestNotificationPermission
import com.lossydragon.modplayer.util.requestWriteStoragePermission
import com.lossydragon.modplayer.util.setEdgeToEdgeConfig
import com.lossydragon.modplayer.util.toModuleEntity
import com.lossydragon.native.Player
import com.lossydragon.native.RenderingBackend
import com.lossydragon.native.model.ModInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.android.ext.android.inject
import timber.log.Timber

private sealed class NavKeyRoot : NavKey {
    @Serializable
    data object Main : NavKeyRoot()

    @Serializable
    data object Player : NavKeyRoot()
}

class MainActivity : ComponentActivity() {

    private val pendingPlayerModule = MutableStateFlow<ModuleEntity?>(null)

    private val saf: SafDataSource by inject()

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val permissionRequest = registerForActivityResult(
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
     * Resolves and probes the URI off the main thread; only real modules reach the player.
     */
    private fun handleViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        Timber.d("Handle Intent Uri: $uri")
        val persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        lifecycleScope.launch(Dispatchers.IO) {
            // The module ends up in the persisted queue; keep the grant so a restart can reload it.
            if (persistable) {
                try {
                    saf.persistFileGrant(uri)
                } catch (e: SecurityException) {
                    Timber.w(e, "Could not persist grant for $uri")
                }
            }
            val module = runCatching { uri.toModuleEntity(applicationContext) }
                .onFailure { Timber.w(it, "Could not resolve $uri") }
                .getOrNull() ?: return@launch
            val info = ModInfo()
            if (!Player.testFromFd(applicationContext, uri, info)) {
                Timber.w("Not a playable module: $uri")
                return@launch
            }
            pendingPlayerModule.value = module.copy(
                isValidModule = true,
                moduleName = info.name.trim(),
                moduleType = info.type.trim(),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setEdgeToEdgeConfig()

        requestNotificationPermission {
            @SuppressLint("InlinedApi")
            permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestWriteStoragePermission {
            permissionRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        handleViewIntent(intent)

        setContent {
            AppTheme {
                val backStack = rememberNavBackStack(NavKeyRoot.Main)
                fun openPlayer() {
                    if (backStack.lastOrNull() !is NavKeyRoot.Player) {
                        backStack.add(NavKeyRoot.Player)
                    }
                }

                val playerViewModel = activityViewModel<PlayerViewModel>()

                val needsBackendSetup by playerViewModel.needsBackendSetup
                    .collectAsStateWithLifecycle()
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
                            openPlayer()
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
                                onNavigateToPlayer = ::openPlayer,
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
