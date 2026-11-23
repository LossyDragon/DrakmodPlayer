package com.lossydragon.modplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.components.collectPref
import com.lossydragon.modplayer.ui.components.rememberAppPreferences
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

val seed = Color(AppPreferences.DEFAULT_THEME_COLOR)

/** Resolves a persisted PaletteStyle name, falling back to the default for unknown names. */
fun paletteStyleOf(name: String): PaletteStyle =
    PaletteStyle.entries.firstOrNull { it.name == name } ?: PaletteStyle.Vibrant

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val prefs = rememberAppPreferences()

    val styleName by prefs.collectPref(AppPreferences.DEFAULT_THEME_STYLE) {
        getAppThemeStyleFlow()
    }
    val style = remember(styleName) { paletteStyleOf(styleName) }

    val amoled by prefs.collectPref(false) { getAppThemeAmoledFlow() }

    val prefColor by prefs.collectPref(AppPreferences.DEFAULT_THEME_COLOR) { getThemeColorFlow() }
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
