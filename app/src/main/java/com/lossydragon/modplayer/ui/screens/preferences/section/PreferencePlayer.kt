package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
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

    val sampleRate by prefs.getSampleRateFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.DEFAULT_SAMPLE_RATE)
    val bufferMs by prefs.getBufferMsFlow()
        .collectAsStateWithLifecycle(initialValue = Xmp.DEFAULT_BUFFER_MS)
    val flags by prefs.getPlayerFlagsFlow()
        .collectAsStateWithLifecycle(initialValue = 0)

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

    PreferenceSection(
        title = {
            Text(
                text = "Audio",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = "Sample Rate") },
                subtitle = { Text(text = sampleRate.toString()) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 3),
                onClick = { isSampleRateShowing = true }
            )
            SettingsMenuLink(
                title = { Text("Player Flags") },
                subtitle = { Text(flagsSubtitle(flags, flagItems)) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 3),
                onClick = { isFlagsShowing = true }
            )
            SettingsSlider(
                title = { Text(text = "Buffer Milliseconds") },
                subtitle = { Text(text = bufferMs.toString()) },
                colors = colors,
                steps = ((Xmp.MAX_BUFFER_MS - Xmp.MIN_BUFFER_MS) / 40) - 1, // = 22
                valueRange = Xmp.MIN_BUFFER_MS.toFloat()..Xmp.MAX_BUFFER_MS.toFloat(),
                value = bufferMs.toFloat(),
                onValueChange = { value ->
                    scope.launch { prefs.setBufferMs(value.toInt()) }
                },
                shapes = ListItemDefaults.segmentedShapes(1, 2),
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
            PreferencePlayer(colors)
        }
    }
}
