package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
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
import com.lossydragon.modplayer.R
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
        title = R.string.pref_style_item_tonal,
        description = R.string.pref_style_item_tonal_desc
    ),
    PreferenceItem(
        key = PaletteStyle.Neutral.name,
        title = R.string.pref_style_item_neutral,
        description = R.string.pref_style_item_neutral_desc
    ),
    PreferenceItem(
        key = PaletteStyle.Vibrant.name,
        title = R.string.pref_style_item_vibrant,
        description = R.string.pref_style_item_vibrant_desc
    ),
    PreferenceItem(
        key = PaletteStyle.Expressive.name,
        title = R.string.pref_style_item_expressive,
        description = R.string.pref_style_item_expressive_desc
    ),
    PreferenceItem(
        key = PaletteStyle.Rainbow.name,
        title = R.string.pref_style_item_rainbow,
        description = R.string.pref_style_item_rainbow_desc
    ),
    PreferenceItem(
        key = PaletteStyle.FruitSalad.name,
        title = R.string.pref_style_item_fruit,
        description = R.string.pref_style_item_fruit_desc
    ),
    PreferenceItem(
        key = PaletteStyle.Monochrome.name,
        title = R.string.pref_style_item_monocrhome,
        description = R.string.pref_style_item_monocrhome_desc
    ),
    PreferenceItem(
        key = PaletteStyle.Fidelity.name,
        title = R.string.pref_style_item_fidelity,
        description = R.string.pref_style_item_fidelity_desc
    ),
    PreferenceItem(
        key = PaletteStyle.Content.name,
        title = R.string.pref_style_item_content,
        description = R.string.pref_style_item_content_desc
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
            title = { Text(text = stringResource(R.string.dialog_title_theme_color)) },
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
                            text = stringResource(R.string.hex_code, hexCode),
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
                    content = { Text(text = stringResource(R.string.confirm)) }
                )
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { isThemeColorShowing = false },
                        content = { Text(text = stringResource(R.string.cancel)) }
                    )
                    TextButton(
                        onClick = {
                            scope.launch { prefs.setThemeColor(seed.toArgb()) }
                            isThemeColorShowing = false
                        },
                        content = { Text(text = stringResource(R.string.reset)) }
                    )
                }
            }
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_interface),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_scheme_style)) },
                subtitle = { Text(text = style.name) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(0, 3),
                onClick = { isThemeStyleShowing = true }
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_theme_color)) },
                subtitle = { Text(text = color.toHex()) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(1, 3),
                onClick = { isThemeColorShowing = true }
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_amoled)) },
                subtitle = { Text(text = amoled.toString()) },
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(2, 3),
                state = amoled,
                onCheckedChange = { scope.launch { prefs.setAppThemeAmoled(it) } }
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
