package com.lossydragon.modplayer.ui.screens.preferences.section

import android.content.res.Resources
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.alorma.compose.settings.ui.expressive.SettingsSwitch
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.FlagItem
import com.lossydragon.modplayer.ui.screens.preferences.components.MultiChoiceAlertDialog
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceItem
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.screens.preferences.components.SettingsSlider
import com.lossydragon.modplayer.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.native.Player
import com.lossydragon.native.ResamplerMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private fun formatFlagsLabel(resources: Resources, flags: Int): String {
    val parts = buildList {
        if (flags and Player.XMP_FORMAT_8BIT != 0) {
            add(resources.getString(R.string.eight_bit))
        } else if (flags and Player.XMP_FORMAT_32BIT != 0) {
            add(resources.getString(R.string.thirty_two_bit))
        } else {
            add(resources.getString(R.string.sixteen_bit))
        }
        if (flags and Player.XMP_FORMAT_UNSIGNED != 0) {
            add(resources.getString(R.string.unsigned))
        }
        if (flags and Player.XMP_FORMAT_MONO != 0) {
            add(resources.getString(R.string.mono))
        } else {
            add(resources.getString(R.string.stereo))
        }
    }
    return parts.joinToString(", ")
}

private val sampleRateOptions = persistentListOf(
    PreferenceItem(
        key = "8000",
        title = R.string.pref_sample_item_8000,
        description = R.string.pref_sample_item_8000_desc
    ),
    PreferenceItem(
        key = "22050",
        title = R.string.pref_sample_item_22050,
        description = R.string.pref_sample_item_22050_desc
    ),
    PreferenceItem(
        key = "44100",
        title = R.string.pref_sample_item_44100,
        description = R.string.pref_sample_item_44100_desc
    ),
    PreferenceItem(
        key = "48000",
        title = R.string.pref_sample_item_48000,
        description = R.string.pref_sample_item_48000_desc
    ),
)

private val flagItems = persistentListOf(
    FlagItem(
        flag = Player.XMP_FLAGS_VBLANK,
        title = R.string.pref_flags_item_vblank,
        description = R.string.pref_flags_item_vblank_desc
    ),
    FlagItem(
        flag = Player.XMP_FLAGS_FX9BUG,
        title = R.string.pref_flags_item_fx9,
        description = R.string.pref_flags_item_fx9_desc
    ),
    FlagItem(
        flag = Player.XMP_FLAGS_FIXLOOP,
        title = R.string.pref_flags_item_fix_loop,
        description = R.string.pref_flags_item_fix_loop_desc
    ),
    FlagItem(
        flag = Player.XMP_FLAGS_A500,
        title = R.string.pref_flags_item_a500,
        description = R.string.pref_flags_item_a500_desc
    ),
)

private val interpOptions = persistentListOf(
    PreferenceItem(
        key = ResamplerMode.NEAREST.id.toString(),
        title = R.string.pref_interp_item_nearest,
        description = R.string.pref_interp_item_nearest_desc
    ),
    PreferenceItem(
        key = ResamplerMode.LINEAR.id.toString(),
        title = R.string.pref_interp_item_linear,
        description = R.string.pref_interp_item_linear_desc
    ),
    PreferenceItem(
        key = ResamplerMode.CUBIC.id.toString(),
        title = R.string.pref_interp_item_spline,
        description = R.string.pref_interp_item_spline_desc
    ),
    PreferenceItem(
        key = ResamplerMode.OPENMPT_AMIGA_A500.id.toString(),
        title = R.string.pref_interp_item_amiga_a500,
        description = R.string.pref_interp_item_amiga_a500_desc
    ),
    PreferenceItem(
        key = ResamplerMode.OPENMPT_AMIGA_A1200.id.toString(),
        title = R.string.pref_interp_item_amiga_a1200,
        description = R.string.pref_interp_item_amiga_a1200_desc
    ),
)

