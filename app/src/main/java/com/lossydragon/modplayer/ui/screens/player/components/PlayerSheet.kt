package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R

/** Bottom sheet with a centred title and a close button; closed only through [onDismiss]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheet(
    title: String,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        shape = MaterialTheme.shapes.small,
        content = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
                content = {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    IconButton(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .align(Alignment.CenterEnd),
                        onClick = onDismiss,
                        content = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.close)
                            )
                        }
                    )
                }
            )
            content()
        }
    )
}
