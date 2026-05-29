package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceItem
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.helllabs.libxmp.Xmp
import org.koin.compose.koinInject

private fun Int.toPerfModeKey() = when (this) {
    Xmp.OBOE_PERFMODE_NONE -> "none"
    Xmp.OBOE_PERFMODE_POWERSAVING -> "powersaving"
    else -> "lowlatency"
}

private fun Int.toAudioApiKey() = when (this) {
    Xmp.OBOE_AUDIO_API_AAUDIO -> "aaudio"
    Xmp.OBOE_AUDIO_API_OPENSLES -> "opensles"
    else -> "unspecified"
}

private val perfModeOptions = persistentListOf(
    PreferenceItem(
        key = "lowlatency",
        title = R.string.pref_audio_perf_item_lowlatency,
        description = R.string.pref_audio_perf_item_lowlatency_desc
    ),
    PreferenceItem(
        key = "none",
        title = R.string.pref_audio_perf_item_none,
        description = R.string.pref_audio_perf_item_none_desc
    ),
    PreferenceItem(
        key = "powersaving",
        title = R.string.pref_audio_perf_item_power_saving,
        description = R.string.pref_audio_perf_item_power_saving_desc
    ),
)

private val apiOptions = persistentListOf(
    PreferenceItem(
        key = "unspecified",
        title = R.string.pref_audio_api_item_unspecified,
        description = R.string.pref_audio_api_item_unspecified_desc
    ),
    PreferenceItem(
        key = "aaudio",
        title = R.string.pref_audio_api_item_aaudio,
        description = R.string.pref_audio_api_item_aaudio_desc
    ),
    PreferenceItem(
        key = "opensles",
        title = R.string.pref_audio_api_item_opensles,
        description = R.string.pref_audio_api_item_opensles_desc
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceOboe(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val perfMode by prefs.getOboePerfModeFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.OBOE_PERFMODE_LOWLATENCY)
    val audioApi by prefs.getOboeAudioApiFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.OBOE_AUDIO_API_UNSPECIFIED)

    var isPerfModeShowing by remember { mutableStateOf(false) }
    if (isPerfModeShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = perfMode.toPerfModeKey(),
            items = perfModeOptions,
            onItemSelected = { key ->
                fun String.toPerfModeInt() = when (this) {
                    "none" -> Xmp.OBOE_PERFMODE_NONE
                    "powersaving" -> Xmp.OBOE_PERFMODE_POWERSAVING
                    else -> Xmp.OBOE_PERFMODE_LOWLATENCY
                }
                isPerfModeShowing = false
                key?.let { scope.launch { prefs.setOboePerfMode(it.toPerfModeInt()) } }
            },
        )
    }

    var isAudioApiShowing by remember { mutableStateOf(false) }
    if (isAudioApiShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = audioApi.toAudioApiKey(),
            items = apiOptions,
            onItemSelected = { key ->
                fun String.toAudioApiInt() = when (this) {
                    "aaudio" -> Xmp.OBOE_AUDIO_API_AAUDIO
                    "opensles" -> Xmp.OBOE_AUDIO_API_OPENSLES
                    else -> Xmp.OBOE_AUDIO_API_UNSPECIFIED
                }
                isAudioApiShowing = false
                key?.let { scope.launch { prefs.setOboeAudioApi(it.toAudioApiInt()) } }
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
                    val text = perfModeOptions.find { it.key == perfMode.toPerfModeKey() }!!.title
                    Text(text = stringResource(text))
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 3),
            )
            SettingsMenuLink(
                onClick = { isAudioApiShowing = true },
                title = { Text(text = stringResource(R.string.pref_audio_api)) },
                subtitle = { Text(text = stringResource(R.string.pref_audio_api_desc)) },
                action = {
                    val text = apiOptions.find { it.key == audioApi.toAudioApiKey() }!!.title
                    Text(text = stringResource(text))
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(2, 3),
            )
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
