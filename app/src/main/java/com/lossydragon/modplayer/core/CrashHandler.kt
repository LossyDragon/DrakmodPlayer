package com.lossydragon.modplayer.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lossydragon.modplayer.BuildConfig
import com.lossydragon.modplayer.util.writeToDownloads
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * Writes a crash report to private storage on the crashing thread; [exportPendingReports]
 * moves it to Downloads on the next launch, where MediaStore work is safe.
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val LOG_CAT_LINES = 300
        private const val LOG_CAT_MAX_CHARS = 64 * 1024
        private const val CRASH_FILE_HISTORY_COUNT = 2
        private const val PENDING_DIR = "crashes"

        val timestamp: String
            get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

        fun initialize(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, defaultHandler)
            )
        }

        /** Last [LOG_CAT_LINES] lines of this process's logcat, capped at [LOG_CAT_MAX_CHARS]. */
        fun recentLogcat(): String = try {
            val cmd = "logcat -d -t $LOG_CAT_LINES --pid=${android.os.Process.myPid()}"
            val process = Runtime.getRuntime().exec(cmd)
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(8 * 1024)
                    val out = StringBuilder()
                    while (out.length < LOG_CAT_MAX_CHARS) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        out.appendRange(buf, 0, n)
                    }
                    out.toString()
                }
            } finally {
                process.destroy()
            }
        } catch (e: Exception) {
            "Failed to retrieve logcat: ${e.message}"
        }

        /** Copies reports saved by a previous run to Downloads and trims old ones. Call off-main. */
        fun exportPendingReports(context: Context) {
            val dir = File(context.filesDir, PENDING_DIR)
            val files = dir.listFiles() ?: return
            for (file in files) {
                try {
                    context.writeToDownloads(file.name, file.readText())
                    file.delete()
                } catch (e: Exception) {
                    Timber.w(e, "Could not export crash report ${file.name}")
                }
            }
            if (files.isNotEmpty()) runCatching { cleanupOldCrashFiles(context) }
        }

        private fun cleanupOldCrashFiles(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                val selectionArgs =
                    arrayOf(
                        "%${Constants.DOWNLOADS_FOLDER}%",
                        "${Constants.CRASH_FILE_PREFIX}%.txt"
                    )
                val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"

                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    var count = 0
                    while (cursor.moveToNext()) {
                        count++
                        if (count > CRASH_FILE_HISTORY_COUNT) {
                            val id = cursor.getLong(idCol)
                            val deleteUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                                .buildUpon().appendPath(id.toString()).build()
                            resolver.delete(deleteUri, null, null)
                        }
                    }
                }
            } else {
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Constants.DOWNLOADS_FOLDER
                )
                downloadsDir.listFiles { file -> file.name.startsWith(Constants.CRASH_FILE_PREFIX) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(CRASH_FILE_HISTORY_COUNT)
                    ?.forEach { it.delete() }
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashToFile(throwable)
        } catch (_: Exception) {
            // Nothing sensible left to do; let the default handler finish.
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashToFile(throwable: Throwable) {
        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()

        val time = timestamp
        val crashReport = buildString {
            appendLine("Timestamp: $time")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("---------- Device Information ----------")
            appendLine("${Build.MANUFACTURER} - ${Build.BRAND} - ${Build.MODEL}")
            appendLine("Android Version: ${Build.VERSION.RELEASE}")
            appendLine()
            appendLine("---------- Cause ----------")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("---------- Stack Trace ----------")
            appendLine(stackTrace)
            appendLine()
            appendLine("---------- Logcat ----------")
            appendLine(recentLogcat())
        }

        val dir = File(context.filesDir, PENDING_DIR).apply { mkdirs() }
        File(dir, "${Constants.CRASH_FILE_PREFIX}$time.txt").writeText(crashReport)
    }
}
