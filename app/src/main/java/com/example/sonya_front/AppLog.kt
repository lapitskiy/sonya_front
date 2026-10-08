package com.example.sonya_front

import android.content.Context
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Кольцевой лог приложения (последние [MAX_ENTRIES] строк).
 *
 * - раз в [SAVE_INTERVAL_MS] сбрасывается на диск (`files/logs/app_ring.log`);
 * - раз в [UPLOAD_INTERVAL_MS] новые строки (которых ещё нет на сервере) уходят на бэк:
 *   `POST {base}/debug/app-log`. Читать: `GET {base}/debug/app-log?tail=300`.
 *
 * ВАЖНО: загрузка использует собственный OkHttpClient БЕЗ логирующего интерцептора и пишет
 * только в android.util.Log — иначе отправка логов порождала бы новые логи (петля).
 */
object AppLog {
    private const val MAX_ENTRIES = 1000
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val UPLOAD_INTERVAL_MS = 15_000L
    private const val UPLOAD_BACKOFF_STEP_MS = 15_000L
    private const val UPLOAD_BACKOFF_MAX_MS = 120_000L
    private const val UPLOAD_MAX_LINE_CHARS = 2_000
    private const val UPLOAD_PATH = "debug/app-log"
    private const val INTERNAL_TAG = "APP_LOG"

    private data class Entry(val seq: Long, val line: String)

    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ring = ArrayDeque<Entry>(MAX_ENTRIES)

    // Нумерация строк внутри процесса; session отличает перезапуски приложения (seq сбрасывается).
    private val sessionId: String = UUID.randomUUID().toString()
    private var nextSeq: Long = 1L
    private var uploadedSeq: Long = 0L

    private val uploadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

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
        scope.launch {
            var backoffMs = 0L
            while (isActive) {
                delay(UPLOAD_INTERVAL_MS + backoffMs)
                backoffMs = if (uploadPending()) {
                    0L
                } else {
                    (backoffMs + UPLOAD_BACKOFF_STEP_MS).coerceAtMost(UPLOAD_BACKOFF_MAX_MS)
                }
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
            ring.addLast(Entry(nextSeq++, line))
            dirty = true
        }
    }

    private fun flushToDisk() {
        val ctx = appContext ?: return
        val linesToWrite: String = synchronized(ring) {
            if (!dirty) return
            ring.joinToString(separator = "\n", postfix = "\n") { it.line }
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

    /**
     * Отправляет на бэк строки, которых там ещё нет. Возвращает true, если слать нечего
     * или отправка прошла; false — ошибка (повтор позже с backoff, курсор не двигается).
     */
    private fun uploadPending(): Boolean {
        val ctx = appContext ?: return true
        val batch: List<Entry> = synchronized(ring) { ring.filter { it.seq > uploadedSeq } }
        if (batch.isEmpty()) return true

        return try {
            val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            val lines = JSONArray()
            for (e in batch) {
                lines.put(
                    JSONObject()
                        .put("seq", e.seq)
                        .put("line", e.line.take(UPLOAD_MAX_LINE_CHARS))
                )
            }
            val json = JSONObject()
                .put("device_id", "android-$androidId")
                .put("session", sessionId)
                .put("lines", lines)
                .toString()

            val request = Request.Builder()
                .url(ApiClient.baseUrl + UPLOAD_PATH)
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            uploadClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w(INTERNAL_TAG, "log upload failed: HTTP ${resp.code}")
                    return false
                }
            }
            uploadedSeq = batch.last().seq
            true
        } catch (t: Throwable) {
            android.util.Log.w(INTERNAL_TAG, "log upload error: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }
}
