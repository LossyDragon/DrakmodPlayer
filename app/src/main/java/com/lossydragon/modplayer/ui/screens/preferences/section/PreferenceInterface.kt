package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.alorma.compose.settings.ui.expressive.SettingsSwitch
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.SaturationSlider
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceItem
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.ui.theme.seed
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.toHex
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val styleItems = persistentListOf(
    PreferenceItem(
        key = PaletteStyle.TonalSpot.name,
        title = "Tonal Spot",
        description = "Calm, sedated colors"
    ),
    PreferenceItem(
        key = PaletteStyle.Neutral.name,
        title = "Neutral",
        description = "Slightly more chromatic than monochrome"
    ),
    PreferenceItem(
        key = PaletteStyle.Vibrant.name,
        title = "Vibrant",
        description = "Loud, maximum colorfulness"
    ),
    PreferenceItem(
        key = PaletteStyle.Expressive.name,
        title = "Expressive",
        description = "Playful, hue does not appear in theme"
    ),
    PreferenceItem(
        key = PaletteStyle.Rainbow.name,
        title = "Rainbow",
        description = "Playful, hue does not appear in theme"
    ),
    PreferenceItem(
        key = PaletteStyle.FruitSalad.name,
        title = "Fruit Salad",
        description = "Playful, hue does not appear in theme"
    ),
    PreferenceItem(
        key = PaletteStyle.Monochrome.name,
        title = "Monochrome",
        description = "Purely black, white, and gray"
    ),
    PreferenceItem(
        key = PaletteStyle.Fidelity.name,
        title = "Fidelity",
        description = "Source color placed in primaryContainer"
    ),
    PreferenceItem(
        key = PaletteStyle.Content.name,
        title = "Content",
        description = "Source color with analogous tertiary"
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceInterface(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val style by remember(prefs) {
        prefs.getAppThemeStyleFlow().map {
            PaletteStyle.entries.getOrElse(it) { PaletteStyle.Vibrant }
        }
    }.collectAsStateWithLifecycle(PaletteStyle.Vibrant)

    val amoled by prefs.getAppThemeAmoledFlow().collectAsStateWithLifecycle(false)

    val color by remember(prefs) {
        prefs.getThemeColorFlow().map { Color(it) }
    }.collectAsStateWithLifecycle(seed)

    var isThemeStyleShowing by remember { mutableStateOf(false) }
    if (isThemeStyleShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = style.name,
            items = styleItems,
            onItemSelected = { key ->
                key?.let {
                    scope.launch { prefs.setAppThemeStyle(PaletteStyle.valueOf(it).ordinal) }
                }
                isThemeStyleShowing = false
            },
        )
    }

    val controller = rememberColorPickerController()

    var hexCode by remember { mutableStateOf(color.toHex()) }
    var textColor by remember { mutableStateOf(color) }
    var isThemeColorShowing by remember { mutableStateOf(false) }
    LaunchedEffect(isThemeColorShowing) {
        if (isThemeColorShowing) {
            controller.selectByColor(color, false)
        }
    }
    if (isThemeColorShowing) {
        AlertDialog(
            onDismissRequest = { isThemeColorShowing = false },
            title = { Text(text = "Theme Color") },
            text = {
                Column {
                    HsvColorPicker(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(256.dp)
                            .padding(10.dp),
                        controller = controller,
                        onColorChanged = { colorEnvelope ->
                            hexCode = colorEnvelope.hexCode
                            textColor = colorEnvelope.color
                        },
                        onStart = {},
                        onFinish = {},
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(textColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "#$hexCode",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,

                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    BrightnessSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                            .height(35.dp)
                            .align(Alignment.CenterHorizontally),
                        controller = controller,
                    )

                    SaturationSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                            .height(35.dp)
                            .align(Alignment.CenterHorizontally),
                        controller = controller,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { prefs.setThemeColor(textColor.toArgb()) }
                        isThemeColorShowing = false
                    },
                    content = { Text(text = "Confirm") }
                )
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { isThemeColorShowing = false },
                        content = { Text(text = "Cancel") }
                    )
                    TextButton(
                        onClick = {
                            scope.launch { prefs.setThemeColor(seed.toArgb()) }
                            isThemeColorShowing = false
                        },
                        content = { Text(text = "Reset") }
                    )
                }
            }
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = "Interface",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = "Scheme Style") },
                subtitle = { Text(text = style.name) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 3),
                onClick = { isThemeStyleShowing = true }
            )
            SettingsSwitch(
                title = { Text(text = "AMOLED Mode") },
                subtitle = { Text(text = amoled.toString()) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 3),
                state = amoled,
                onCheckedChange = { scope.launch { prefs.setAppThemeAmoled(it) } }
            )
            SettingsMenuLink(
                title = { Text(text = "Theme Color") },
                subtitle = { Text(text = color.toHex()) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(2, 3),
                onClick = { isThemeColorShowing = true }
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
            PreferenceInterface(colors)
        }
    }
}
