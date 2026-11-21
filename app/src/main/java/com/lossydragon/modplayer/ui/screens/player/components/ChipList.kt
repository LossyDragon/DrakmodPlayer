package com.lossydragon.modplayer.ui.screens.player.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.media3.common.Player
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf

private data class ChipItem(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
internal fun ChipList(
    isShuffle: Boolean,
    repeatMode: Int,
    isSubSongs: Boolean,
    onShuffle: () -> Unit,
    onLoop: () -> Unit,
    onModInfo: () -> Unit,
    onShowSongMessage: () -> Unit,
    onShowSongInstruments: () -> Unit,
    onPlaySubSongs: () -> Unit,
    onShowDurations: () -> Unit,
    onAudioInfo: () -> Unit
) {
    val assistChips = persistentListOf(
        ChipItem(
            label = R.string.chip_show_subsongs,
            icon = Icons.Default.FormatListNumbered,
            onClick = onShowDurations
        ),
        ChipItem(
            label = R.string.chip_mod_info,
            icon = Icons.Default.Info,
            onClick = onModInfo
        ),
        ChipItem(
            label = R.string.chip_mod_instruments,
            icon = Icons.AutoMirrored.Filled.List,
            onClick = onShowSongInstruments
        ),
        ChipItem(
            label = R.string.chip_song_message,
            icon = Icons.AutoMirrored.Filled.Comment,
            onClick = onShowSongMessage
        ),
        ChipItem(
            label = R.string.chip_audio_info,
            icon = Icons.Default.BugReport,
            onClick = onAudioInfo
        ),
    )

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            space = 8.dp,
            alignment = Alignment.CenterHorizontally
        ),
        contentPadding = PaddingValues(horizontal = 6.dp),
        content = {
            item {
                FilterChip(
                    selected = isShuffle,
                    onClick = onShuffle,
                    label = {
                        val label = if (isShuffle) {
                            R.string.chip_shuffle_on
                        } else {
                            R.string.chip_shuffle_off
                        }
                        Text(text = stringResource(label))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
            item {
                FilterChip(
                    selected = repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = onLoop,
                    label = {
                        val label = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.string.chip_repeat_one
                            Player.REPEAT_MODE_ALL -> R.string.chip_repeat_all
                            else -> R.string.chip_repeat_off
                        }
                        Text(text = stringResource(label))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    leadingIcon = {
                        val loopIcon = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            Player.REPEAT_MODE_ALL -> Icons.Default.RepeatOn
                            else -> Icons.Default.Repeat
                        }

                        Icon(
                            imageVector = loopIcon,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
            item {
                FilterChip(
                    selected = isSubSongs,
                    onClick = onPlaySubSongs,
                    label = {
                        val label = if (isSubSongs) {
                            R.string.chip_subsongs_on
                        } else {
                            R.string.chip_subsongs_off
                        }
                        Text(text = stringResource(label))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }

            items(assistChips) { chip ->
                AssistChip(
                    onClick = chip.onClick,
                    label = { Text(text = stringResource(chip.label)) },
                    leadingIcon = {
                        Icon(
                            imageVector = chip.icon,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Box(modifier = Modifier.fillMaxWidth()) {
            ChipList(
                isShuffle = false,
                repeatMode = Player.REPEAT_MODE_ONE,
                isSubSongs = false,
                onShuffle = {},
                onLoop = {},
                onModInfo = {},
                onShowSongMessage = {},
                onShowSongInstruments = {},
                onPlaySubSongs = {},
                onShowDurations = {},
                onAudioInfo = {},
            )
        }
    }
}
