package com.lossydragon.modplayer.ui.screens.downloads.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme

@Composable
internal fun MonoSpaceText(text: String) {
    Text(
        modifier = Modifier.padding(start = 10.dp, end = 10.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        text = text
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            MonoSpaceText(text = stringResource(R.string.app_name))
        }
    }
}
