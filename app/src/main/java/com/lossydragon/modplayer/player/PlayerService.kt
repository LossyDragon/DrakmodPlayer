package com.lossydragon.modplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.lossydragon.modplayer.MainActivity
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.dao.ModuleDao
import com.lossydragon.modplayer.db.dao.PlaylistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * [MediaLibraryService] that bridges [ModPlayer] to the Media3 session API.
 */
@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {

    private companion object {
        const val NOTIFICATION_ID = 669
        const val CHANNEL_ID = "669"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val player: ModPlayer by inject()
    private val engine: Engine by inject()
    private val prefs: AppPreferences by inject()
    private val playlistDao: PlaylistDao by inject()
    private val modulesDao: ModuleDao by inject()

    private lateinit var mediaLibrarySession: MediaLibrarySession

    private var hasSubsongs = false

    /** Redraws the session buttons, which show the current modes. */
    private val modeListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateButtons()
        override fun onRepeatModeChanged(repeatMode: Int) = updateButtons()
    }

    override fun onCreate() {
        super.onCreate()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val callback = AutoBrowseCallback(this, player, prefs, playlistDao, modulesDao, scope)
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setId(getString(R.string.app_name) + "_session")
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(sessionButtons())
            .build()
        callback.observeLibrary(mediaLibrarySession)

        player.addListener(modeListener)
        scope.launch(Dispatchers.Main) {
            engine.modVarsFlow.map { it.seqData.size > 1 }.distinctUntilChanged().collect {
                hasSubsongs = it
                updateButtons()
            }
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .build()
        )

        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Timber.e("Foreground service start not allowed")
            }
        })

        scope.launch(Dispatchers.Main) { player.restoreQueue() }
    }

    override fun onDestroy() {
        // The player outlives this service; a stale listener would poke a released session.
        player.removeListener(modeListener)
        mediaLibrarySession.release()
        // [player] is a process singleton reused by the next service instance, so only the
        // native engine is torn down here — see [ModPlayer.releaseEngine].
        player.releaseEngine()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Nothing to release here: stopSelf() leads to onDestroy(), which does it once.
        if (!isPlaybackOngoing) stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaLibrarySession

    private fun sessionButtons() =
        SessionButtons.build(this, player.shuffleModeEnabled, player.repeatMode, hasSubsongs)

    private fun updateButtons() = mediaLibrarySession.setMediaButtonPreferences(sessionButtons())
}
