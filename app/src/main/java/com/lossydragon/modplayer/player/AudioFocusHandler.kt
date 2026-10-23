package com.lossydragon.modplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import timber.log.Timber

/**
 * Wraps Android's [AudioFocusRequest] lifecycle.
 */
class AudioFocusHandler(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** True while the app holds [AudioManager.AUDIOFOCUS_GAIN]. */
    var hasFocus = false
        private set

    /**
     * Requests [AudioManager.AUDIOFOCUS_GAIN] if not already held.
     * @param onGain Invoked when focus is (re)granted after a transient loss.
     * @param onLoss Invoked when focus is lost permanently or transiently.
     * @param onDuck Invoked with true while another app wants us quieter, false when it is done.
     */
    fun request(onGain: () -> Unit, onLoss: () -> Unit, onDuck: (Boolean) -> Unit) {
        if (hasFocus) return
        // Abandon any previous request so its listener doesn't fire alongside the new one.
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null

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
                        onDuck(false)
                        hasFocus = true
                        onGain()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)

                    // LOSS_TRANSIENT is followed by GAIN once the other app is done.
                    // LOSS (permanent, e.g. the car radio taking over) is not: Android never
                    // sends GAIN after it. Staying paused until the user presses play, which
                    // re-requests focus, is the intended behaviour — not a stuck player.
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
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Audio focus result=$result")
    }

    /** Releases audio focus. Call from the service's onDestroy. */
    fun abandon() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        hasFocus = false
    }
}
