package com.lossydragon.modplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.tooling.preview.datasource.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

@Composable
fun MessageBox(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val actionRow: (@Composable RowScope.() -> Unit)? = actions ?: onBack?.let { back ->
        {
            TextButton(
                onClick = back,
                content = { Text(text = stringResource(R.string.desc_back_button)) }
            )
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        content = {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = stringResource(R.string.desc_message_box_icon),
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .size(24.dp),
                        )
                    }

                    title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    actionRow?.let { item ->
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            content = { item() },
                        )
                    }
                }
            }
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = {
                MessageBox(
                    text = LoremIpsum(50).values.first(),
                    actions = {
                        TextButton(onClick = {}) { Text(text = stringResource(R.string.cancel)) }
                        TextButton(onClick = {}) { Text(text = stringResource(R.string.confirm)) }
                    }
                )
            }
        )
    }
}
