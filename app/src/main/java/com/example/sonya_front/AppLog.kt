package com.example.sonya_front

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AppLog {
    private const val MAX_ENTRIES = 100
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val INTERNAL_TAG = "APP_LOG"
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ring = ArrayDeque<String>(MAX_ENTRIES)

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dirty = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                flushToDisk()
            }
        }
    }

    fun d(tag: String, message: String): Int = log("D", tag, message, null) {
        android.util.Log.d(tag, message)
    }

    fun i(tag: String, message: String): Int = log("I", tag, message, null) {
        android.util.Log.i(tag, message)
    }

    fun w(tag: String, message: String): Int = log("W", tag, message, null) {
        android.util.Log.w(tag, message)
    }

    fun w(tag: String, message: String, tr: Throwable): Int = log("W", tag, message, tr) {
        android.util.Log.w(tag, message, tr)
    }

    fun e(tag: String, message: String): Int = log("E", tag, message, null) {
        android.util.Log.e(tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable): Int = log("E", tag, message, tr) {
        android.util.Log.e(tag, message, tr)
    }

    fun v(tag: String, message: String): Int = log("V", tag, message, null) {
        android.util.Log.v(tag, message)
    }

    private inline fun log(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
        logCall: () -> Int,
    ): Int {
        val result = logCall()
        append(level, tag, message, throwable)
        return result
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        val ts = synchronized(timestampFmt) { timestampFmt.format(Date()) }
        val safeMessage = message.replace("\n", "\\n")
        val throwableText = throwable?.let { " | ${android.util.Log.getStackTraceString(it).replace("\n", "\\n")}" } ?: ""
        val line = "$ts $level/$tag: $safeMessage$throwableText"
        synchronized(ring) {
            if (ring.size == MAX_ENTRIES) ring.removeFirst()
            ring.addLast(line)
            dirty = true
        }
    }

    private fun flushToDisk() {
        val ctx = appContext ?: return
        val linesToWrite: String = synchronized(ring) {
            if (!dirty) return
            ring.joinToString(separator = "\n", postfix = "\n")
        }
        try {
            val dir = File(ctx.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "app_ring.log")
            file.writeText(linesToWrite)
            synchronized(ring) { dirty = false }
        } catch (t: Throwable) {
            android.util.Log.w(INTERNAL_TAG, "Failed to persist app ring log: ${t.message}", t)
        }
    }
}
