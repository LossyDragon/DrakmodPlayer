package com.lossydragon.modplayer.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink
import com.lossydragon.modplayer.BuildConfig
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.components.collectPref
import com.lossydragon.modplayer.ui.components.rememberAppPreferences
import com.lossydragon.modplayer.ui.screens.preferences.components.PreferenceSection
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.coroutines.launch

@Composable
fun PreferenceDownloads(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = rememberAppPreferences()
    val userKey by prefs.collectPref("") { getUserApiKeyFlow() }

    var isShowingDialog by remember { mutableStateOf(false) }
    if (isShowingDialog) {
        ApiKeyDialog(
            initialKey = userKey,
            onSave = { scope.launch { prefs.setUserApiKey(it) } },
            onDismiss = { isShowingDialog = false },
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_downloads),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_api_key)) },
                subtitle = {
                    Text(
                        text = stringResource(
                            when {
                                userKey.isNotBlank() -> R.string.pref_api_key_custom
                                BuildConfig.API_KEY.isNotBlank() -> R.string.pref_api_key_builtin
                                else -> R.string.not_set
                            }
                        )
                    )
                },
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                colors = colors,
                onClick = { isShowingDialog = true },
            )
        }
    )
}

@Composable
private fun ApiKeyDialog(
    initialKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember { mutableStateOf(initialKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Default.Key, contentDescription = null) },
        title = { Text(text = stringResource(R.string.pref_api_key)) },
        text = {
            Column {
                Text(text = stringResource(R.string.pref_api_key_desc))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.pref_api_key_label)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(key)
                    onDismiss()
                },
                content = { Text(text = stringResource(R.string.save)) }
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                content = { Text(text = stringResource(R.string.cancel)) }
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            val colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            PreferenceDownloads(colors)
        }
    }
}

@Preview
@Composable
private fun Preview_Dialog() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ApiKeyDialog(initialKey = "", onSave = {}, onDismiss = {})
        }
    }
}
