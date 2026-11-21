package com.lossydragon.modplayer.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.native.model.FrameInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Collects the 50 Hz frame flow itself so only this row recomposes per frame. */
@Composable
internal fun PatternInfoRow(
    frameInfo: StateFlow<FrameInfo>,
    modifier: Modifier = Modifier
) {
    val fi by frameInfo.collectAsStateWithLifecycle()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(all = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            val numRows = fi.numRows.toString()
            val row = "${fi.row.toString().padStart(numRows.length)}/$numRows"
            InfoChip(label = stringResource(R.string.info_pos), value = "${fi.pos}")
            InfoChip(label = stringResource(R.string.info_pat), value = "${fi.pattern}")
            InfoChip(label = stringResource(R.string.info_row), value = row)
            InfoChip(label = stringResource(R.string.info_spd), value = "${fi.speed}")
            InfoChip(label = stringResource(R.string.info_bpm), value = "${fi.bpm}")
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
                frameInfo = MutableStateFlow(
                    FrameInfo(
                        pos = 3,
                        pattern = 4,
                        row = 6,
                        numRows = 64,
                        speed = 12,
                        bpm = 128,
                    )
                )
            )
        }
    }
}
