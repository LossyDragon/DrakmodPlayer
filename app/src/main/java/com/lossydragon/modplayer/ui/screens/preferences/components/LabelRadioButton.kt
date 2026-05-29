package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

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
        content = { Text(text = stringResource(item.title)) },
        supportingContent = { Text(text = stringResource(item.description)) },
        trailingContent = { RadioButton(selected = isSelected, onClick = null) },
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelRadioButton(
                item = PreferenceItem("Beans", R.string.delete, R.string.delete),
                isSelected = true,
                onClick = {},
            )
            LabelRadioButton(
                item = PreferenceItem("Beans", R.string.delete, R.string.delete),
                isSelected = false,
                onClick = {},
            )
        }
    }
}
