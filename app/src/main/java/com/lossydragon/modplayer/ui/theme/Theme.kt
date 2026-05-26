package com.lossydragon.modplayer.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.db.AppPreferences
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import org.koin.compose.koinInject

val seed = Color(0xFF660099)

@Composable
fun AppTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val styleValue by prefs.getAppThemeStyleFlow()
        .collectAsStateWithLifecycle(PaletteStyle.Vibrant.ordinal)
    val style = remember(styleValue) {
        PaletteStyle.entries.getOrElse(styleValue) { PaletteStyle.Vibrant }
    }

    val amoledValue by prefs.getAppThemeAmoledFlow().collectAsStateWithLifecycle(false)
    val amoled by remember(amoledValue) {
        mutableStateOf(amoledValue)
    }

    val prefColor by prefs.getThemeColorFlow().collectAsStateWithLifecycle(seed.toArgb())
    val color = remember(prefColor) { Color(prefColor) }

    val colorScheme = rememberDynamicColorScheme(
        seedColor = color,
        isDark = darkTheme,
        isAmoled = amoled,
        style = style,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