private val formatOptions = persistentListOf(
    FlagItem(
        flag = Player.XMP_FORMAT_8BIT,
        title = R.string.pref_format_item_8bit,
        description = R.string.pref_format_item_8bit_desc
    ),
    FlagItem(
        flag = Player.XMP_FORMAT_UNSIGNED,
        title = R.string.pref_format_item_unsigned,
        description = R.string.pref_format_item_unsigned_desc
    ),
    FlagItem(
        flag = Player.XMP_FORMAT_MONO,
        title = R.string.pref_format_item_mono,
        description = R.string.pref_format_item_mono_desc
    ),
    FlagItem(
        flag = Player.XMP_FORMAT_32BIT,
        title = R.string.pref_format_item_32bit,
        description = R.string.pref_format_item_32bit_desc
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceXmp(
    colors: ListItemColors
) {
    val resource = LocalResources.current
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val sampleRate by prefs.getSampleRateFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_SAMPLE_RATE)
    val formatFlags by prefs.getPlayerFormatFlow()
        .collectAsStateWithLifecycle(initialValue = 0)
    val bufferMs by prefs.getBufferMsFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_BUFFER_MS)
    val flags by prefs.getPlayerFlagsFlow()
        .collectAsStateWithLifecycle(initialValue = 0)
    val pan by prefs.getDefaultPanFlow()
        .collectAsStateWithLifecycle(Player.DEFAULT_PAN_SEPARATION)
    val mix by prefs.getStereoMixFlow()
        .collectAsStateWithLifecycle(Player.DEFAULT_STEREO_MIX)
    val dspEffect by prefs.getDspEffectFlow()
        .collectAsStateWithLifecycle(initialValue = Player.XMP_DSP_LOWPASS)
    val interp by prefs.getInterpolationTypeFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_INTERPOLATION)
    val volume by prefs.getPlayerVolumeFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_PLAYER_VOLUME)
    val boost by prefs.getVolumeBoostFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_VOLUME_BOOST)

    var isSampleRateShowing by remember { mutableStateOf(false) }
    if (isSampleRateShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = sampleRate.toString(),
            items = sampleRateOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()?.let { scope.launch { prefs.setSampleRate(it) } }
                isSampleRateShowing = false
            },
        )
    }

    var isFlagsShowing by remember { mutableStateOf(false) }
    if (isFlagsShowing) {
        MultiChoiceAlertDialog(
            currentFlags = flags,
            items = flagItems,
            onConfirm = { newFlags ->
                scope.launch { prefs.setPlayerFlags(newFlags) }
                isFlagsShowing = false
            },
            onDismiss = { isFlagsShowing = false }
        )
    }

    var isInterpShowing by remember { mutableStateOf(false) }
    if (isInterpShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = interp.toString(),
            items = interpOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()?.let { scope.launch { prefs.setInterpolationType(it) } }
                isInterpShowing = false
            },
        )
    }

    var showFormatDialog by remember { mutableStateOf(false) }
    if (showFormatDialog) {
        MultiChoiceAlertDialog(
            currentFlags = formatFlags,
            items = formatOptions,
            onConfirm = { newFlags ->
                val sanitized = if ((newFlags and Player.XMP_FORMAT_8BIT) != 0) {
                    // 8-bit wins, clear 32-bit
                    newFlags and Player.XMP_FORMAT_32BIT.inv()
                } else {
                    newFlags
                }
                scope.launch { prefs.setPlayerFormat(sanitized) }
                showFormatDialog = false
            },
            onDismiss = { showFormatDialog = false },
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_libxmp),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_sample_rate)) },
                subtitle = { Text(text = stringResource(R.string.pref_sample_rate_desc)) },
                action = { Text(text = stringResource(R.string.size_hz, sampleRate)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 10),
                onClick = { isSampleRateShowing = true }
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_output_format)) },
                subtitle = { Text(text = stringResource(R.string.pref_output_format_desc)) },
                action = { Text(text = formatFlagsLabel(resource, formatFlags)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 10),
                onClick = { showFormatDialog = true },
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_interpolation_type)) },
                subtitle = { Text(text = stringResource(R.string.pref_interpolation_type_desc)) },
                action = {
                    val text = when (interp) {
                        ResamplerMode.NEAREST.id -> stringResource(R.string.interp_nearest)

                        ResamplerMode.LINEAR.id -> stringResource(R.string.interp_linear)

                        ResamplerMode.CUBIC.id -> stringResource(R.string.interp_spline)

                        ResamplerMode.OPENMPT_AMIGA_A500.id ->
                            stringResource(R.string.interp_openmpt_amiga_a500)

                        ResamplerMode.OPENMPT_AMIGA_A1200.id ->
                            stringResource(R.string.interp_openmpt_amiga_a1200)

                        else -> stringResource(R.string.unknown)
                    }
                    Text(text = text)
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(2, 10),
                onClick = { isInterpShowing = true }
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_flags)) },
                subtitle = { Text(text = stringResource(R.string.pref_flags_desc)) },
                action = {
                    val count = flagItems.count { (flags and it.flag) != 0 }
                    val text = if (count == 0) {
                        stringResource(R.string.none)
                    } else {
                        stringResource(R.string.pref_flags_enabled, count)
                    }
                    Text(text = text)
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(3, 10),
                onClick = { isFlagsShowing = true }
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_dsp)) },
                subtitle = { Text(text = stringResource(R.string.pref_dsp_desc)) },
                state = dspEffect != Player.XMP_DSP_NONE,
                onCheckedChange = { enabled ->
                    scope.launch {
                        val value = if (enabled) Player.XMP_DSP_LOWPASS else Player.XMP_DSP_NONE
                        prefs.setDspEffect(value)
                    }
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(4, 10),
            )
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_buffer_ms)) },
                subtitle = { Text(text = stringResource(R.string.pref_buffer_ms_desc)) },
                action = { Text(text = stringResource(R.string.pref_buffer_ms_count, bufferMs)) },
                colors = colors,
                steps = ((Player.MAX_BUFFER_MS - Player.MIN_BUFFER_MS) / 40) - 1, // = 22
                valueRange = Player.MIN_BUFFER_MS.toFloat()..Player.MAX_BUFFER_MS.toFloat(),
                value = bufferMs.toFloat(),
                onValueChange = { scope.launch { prefs.setBufferMs(it.toInt()) } },
                shapes = ListItemDefaults.segmentedShapes(5, 10),
            )
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_volume)) },
                subtitle = { Text(text = stringResource(R.string.pref_volume_desc)) },
                action = { Text(text = stringResource(R.string.value_percent, volume)) },
                colors = colors,
                steps = 0,
                valueRange = 0f..100f,
                value = volume.toFloat(),
                onValueChange = { scope.launch { prefs.setPlayerVolume(it.toInt()) } },
                shapes = ListItemDefaults.segmentedShapes(6, 10),
            )
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_boost)) },
                subtitle = { Text(text = stringResource(R.string.pref_boost_desc)) },
                action = { Text(text = stringResource(R.string.value_x, boost)) },
                colors = colors,
                steps = 2,
                valueRange = 0f..3f,
                value = boost.toFloat(),
                onValueChange = { scope.launch { prefs.setVolumeBoost(it.toInt()) } },
                shapes = ListItemDefaults.segmentedShapes(7, 10),
            )
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_stereo_mix)) },
                subtitle = { Text(text = stringResource(R.string.pref_stereo_mix_desc)) },
                action = { Text(text = stringResource(R.string.value_percent, mix)) },
                colors = colors,
                steps = 0,
                valueRange = -100f..100f,
                value = mix.toFloat(),
                onValueChange = { scope.launch { prefs.setStereoMix(it.toInt()) } },
                shapes = ListItemDefaults.segmentedShapes(8, 10),
            )
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_pan)) },
                subtitle = { Text(text = stringResource(R.string.pref_pan_desc)) },
                action = { Text(text = stringResource(R.string.value_percent, pan)) },
                colors = colors,
                steps = 4,
                valueRange = 0f..100f,
                value = pan.toFloat(),
                onValueChange = { scope.launch { prefs.setDefaultPan(it.toInt()) } },
                shapes = ListItemDefaults.segmentedShapes(9, 10),
            )
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview
@Composable
private fun Preview() {
    AppTheme {
        val colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        Surface {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PreferenceXmp(colors)
            }
        }
    }
}
