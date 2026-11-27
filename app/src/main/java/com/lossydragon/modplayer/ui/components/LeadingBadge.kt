package com.lossydragon.modplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lossydragon.modplayer.ui.theme.AppTheme

/** Tonal circle used as the leading content of segmented list rows. */
@Composable
fun LeadingBadge(icon: ImageVector) {
    LeadingBadge {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun LeadingBadge(text: String) {
    LeadingBadge {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun LeadingBadge(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        LeadingBadge(icon = Icons.Default.AudioFile)
    }
}

@Preview
@Composable
private fun Preview_Text() {
    AppTheme {
        LeadingBadge(text = "S3M")
    }
}
