package com.lossydragon.modplayer.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Configures the activity to draw behind the system bars with transparent backgrounds.
 * Enables edge-to-edge rendering with transparent status and navigation bars, and on Android Q+
 * disables the system-enforced contrast scrim that would otherwise be applied to the 3-button
 * navigation bar.
 */
fun ComponentActivity.setEdgeToEdgeConfig() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Force the 3-button navigation bar to be transparent
        // See: https://developer.android.com/develop/ui/views/layout/edge-to-edge#create-transparent
        window.isNavigationBarContrastEnforced = false
    }
}

/**
 * Launches the system share sheet with [message] as plain text.
 * @param message the text payload to share.
 */
fun Context.shareLink(message: String) {
    val sendIntent: Intent = Intent().apply {
        action = Intent.ACTION_SEND
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    val shareIntent = Intent.createChooser(sendIntent, null)
    this.startActivity(shareIntent)
}
