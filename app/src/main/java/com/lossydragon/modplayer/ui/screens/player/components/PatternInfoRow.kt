package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.ui.theme.AppTheme
import org.helllabs.libxmp.model.FrameInfo

@Composable
internal fun PatternInfoRow(
    fi: FrameInfo,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(all = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            val numRows = fi.numRows.toString()
            val row = "${fi.row.toString().padStart(numRows.length)}/$numRows"
            InfoChip(label = "Pos", value = "${fi.pos}")
            InfoChip(label = "Pat", value = "${fi.pattern}")
            InfoChip(label = "Row", value = row) // Pad fix
            InfoChip(label = "Spd", value = "${fi.speed}")
            InfoChip(label = "BPM", value = "${fi.bpm}")
        }
    )
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            PatternInfoRow(
                fi = FrameInfo(
                    pos = 3,
                    pattern = 4,
                    row = 6,
                    numRows = 64,
                    speed = 12,
                    bpm = 128,
                )
            )
        }
    }
}
