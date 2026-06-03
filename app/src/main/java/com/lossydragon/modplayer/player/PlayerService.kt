package com.lossydragon.modplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.lossydragon.modplayer.MainActivity
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.db.dao.ModuleMetadataDao
import com.lossydragon.modplayer.db.dao.PlaylistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val prefs: AppPreferences by inject()
    private val playlistDao: PlaylistDao by inject()
    private val metadataDao: ModuleMetadataDao by inject()

    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val browseCallback: AutoBrowseCallback by lazy {
        AutoBrowseCallback(
            context = this,
            prefs = prefs,
            playlistDao = playlistDao,
            metadataDao = metadataDao,
            scope = scope,
        )
    }

    override fun onCreate() {
        super.onCreate()

        mediaLibrarySession = MediaLibrarySession
            .Builder(this, player, browseCallback)
            .setId(getString(R.string.app_name) + "_session")
            .setSessionActivity(buildSessionActivity())
            .build()

        DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.app_name)
            .build()
            .also(::setMediaNotificationProvider)

        object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Timber.e("Foreground service start not allowed")
            }
        }.also(::setListener)

        scope.launch(Dispatchers.Main) {
            player.restoreQueue()
        }
    }

    override fun onDestroy() {
        player.abandonAudioFocus()
        mediaLibrarySession.release()
        player.releaseEngine()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            player.releaseEngine()
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaLibrarySession

    /** PendingIntent that brings MainActivity to the foreground when the notification is tapped. */
    private fun buildSessionActivity(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
