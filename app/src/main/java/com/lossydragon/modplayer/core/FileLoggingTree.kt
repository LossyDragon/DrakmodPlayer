package com.lossydragon.modplayer.core

import android.content.Context
import android.util.Log
import com.lossydragon.modplayer.util.writeToDownloads
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

class FileLoggingTree(private val context: Context) : Timber.Tree() {

    @Volatile
    var enabled: Boolean = false

    private val logFile: File
        get() = File(context.filesDir, LOG_FILE_NAME)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!enabled) return
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }

        val entry = buildString {
            append("${timestamp()} $level/${tag ?: "?"}: $message")
            if (t != null) append("\n${Log.getStackTraceString(t)}")
            appendLine()
        }

        synchronized(this) {
            try {
                if (logFile.length() > MAX_FILE_SIZE) logFile.delete()
                logFile.appendText(entry)
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    fun exportToDownloads(): Boolean {
        return try {
            val content = buildString {
                appendLine("---------- Timber Logs ----------")
                if (logFile.exists()) append(logFile.readText()) else appendLine("(no file log)")
                appendLine()
                appendLine("---------- Logcat ----------")
                appendLine(recentLogcat)
            }
            val fileName = "drakmod_log_${CrashHandler.timestamp}.txt"
            context.writeToDownloads(fileName, content)
            true
        } catch (e: Exception) {
            Timber.e(e)
            false
        }
    }

    companion object {
        private const val LOG_FILE_NAME = "drakmod_debug.log"
        private const val MAX_FILE_SIZE = 4 * 1024 * 1024L // 4MB
        private const val LOGCAT_LINE_COUNT = 500

        private val recentLogcat: String
            get() = try {
                val cmd = "logcat -d -t $LOGCAT_LINE_COUNT --pid=${android.os.Process.myPid()}"
                Runtime.getRuntime().exec(cmd).inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "Failed to retrieve logcat: ${e.message}"
            }

        private fun timestamp(): String =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }
}
