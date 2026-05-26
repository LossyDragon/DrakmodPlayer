package com.lossydragon.modplayer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun Context.requestNotificationPermission(onRequest: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        requestPermission(Manifest.permission.POST_NOTIFICATIONS, onRequest)
    }
}

fun Context.requestWriteStoragePermission(onRequest: () -> Unit) {
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, onRequest)
    }
}

private fun Context.requestPermission(permission: String, onRequest: () -> Unit) {
    val hasPerm = ContextCompat.checkSelfPermission(this, permission)
    if (hasPerm != PackageManager.PERMISSION_GRANTED) onRequest()
}
