package com.lossydragon.modplayer.ui.screens.downloads.components

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

@Composable
internal fun HeaderText(text: String) {
    Text(
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        text = text
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            HeaderText(text = stringResource(R.string.app_name))
        }
    }
}
