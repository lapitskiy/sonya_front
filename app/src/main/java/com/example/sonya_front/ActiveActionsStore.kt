package com.example.sonya_front

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores active scheduled actions (for UI countdown / tray visibility).
 * Minimal implementation using SharedPreferences JSON.
 */
object ActiveActionsStore {
    private const val PREFS = "active_actions_store"
    private const val KEY_JSON = "active_actions_json"

    data class ActiveAction(
        val actionId: Int,
        val type: String,
        val label: String,
        val triggerAtEpochMs: Long,
        val scheduledAtEpochMs: Long,
        val durationMs: Long?, // only for timers
        val state: String = "scheduled", // scheduled | ringing
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun upsert(ctx: Context, item: ActiveAction) {
        val list = getAll(ctx).toMutableList()
        val idx = list.indexOfFirst { it.actionId == item.actionId }
        if (idx >= 0) list[idx] = item else list.add(item)
        save(ctx, list)
    }

    fun remove(ctx: Context, actionId: Int) {
        val list = getAll(ctx).filterNot { it.actionId == actionId }
        save(ctx, list)
    }

    fun getAll(ctx: Context): List<ActiveAction> {
        val raw = prefs(ctx).getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optInt("action_id", -1)
                    val type = o.optString("type", "")
                    val label = o.optString("label", "")
                    val trigger = o.optLong("trigger_at_epoch_ms", 0L)
                    val scheduledAt = o.optLong("scheduled_at_epoch_ms", 0L)
                    val durationMs = if (o.has("duration_ms")) o.optLong("duration_ms", 0L).takeIf { it > 0L } else null
                    val state = o.optString("state", "scheduled").ifBlank { "scheduled" }
                    if (id > 0 && type.isNotBlank() && trigger > 0L) {
                        add(
                            ActiveAction(
                                actionId = id,
                                type = type,
                                label = label,
                                triggerAtEpochMs = trigger,
                                scheduledAtEpochMs = if (scheduledAt > 0L) scheduledAt else (trigger - (durationMs ?: 0L)).coerceAtLeast(1L),
                                durationMs = durationMs,
                                state = state,
                            )
                        )
                    }
                }
            }.sortedBy { it.triggerAtEpochMs }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<ActiveAction>) {
        val arr = JSONArray()
        for (a in list) {
            val o = JSONObject()
            o.put("action_id", a.actionId)
            o.put("type", a.type)
            o.put("label", a.label)
            o.put("trigger_at_epoch_ms", a.triggerAtEpochMs)
            o.put("scheduled_at_epoch_ms", a.scheduledAtEpochMs)
            a.durationMs?.let { o.put("duration_ms", it) }
            o.put("state", a.state)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_JSON, arr.toString()).apply()
    }
}

