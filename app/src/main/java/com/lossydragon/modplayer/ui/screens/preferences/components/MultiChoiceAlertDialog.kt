package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

internal data class FlagItem(
    val flag: Int,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int
)

@Composable
internal fun MultiChoiceAlertDialog(
    currentFlags: Int,
    items: ImmutableList<FlagItem>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var working by remember(currentFlags) { mutableIntStateOf(currentFlags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.dialog_title_multi_choice)) },
        text = {
            Column {
                items.forEach { item ->
                    val checked = (working and item.flag) != 0
                    LabelCheckbox(
                        title = stringResource(item.title),
                        description = stringResource(item.description),
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            working = if (isChecked) {
                                working or item.flag
                            } else {
                                working and item.flag.inv()
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working) }) {
                Text(text = stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun LabelCheckbox(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MultiChoiceAlertDialog(
                currentFlags = 10,
                items = persistentListOf(
                    FlagItem(flag = 1, title = R.string.app_name, description = R.string.app_name),
                    FlagItem(flag = 2, title = R.string.app_name, description = R.string.app_name),
                    FlagItem(flag = 4, title = R.string.app_name, description = R.string.app_name),
                    FlagItem(flag = 8, title = R.string.app_name, description = R.string.app_name),
                ),
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
