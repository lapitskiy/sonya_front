package com.example.sonya_front

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple local tasks without due date.
 * SharedPreferences JSON storage (minimal, no Room).
 */
object SimpleTasksStore {
    private const val PREFS = "simple_tasks_store"
    private const val KEY_JSON = "tasks_json"
    private const val KEY_NEXT_ID = "tasks_next_id"

    enum class Urgency(val raw: String, val labelRu: String) {
        LOW("low", "Низкая"),
        NORMAL("normal", "Обычная"),
        HIGH("high", "Срочно");

        companion object {
            fun fromRaw(raw: String?): Urgency {
                return when (raw?.lowercase()) {
                    "low" -> LOW
                    "high" -> HIGH
                    else -> NORMAL
                }
            }
        }
    }

    data class Task(
        val id: Int,
        val description: String,
        val category: String,
        val urgency: Urgency,
        val done: Boolean,
        val createdAtEpochMs: Long,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(ctx: Context): List<Task> {
        val raw = prefs(ctx).getString(KEY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optInt("id", -1)
                    val desc = o.optString("description", "")
                    val cat = o.optString("category", "")
                    val urg = Urgency.fromRaw(o.optString("urgency", "normal"))
                    val done = o.optBoolean("done", false)
                    val created = o.optLong("created_at_epoch_ms", 0L)
                    if (id > 0 && desc.isNotBlank()) {
                        add(
                            Task(
                                id = id,
                                description = desc,
                                category = cat,
                                urgency = urg,
                                done = done,
                                createdAtEpochMs = created.takeIf { it > 0L } ?: 0L,
                            )
                        )
                    }
                }
            }
                .sortedWith(compareBy<Task> { it.done }.thenByDescending { it.createdAtEpochMs }.thenByDescending { it.id })
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun add(ctx: Context, description: String, category: String, urgency: Urgency): Task? {
        val desc = description.trim()
        if (desc.isBlank()) return null
        val cat = category.trim()
        val now = System.currentTimeMillis()

        val p = prefs(ctx)
        val nextId = (p.getInt(KEY_NEXT_ID, 1)).coerceAtLeast(1)
        val task = Task(
            id = nextId,
            description = desc,
            category = cat,
            urgency = urgency,
            done = false,
            createdAtEpochMs = now,
        )
        val list = getAll(ctx).toMutableList()
        list.add(0, task)
        save(ctx, list)
        p.edit().putInt(KEY_NEXT_ID, nextId + 1).apply()
        return task
    }

    fun setDone(ctx: Context, id: Int, done: Boolean) {
        val list = getAll(ctx).map { if (it.id == id) it.copy(done = done) else it }
        save(ctx, list)
    }

    fun delete(ctx: Context, id: Int) {
        val list = getAll(ctx).filterNot { it.id == id }
        save(ctx, list)
    }

    fun clearDone(ctx: Context) {
        val list = getAll(ctx).filterNot { it.done }
        save(ctx, list)
    }

    private fun save(ctx: Context, list: List<Task>) {
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("description", t.description)
            o.put("category", t.category)
            o.put("urgency", t.urgency.raw)
            o.put("done", t.done)
            o.put("created_at_epoch_ms", t.createdAtEpochMs)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_JSON, arr.toString()).apply()
    }
}

