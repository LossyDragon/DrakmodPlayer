package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.collections.immutable.ImmutableList

internal data class PreferenceItem(
    val key: String,
    val title: String,
    val description: String
)

@Composable
internal fun SingleChoiceAlertDialog(
    selectedItemKey: String?,
    onItemSelected: (String?) -> Unit,
    items: ImmutableList<PreferenceItem>
) {
    val userSelectedItem = remember { mutableStateOf(selectedItemKey) }

    AlertDialog(
        onDismissRequest = { onItemSelected(selectedItemKey) },
        title = { Text(text = "Single choice") },
        text = {
            Column {
                items.forEach { sampleItem ->
                    val isSelected = sampleItem.key == userSelectedItem.value
                    LabelRadioButton(
                        item = sampleItem,
                        isSelected = isSelected,
                        onClick = { userSelectedItem.value = sampleItem.key },
                    )
                }
            }
        },
        confirmButton = if (userSelectedItem.value == null) {
            {
                TextButton(
                    onClick = { onItemSelected(null) },
                    content = { Text(text = "Cancel") }
                )
            }
        } else {
            {
                TextButton(
                    onClick = { onItemSelected(userSelectedItem.value) },
                    content = { Text(text = "Select") }
                )
            }
        },
        dismissButton = if (userSelectedItem.value == null) {
            null
        } else {
            {
                TextButton(
                    onClick = { onItemSelected(null) },
                    content = { Text(text = "Clear") }
                )
            }
        },
    )
}
