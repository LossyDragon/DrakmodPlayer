package com.lossydragon.modplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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

val seed = Color(AppPreferences.DEFAULT_THEME_COLOR)

/** Resolves a persisted PaletteStyle name, falling back to the default for unknown names. */
fun paletteStyleOf(name: String): PaletteStyle =
    PaletteStyle.entries.firstOrNull { it.name == name } ?: PaletteStyle.Vibrant

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val styleName by prefs.getAppThemeStyleFlow()
        .collectAsStateWithLifecycle(AppPreferences.DEFAULT_THEME_STYLE)
    val style = remember(styleName) { paletteStyleOf(styleName) }

    val amoled by prefs.getAppThemeAmoledFlow().collectAsStateWithLifecycle(false)

    val prefColor by prefs.getThemeColorFlow()
        .collectAsStateWithLifecycle(AppPreferences.DEFAULT_THEME_COLOR)
    val color = remember(prefColor) { Color(prefColor) }

    // AMOLED is the only pref that forces dark; otherwise follow the system.
    val colorScheme = rememberDynamicColorScheme(
        seedColor = color,
        isDark = darkTheme || amoled,
        isAmoled = amoled,
        style = style,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
        shapes = MaterialTheme.shapes.copy(
            extraSmall = ShapeDefaults.ExtraSmall,
            small = ShapeDefaults.Small,
            medium = ShapeDefaults.Small,
            large = ShapeDefaults.Medium,
            extraLarge = ShapeDefaults.Medium,
        )
    )
}
