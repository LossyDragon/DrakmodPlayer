package com.lossydragon.modplayer.util

import android.text.Html
import java.time.Instant

/** Formats milliseconds as `m:ss`. */
fun Long.formatMs(): String {
    val s = this / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Formats bytes as a human-readable size string (B, KB, MB). */
fun Long.formatSize(): String = when {
    this < 1_024L -> "$this B"
    this < 1_048_576L -> "${"%.1f".format(this / 1_024.0)} KB"
    else -> "${"%.1f".format(this / 1_048_576.0)} MB"
}

/** Returns displayable styled text from the provided HTML string. */
fun String.fromHtml(): String =
    Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString().trim()

/** Formats a Unix epoch timestamp in milliseconds to a human-readable date string e.g. "May 23, 2026". */
fun Long.toReadableDate(): String {
    val formatter = java.time.format.DateTimeFormatter
        .ofPattern("MMM d, yyyy")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(this))
}
