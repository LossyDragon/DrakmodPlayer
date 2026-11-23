package com.lossydragon.modplayer.player

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.lossydragon.modplayer.R

/**
 * Shuffle, repeat and sub-song buttons for controllers that draw the session's buttons
 * (Android Auto, the media notification). They only reach Auto as custom session commands.
 */
@OptIn(UnstableApi::class)
internal object SessionButtons {

    private const val PREFIX = "com.lossydragon.modplayer."
    private val SHUFFLE = SessionCommand(PREFIX + "SHUFFLE", Bundle.EMPTY)
    private val REPEAT = SessionCommand(PREFIX + "REPEAT", Bundle.EMPTY)
    private val NEXT_SUBSONG = SessionCommand(PREFIX + "NEXT_SUBSONG", Bundle.EMPTY)

    /** The default commands plus ours; a button whose command is unavailable is not shown. */
    val sessionCommands: SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SHUFFLE)
            .add(REPEAT)
            .add(NEXT_SUBSONG)
            .build()

    /** Buttons showing the current modes; sub-song only when the module has more than one. */
    fun build(
        context: Context,
        shuffle: Boolean,
        repeatMode: Int,
        hasSubsongs: Boolean
    ): List<CommandButton> {
        val (shuffleIcon, shuffleLabel) = if (shuffle) {
            CommandButton.ICON_SHUFFLE_ON to R.string.chip_shuffle_on
        } else {
            CommandButton.ICON_SHUFFLE_OFF to R.string.chip_shuffle_off
        }
        val (repeatIcon, repeatLabel) = when (repeatMode) {
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL to R.string.chip_repeat_all
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE to R.string.chip_repeat_one
            else -> CommandButton.ICON_REPEAT_OFF to R.string.chip_repeat_off
        }
        return buildList {
            add(button(context, SHUFFLE, shuffleIcon, shuffleLabel))
            add(button(context, REPEAT, repeatIcon, repeatLabel))
            if (hasSubsongs) {
                val icon = CommandButton.ICON_QUEUE_NEXT
                add(button(context, NEXT_SUBSONG, icon, R.string.auto_next_subsong))
            }
        }
    }

    /** Runs [command] on [player]. False if it is not one of ours. */
    fun handle(command: SessionCommand, player: ModPlayer): Boolean {
        when (command.customAction) {
            SHUFFLE.customAction -> player.shuffleModeEnabled = !player.shuffleModeEnabled

            REPEAT.customAction -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }

            NEXT_SUBSONG.customAction -> player.nextSequence()

            else -> return false
        }
        return true
    }

    private fun button(context: Context, command: SessionCommand, icon: Int, label: Int) =
        CommandButton.Builder(icon)
            .setSessionCommand(command)
            .setDisplayName(context.getString(label))
            .build()
}
