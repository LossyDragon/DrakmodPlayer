package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.alorma.compose.settings.ui.expressive.SettingsSwitch
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.FlagItem
import com.lossydragon.modplayer.ui.screens.preferences.components.MultiChoiceAlertDialog
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceItem
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.screens.preferences.components.SettingsSlider
import com.lossydragon.modplayer.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.helllabs.libxmp.Xmp
import org.koin.compose.koinInject

private fun flagsSubtitle(flags: Int, items: List<FlagItem>): String =
    items.filter { (flags and it.flag) != 0 }
        .joinToString(", ") { it.title }
        .ifEmpty { "None" }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferencePlayer(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val sampleRateOptions = remember {
        persistentListOf(
            PreferenceItem("8000", "8000 Hz", "Telephone quality"),
            PreferenceItem("22050", "22050 Hz", "Low quality"),
            PreferenceItem("44100", "44100 Hz", "CD quality"),
            PreferenceItem("48000", "48000 Hz", "DVD quality"),
        )
    }
    val flagItems = remember {
        persistentListOf(
            FlagItem(Xmp.XMP_FLAGS_VBLANK, "VBlank timing", "Use vblank timing"),
            FlagItem(Xmp.XMP_FLAGS_FX9BUG, "FX9 bug", "Emulate Protracker 2.x FX9 bug"),
            FlagItem(Xmp.XMP_FLAGS_FIXLOOP, "Fix loop", "Halve sample loop values"),
            FlagItem(
                Xmp.XMP_FLAGS_A500,
                "Amiga 500 mixer",
                "Use Paula mixer for Amiga modules"
            ),
        )
    }

    val interpOptions = remember {
        persistentListOf(
            PreferenceItem(
                Xmp.XMP_INTERP_NEAREST.toString(),
                "Nearest",
                "Nearest neighbor — sharp, low CPU"
            ),
            PreferenceItem(
                Xmp.XMP_INTERP_LINEAR.toString(),
                "Linear",
                "Linear interpolation (default)"
            ),
            PreferenceItem(
                Xmp.XMP_INTERP_SPLINE.toString(),
                "Spline",
                "Cubic spline — smoothest, highest CPU"
            ),
        )
    }

    val sampleRate by prefs.getSampleRateFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.DEFAULT_SAMPLE_RATE)
    val bufferMs by prefs.getBufferMsFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.DEFAULT_BUFFER_MS)
    val flags by prefs.getPlayerFlagsFlow()
        .collectAsStateWithLifecycle(initialValue = 0)
    val pan by prefs.getDefaultPanFlow()
        .collectAsStateWithLifecycle(Xmp.DEFAULT_PAN_SEPARATION)
    val mix by prefs.getStereoMixFlow()
        .collectAsStateWithLifecycle(Xmp.DEFAULT_STEREO_MIX)
    val dspEffect by prefs.getDspEffectFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.XMP_DSP_LOWPASS)
    val interp by prefs.getInterpolationTypeFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.DEFAULT_INTERPOLATION)
    val volume by prefs.getPlayerVolumeFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.DEFAULT_PLAYER_VOLUME)
    val boost by prefs.getVolumeBoostFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.DEFAULT_VOLUME_BOOST)
    val autoResume by prefs.getAutoResumeFlow()
        .collectAsStateWithLifecycle(initialValue = false)

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

    PreferenceSection(
        title = {
            Text(
                text = "Audio",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsSwitch(
                title = { Text("Auto-resume on startup") },
                subtitle = {
                    Text("Restore the last queue and continue playback when the app opens.")
                },
                state = autoResume,
                onCheckedChange = { scope.launch { prefs.setAutoResume(it) } },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 10),
            )
            SettingsMenuLink(
                title = { Text(text = "Sample Rate") },
                subtitle = {
                    Text(
                        "Audio output rate (Hz). Higher = better quality, more CPU. Applies on next track."
                    )
                },
                action = { Text(text = "$sampleRate Hz") },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 10),
                onClick = { isSampleRateShowing = true }
            )
            SettingsSlider(
                title = { Text(text = "Buffer Milliseconds") },
                subtitle = {
                    Text(
                        text = "Audio buffer size. Smaller = lower latency but risk of glitches. Applies on next track."
                    )
                },
                action = { Text(text = "$bufferMs ms") },
                colors = colors,
                steps = ((Xmp.MAX_BUFFER_MS - Xmp.MIN_BUFFER_MS) / 40) - 1, // = 22
                valueRange = Xmp.MIN_BUFFER_MS.toFloat()..Xmp.MAX_BUFFER_MS.toFloat(),
                value = bufferMs.toFloat(),
                onValueChange = { value ->
                    scope.launch { prefs.setBufferMs(value.toInt()) }
                },
                shapes = ListItemDefaults.segmentedShapes(2, 10),
            )
            SettingsSlider(
                title = { Text("Player Volume") },
                subtitle = { Text("Master output volume (%).") },
                action = { Text("$volume%") },
                colors = colors,
                steps = 0,
                valueRange = 0f..100f,
                value = volume.toFloat(),
                onValueChange = { value ->
                    scope.launch { prefs.setPlayerVolume(value.toInt()) }
                },
                shapes = ListItemDefaults.segmentedShapes(3, 10),
            )
            SettingsSlider(
                title = { Text("Amplification") },
                subtitle = {
                    Text(
                        "Pre-mix gain factor (0=quiet, 1=normal, 2–3=boost). May clip at high values."
                    )
                },
                action = { Text("${boost}x") },
                colors = colors,
                steps = 2,
                valueRange = 0f..3f,
                value = boost.toFloat(),
                onValueChange = { value ->
                    scope.launch { prefs.setVolumeBoost(value.toInt()) }
                },
                shapes = ListItemDefaults.segmentedShapes(4, 10),
            )
            SettingsSlider(
                title = { Text(text = "Stereo Mixing") },
                subtitle = {
                    Text(
                        text = "Stereo separation (%). 0=mono, 100=full stereo, negative=channel swap."
                    )
                },
                action = { Text(text = "$mix%") },
                colors = colors,
                steps = 0,
                valueRange = -100f..100f,
                value = mix.toFloat(),
                onValueChange = { value ->
                    scope.launch { prefs.setStereoMix(value.toInt()) }
                },
                shapes = ListItemDefaults.segmentedShapes(5, 10),
            )
            SettingsSlider(
                title = { Text(text = "Pan Separation") },
                subtitle = {
                    Text(
                        text = "Left/right pan width for stereo formats (%). 0=mono, 100=hard pan. Applies on next track."
                    )
                },
                action = { Text(text = "$pan%") },
                colors = colors,
                steps = 4,
                valueRange = 0f..100f,
                value = pan.toFloat(),
                onValueChange = { value ->
                    scope.launch { prefs.setDefaultPan(value.toInt()) }
                },
                shapes = ListItemDefaults.segmentedShapes(6, 10),
            )
            SettingsMenuLink(
                title = { Text("Interpolation") },
                subtitle = {
                    Text(
                        "Sample interpolation. Nearest=sharp/8-bit feel, Linear=balanced, Spline=smoothest."
                    )
                },
                action = {
                    Text(
                        when (interp) {
                            Xmp.XMP_INTERP_NEAREST -> "Nearest"
                            Xmp.XMP_INTERP_LINEAR -> "Linear"
                            Xmp.XMP_INTERP_SPLINE -> "Spline"
                            else -> "Unknown"
                        }
                    )
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(7, 10),
                onClick = { isInterpShowing = true }
            )
            SettingsSwitch(
                title = { Text("Lowpass Filter") },
                subtitle = { Text("Lowpass DSP smoothing high frequencies.") },
                state = dspEffect != Xmp.XMP_DSP_NONE,
                onCheckedChange = { enabled ->
                    scope.launch {
                        val value = if (enabled) Xmp.XMP_DSP_LOWPASS else Xmp.XMP_DSP_NONE
                        prefs.setDspEffect(value)
                    }
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(8, 10),
            )
            SettingsMenuLink(
                title = { Text("Player Flags") },
                subtitle = {
                    Text(
                        "VBlank timing, FX9 bug, FixLoop, Amiga 500 mixer. All except A500 apply on next track."
                    )
                },
                action = {
                    val count = flagItems.count { (flags and it.flag) != 0 }
                    Text(text = if (count == 0) "None" else "$count enabled")
                },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(9, 10),
                onClick = { isFlagsShowing = true }
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
                PreferencePlayer(colors)
            }
        }
    }
}
