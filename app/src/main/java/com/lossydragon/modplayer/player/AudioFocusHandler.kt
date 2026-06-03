package com.lossydragon.modplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
import org.helllabs.libxmp.Xmp
import timber.log.Timber

/**
 * Wraps Android's [AudioFocusRequest] lifecycle.
 */
class AudioFocusHandler(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** True while the app holds [AudioManager.AUDIOFOCUS_GAIN]. */
    var hasFocus: Boolean = false
        private set

    /**
     * Requests [AudioManager.AUDIOFOCUS_GAIN] if not already held.
     * @param onGain Invoked when focus is (re)granted after a transient loss.
     * @param onLoss Invoked when focus is lost permanently or transiently.
     */
    fun request(onGain: () -> Unit, onLoss: () -> Unit) {
        if (hasFocus) return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                Timber.i("Audio Focus Changed: $change")
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Xmp.setPlayer(Xmp.XMP_PLAYER_VOLUME, Xmp.DEFAULT_PLAYER_VOLUME)
                        hasFocus = true
                        onGain()
                    }

                    AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Xmp.setPlayer(Xmp.XMP_PLAYER_VOLUME, 70) // Yes?
                    }

                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasFocus = false
                        onLoss()
                    }
                }
            }
            .build()

        focusRequest = request

        val result = audioManager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) hasFocus = true

        Timber.d("Audio focus result=$result")
    }

    /** Releases audio focus. Call from the service's onDestroy. */
    fun abandon() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
    }
}
