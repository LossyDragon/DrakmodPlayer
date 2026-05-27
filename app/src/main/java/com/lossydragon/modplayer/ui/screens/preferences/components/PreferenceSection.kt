package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.expressive.SettingsGroup
import com.lossydragon.modplayer.ui.theme.AppTheme

@Composable
internal fun PreferenceSection(
    title: @Composable () -> Unit,
    enabled: Boolean = true,
    paddingValues: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsGroup(
        contentPadding = paddingValues,
        verticalArrangement = verticalArrangement,
        enabled = enabled,
        title = title,
        content = content
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PreferenceSection(
                    title = { Text("Beans") },
                    enabled = true,
                    content = {}
                )
                PreferenceSection(
                    title = { Text("Beans") },
                    enabled = false,
                    content = {}
                )
            }
        }
    }
}
