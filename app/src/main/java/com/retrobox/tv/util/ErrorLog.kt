package com.retrobox.tv.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small persistent error log used by the Diagnostics screen. Keeps the last [MAX_ENTRIES] lines in
 * memory and mirrors them to `errors.log` (truncated at [MAX_FILE_BYTES]) so a crash is still
 * visible after the process restarts.
 */
object ErrorLog {
    private const val MAX_ENTRIES = 100
    private const val MAX_FILE_BYTES = 64 * 1024L
    private const val TAG = "RetroBox"

    private val entries = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "errors.log")
        file = f
        if (f.exists() && f.length() > MAX_FILE_BYTES) f.delete()
        if (f.exists()) f.readLines().takeLast(MAX_ENTRIES).forEach { entries.addLast(it) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e("CRASH", "Uncaught exception on ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(TAG, "[$tag] $message", t)
        append("E", tag, message + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun w(tag: String, message: String) {
        Log.w(TAG, "[$tag] $message")
        append("W", tag, message)
    }

    fun i(tag: String, message: String) = Log.i(TAG, "[$tag] $message")

    @Synchronized
    private fun append(level: String, tag: String, message: String) {
        val line = "${fmt.format(Date())} $level/$tag: ${message.replace('\n', ' ')}"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun recent(): List<String> = entries.toList().asReversed()

    @Synchronized
    fun clear() {
        entries.clear()
        runCatching { file?.delete() }
    }
}
