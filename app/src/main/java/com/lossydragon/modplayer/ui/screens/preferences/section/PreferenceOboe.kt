package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.components.collectPref
import com.lossydragon.modplayer.ui.components.rememberAppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceItem
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.renderer.Player
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

// The first entry of each list is the default, shown for an unknown stored value.
private val perfModeOptions = persistentListOf(
    PreferenceItem(
        key = Player.OBOE_PERFMODE_LOWLATENCY.toString(),
        title = R.string.pref_audio_perf_item_lowlatency,
        description = R.string.pref_audio_perf_item_lowlatency_desc
    ),
    PreferenceItem(
        key = Player.OBOE_PERFMODE_NONE.toString(),
        title = R.string.pref_audio_perf_item_none,
        description = R.string.pref_audio_perf_item_none_desc
    ),
    PreferenceItem(
        key = Player.OBOE_PERFMODE_POWERSAVING.toString(),
        title = R.string.pref_audio_perf_item_power_saving,
        description = R.string.pref_audio_perf_item_power_saving_desc
    ),
)

private val apiOptions = persistentListOf(
    PreferenceItem(
        key = Player.OBOE_AUDIO_API_UNSPECIFIED.toString(),
        title = R.string.pref_audio_api_item_unspecified,
        description = R.string.pref_audio_api_item_unspecified_desc
    ),
    PreferenceItem(
        key = Player.OBOE_AUDIO_API_AAUDIO.toString(),
        title = R.string.pref_audio_api_item_aaudio,
        description = R.string.pref_audio_api_item_aaudio_desc
    ),
    PreferenceItem(
        key = Player.OBOE_AUDIO_API_OPENSLES.toString(),
        title = R.string.pref_audio_api_item_opensles,
        description = R.string.pref_audio_api_item_opensles_desc
    ),
)

@Composable
fun PreferenceOboe(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = rememberAppPreferences()

    val perfMode by prefs.collectPref(Player.OBOE_PERFMODE_LOWLATENCY) { getOboePerfModeFlow() }
    val audioApi by prefs.collectPref(Player.OBOE_AUDIO_API_UNSPECIFIED) { getOboeAudioApiFlow() }

    var isPerfModeShowing by remember { mutableStateOf(false) }
    if (isPerfModeShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = perfMode.toString(),
            items = perfModeOptions,
            onItemSelected = { key ->
                isPerfModeShowing = false
                key?.toIntOrNull()?.let { scope.launch { prefs.setOboePerfMode(it) } }
            },
        )
    }

    var isAudioApiShowing by remember { mutableStateOf(false) }
    if (isAudioApiShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = audioApi.toString(),
            items = apiOptions,
            onItemSelected = { key ->
                isAudioApiShowing = false
                key?.toIntOrNull()?.let { scope.launch { prefs.setOboeAudioApi(it) } }
            },
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_oboe),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                onClick = { isPerfModeShowing = true },
                title = { Text(text = stringResource(R.string.pref_performance_mode)) },
                subtitle = { Text(text = stringResource(R.string.pref_performance_mode_desc)) },
                action = {
                    val item = perfModeOptions.find { it.key == perfMode.toString() }
                        ?: perfModeOptions.first()
                    Text(text = stringResource(item.title))
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 1),
            )
            SettingsMenuLink(
                onClick = { isAudioApiShowing = true },
                title = { Text(text = stringResource(R.string.pref_audio_api)) },
                subtitle = { Text(text = stringResource(R.string.pref_audio_api_desc)) },
                action = {
                    val item = apiOptions.find { it.key == audioApi.toString() }
                        ?: apiOptions.first()
                    Text(text = stringResource(item.title))
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 1),
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            val colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            PreferenceOboe(colors = colors)
        }
    }
}
