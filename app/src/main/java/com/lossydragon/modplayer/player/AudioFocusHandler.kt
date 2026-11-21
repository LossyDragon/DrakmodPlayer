package com.lossydragon.modplayer.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import timber.log.Timber

enum class FocusResult { GRANTED, DELAYED, FAILED }

/**
 * Wraps Android's [AudioFocusRequest] lifecycle. All callbacks arrive on the main thread.
 */
class AudioFocusHandler(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** True while the app holds [AudioManager.AUDIOFOCUS_GAIN]. */
    var hasFocus = false
        private set

    /**
     * Requests [AudioManager.AUDIOFOCUS_GAIN] if not already held.
     * @param onGain Focus (re)granted, including after [FocusResult.DELAYED].
     * @param onLoss Focus lost; `transient` is true when a GAIN will follow once the other
     *   app is done. A permanent loss is never followed by GAIN: the user pressing play,
     *   which re-requests focus, is what gets playback back.
     * @param onDuck True while another app wants us quieter, false when it is done.
     */
    fun request(
        onGain: () -> Unit,
        onLoss: (transient: Boolean) -> Unit,
        onDuck: (Boolean) -> Unit
    ): FocusResult {
        if (hasFocus) return FocusResult.GRANTED
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

                    // A duck ends here too: a later synchronous grant sends no GAIN.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasFocus = false
                        onDuck(false)
                        onLoss(true)
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasFocus = false
                        onDuck(false)
                        onLoss(false)
                    }
                }
            }
            .build()

        focusRequest = request

        val result = audioManager.requestAudioFocus(request)
        Timber.d("Audio focus result=$result")
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) onDuck(false)
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusResult.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FocusResult.DELAYED
            else -> FocusResult.FAILED
        }
    }

    /** Releases audio focus. Call from the service's onDestroy. */
    fun abandon() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        hasFocus = false
    }
}
