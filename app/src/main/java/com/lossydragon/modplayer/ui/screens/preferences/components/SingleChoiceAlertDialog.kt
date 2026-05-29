package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.*
import com.lossydragon.modplayer.R
import kotlinx.collections.immutable.ImmutableList

internal data class PreferenceItem(
    val key: String,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int
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
        title = { Text(text = stringResource(R.string.dialog_title_single_choice)) },
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
                    content = { Text(text = stringResource(R.string.cancel)) }
                )
            }
        } else {
            {
                TextButton(
                    onClick = { onItemSelected(userSelectedItem.value) },
                    content = { Text(text = stringResource(R.string.select)) }
                )
            }
        },
        dismissButton = if (userSelectedItem.value == null) {
            null
        } else {
            {
                TextButton(
                    onClick = { onItemSelected(null) },
                    content = { Text(text = stringResource(R.string.clear)) }
                )
            }
        },
    )
}
