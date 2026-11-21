package com.lossydragon.modplayer.core

import android.content.Context
import android.util.Log
import com.lossydragon.modplayer.util.writeToDownloads
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import timber.log.Timber

/** Appends log lines to a private file from a single writer thread; rotates `.log` -> `.log.1`. */
class FileLoggingTree(private val context: Context) : Timber.Tree() {

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) executor.execute { closeWriter() }
        }

    private val logFile: File get() = File(context.filesDir, LOG_FILE_NAME)
    private val rotatedFile: File get() = File(context.filesDir, "$LOG_FILE_NAME.1")

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "FileLoggingTree").apply { isDaemon = true }
    }

    // Only touched from the executor thread.
    private var writer: BufferedWriter? = null
    private var written = 0L

    init {
        executor.scheduleWithFixedDelay(
            ::flush,
            FLUSH_INTERVAL_S,
            FLUSH_INTERVAL_S,
            TimeUnit.SECONDS
        )
    }

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
        executor.execute { append(entry) }
    }

    private fun append(entry: String) {
        try {
            if (written > MAX_FILE_SIZE) rotate()
            val w = writer ?: open()
            w.write(entry)
            written += entry.length
        } catch (_: Exception) {
            closeWriter()
        }
    }

    private fun open(): BufferedWriter {
        written = logFile.length()
        return BufferedWriter(FileWriter(logFile, true)).also { writer = it }
    }

    private fun rotate() {
        closeWriter()
        rotatedFile.delete()
        logFile.renameTo(rotatedFile)
        written = 0L
    }

    private fun flush() {
        try {
            writer?.flush()
        } catch (_: Exception) {
            closeWriter()
        }
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (_: Exception) {
            // Ignore
        }
        writer = null
    }

    fun exportToDownloads(): Boolean {
        return try {
            executor.submit(::flush).get(2, TimeUnit.SECONDS)
            val content = buildString {
                appendLine("---------- Timber Logs ----------")
                if (rotatedFile.exists()) append(rotatedFile.readText())
                if (logFile.exists()) append(logFile.readText()) else appendLine("(no file log)")
                appendLine()
                appendLine("---------- Logcat ----------")
                appendLine(CrashHandler.recentLogcat())
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
        private const val FLUSH_INTERVAL_S = 2L

        private fun timestamp(): String =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }
}
