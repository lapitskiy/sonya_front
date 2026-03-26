package com.example.sonya_front

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class OfflineCommandCacheItem(
    val id: Long,
    val deviceId: String,
    val text: String,
    val lat: Double?,
    val lon: Double?,
    val deviceTime: String,
    val source: String,
    val createdAtEpochMs: Long,
    val attemptCount: Int,
    val lastTryAtEpochMs: Long?,
    val lastError: String?,
)

object OfflineCommandCacheStore {
    private const val PREFS = "offline_command_cache_store"
    private const val KEY_ITEMS = "items_json"
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enqueue(ctx: Context, request: CommandRequest, source: String): OfflineCommandCacheItem {
        val appCtx = ctx.applicationContext
        val now = System.currentTimeMillis()
        val item = OfflineCommandCacheItem(
            id = now,
            deviceId = request.deviceId,
            text = request.text,
            lat = request.lat,
            lon = request.lon,
            deviceTime = request.deviceTime,
            source = source.trim().ifBlank { "unknown" },
            createdAtEpochMs = now,
            attemptCount = 0,
            lastTryAtEpochMs = null,
            lastError = "нет интернета",
        )
        synchronized(lock) {
            val items = readAllInternal(appCtx).toMutableList()
            items.add(item)
            writeAllInternal(appCtx, items)
        }
        return item
    }

    fun list(ctx: Context, deviceId: String): List<OfflineCommandCacheItem> {
        if (deviceId.isBlank()) return emptyList()
        val appCtx = ctx.applicationContext
        synchronized(lock) {
            return readAllInternal(appCtx)
                .filter { it.deviceId == deviceId }
                .sortedByDescending { it.createdAtEpochMs }
        }
    }

    fun listOldestFirst(ctx: Context, deviceId: String, limit: Int = 50): List<OfflineCommandCacheItem> {
        if (deviceId.isBlank() || limit <= 0) return emptyList()
        val appCtx = ctx.applicationContext
        synchronized(lock) {
            return readAllInternal(appCtx)
                .asSequence()
                .filter { it.deviceId == deviceId }
                .sortedBy { it.createdAtEpochMs }
                .take(limit)
                .toList()
        }
    }

    fun markSent(ctx: Context, id: Long) {
        val appCtx = ctx.applicationContext
        synchronized(lock) {
            val items = readAllInternal(appCtx).filterNot { it.id == id }
            writeAllInternal(appCtx, items)
        }
    }

    fun markAttemptFailed(ctx: Context, id: Long, error: String?) {
        val appCtx = ctx.applicationContext
        val err = error?.trim()?.take(240) ?: "ошибка отправки"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val items = readAllInternal(appCtx).map { item ->
                if (item.id != id) item
                else item.copy(
                    attemptCount = item.attemptCount + 1,
                    lastTryAtEpochMs = now,
                    lastError = err,
                )
            }
            writeAllInternal(appCtx, items)
        }
    }

    private fun readAllInternal(ctx: Context): List<OfflineCommandCacheItem> {
        val raw = prefs(ctx).getString(KEY_ITEMS, null).orEmpty().trim()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<OfflineCommandCacheItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                val deviceId = obj.optString("deviceId", "")
                val text = obj.optString("text", "")
                val deviceTime = obj.optString("deviceTime", "")
                val source = obj.optString("source", "unknown")
                val createdAt = obj.optLong("createdAtEpochMs", 0L)
                val attempts = obj.optInt("attemptCount", 0)
                val lastTryRaw = if (obj.has("lastTryAtEpochMs")) obj.optLong("lastTryAtEpochMs", 0L) else 0L
                val lastTry = if (lastTryRaw > 0L) lastTryRaw else null
                val lastError = obj.optString("lastError", "").ifBlank { null }
                if (id <= 0L || deviceId.isBlank() || text.isBlank()) continue
                val lat = if (obj.has("lat") && !obj.isNull("lat")) obj.optDouble("lat") else null
                val lon = if (obj.has("lon") && !obj.isNull("lon")) obj.optDouble("lon") else null
                out.add(
                    OfflineCommandCacheItem(
                        id = id,
                        deviceId = deviceId,
                        text = text,
                        lat = lat,
                        lon = lon,
                        deviceTime = deviceTime,
                        source = source,
                        createdAtEpochMs = createdAt.takeIf { it > 0L } ?: id,
                        attemptCount = attempts.coerceAtLeast(0),
                        lastTryAtEpochMs = lastTry,
                        lastError = lastError,
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeAllInternal(ctx: Context, items: List<OfflineCommandCacheItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject()
                .put("id", item.id)
                .put("deviceId", item.deviceId)
                .put("text", item.text)
                .put("deviceTime", item.deviceTime)
                .put("source", item.source)
                .put("createdAtEpochMs", item.createdAtEpochMs)
                .put("attemptCount", item.attemptCount)
                .put("lastError", item.lastError)
            if (item.lat != null) obj.put("lat", item.lat) else obj.put("lat", JSONObject.NULL)
            if (item.lon != null) obj.put("lon", item.lon) else obj.put("lon", JSONObject.NULL)
            if (item.lastTryAtEpochMs != null) obj.put("lastTryAtEpochMs", item.lastTryAtEpochMs) else obj.put("lastTryAtEpochMs", JSONObject.NULL)
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
