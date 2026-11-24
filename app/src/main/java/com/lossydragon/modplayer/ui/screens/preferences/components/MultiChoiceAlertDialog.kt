package com.lossydragon.modplayer.ui.screens.preferences.components

import androidx.annotation.StringRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
