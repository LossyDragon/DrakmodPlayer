package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.compose.material3.*
import androidx.compose.runtime.*

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LabelRadioButton(
    item: PreferenceItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        onClick = onClick,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        content = { Text(text = item.title) },
        supportingContent = { Text(text = item.description) },
        trailingContent = { RadioButton(selected = isSelected, onClick = null) },
    )
}
