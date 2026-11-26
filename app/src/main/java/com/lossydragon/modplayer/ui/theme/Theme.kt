package com.lossydragon.modplayer.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.ui.components.collectPref
import com.lossydragon.modplayer.ui.components.rememberAppPreferences
import com.lossydragon.modplayer.util.setEdgeToEdgeConfig
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

val seed = Color(AppPreferences.DEFAULT_THEME_COLOR)

/** Resolves a persisted PaletteStyle name, falling back to the default for unknown names. */
fun paletteStyleOf(name: String): PaletteStyle =
    PaletteStyle.entries.firstOrNull { it.name == name } ?: PaletteStyle.Vibrant

@Composable
fun AppTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val prefs = rememberAppPreferences()

    val mode by prefs.collectPref(AppPreferences.DEFAULT_THEME_MODE) { getAppThemeModeFlow() }

    val styleName by prefs.collectPref(AppPreferences.DEFAULT_THEME_STYLE) {
        getAppThemeStyleFlow()
    }
    val style = remember(styleName) { paletteStyleOf(styleName) }

    val amoled by prefs.collectPref(false) { getAppThemeAmoledFlow() }

    val prefColor by prefs.collectPref(AppPreferences.DEFAULT_THEME_COLOR) { getThemeColorFlow() }
    val color = remember(prefColor) { Color(prefColor) }

    // AMOLED forces dark whatever the mode.
    val isDark = amoled || darkTheme ?: when (mode) {
        AppPreferences.THEME_MODE_LIGHT -> false
        AppPreferences.THEME_MODE_AUTO -> isSystemInDarkTheme()
        else -> true
    }
    val colorScheme = rememberDynamicColorScheme(
        seedColor = color,
        isDark = isDark,
        isAmoled = amoled,
        style = style,
    )

    // System bar icons follow the app theme, not the system's.
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(activity, isDark) { activity?.setEdgeToEdgeConfig(isDark) }

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
