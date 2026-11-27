package com.lossydragon.modplayer.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Spacing shared by the segmented lists. */
object SegmentedListSpacing {
    val Horizontal = 16.dp
    val Vertical = 8.dp

    /** Bottom space that lets the last row scroll clear of a FAB or floating toolbar. */
    val FabClearance = 96.dp

    val Padding = PaddingValues(horizontal = Horizontal, vertical = Vertical)

    val PaddingWithFab = PaddingValues(
        start = Horizontal,
        top = Vertical,
        end = Horizontal,
        bottom = FabClearance,
    )
}

/** Colors shared by the segmented list rows. */
@Composable
fun segmentedRowColors() = ListItemDefaults.segmentedColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
)
