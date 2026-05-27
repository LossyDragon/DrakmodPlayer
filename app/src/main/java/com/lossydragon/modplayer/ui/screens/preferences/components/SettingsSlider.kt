package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.alorma.compose.settings.ui.core.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.expressive.SettingsTileDefaults
import com.lossydragon.modplayer.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    sliderColors: SliderColors = SliderDefaults.colors(),
    colors: ListItemColors = SettingsTileDefaults.colors(),
    shapes: ListItemShapes = SettingsTileDefaults.shapes(),
    elevation: ListItemElevation = SettingsTileDefaults.elevation(),
    semanticProperties: SemanticsPropertyReceiver.() -> Unit = {}
) {
    SegmentedListItem(
        onClick = {}, // no row-level click; slider handles its own interaction
        modifier = Modifier
            .fillMaxWidth()
            .semantics(properties = semanticProperties)
            .then(modifier),
        shapes = shapes,
        enabled = enabled,
        content = title,
        leadingContent = icon,
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                subtitle?.invoke()
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChangeFinished = onValueChangeFinished,
                    colors = sliderColors,
                )
            }
        },
        colors = colors,
        elevation = elevation,
        trailingContent = action,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SettingsSlider(
                    value = 0.5f,
                    onValueChange = {},
                    title = { Text("Beans") },
                    subtitle = { Text("Beans") },
                    enabled = true,
                )

                SettingsSlider(
                    value = 0.5f,
                    onValueChange = {},
                    title = { Text("Beans") },
                    subtitle = { Text("Beans") },
                    enabled = false,
                )
            }
        }
    }
}
