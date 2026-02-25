package com.example.sonya_front

import android.content.Context

/**
 * Remembers which "what-said/requests" items were already ingested into local SimpleTasksStore.
 * This prevents duplicates when the requests list is refreshed.
 */
object IngestedTasksStore {
    private const val PREFS = "ingested_tasks_store"
    private const val KEY_INGESTED_REQUEST_IDS = "ingested_request_ids"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isIngested(ctx: Context, requestId: Int): Boolean {
        if (requestId <= 0) return true
        return prefs(ctx).getStringSet(KEY_INGESTED_REQUEST_IDS, emptySet())?.contains(requestId.toString()) == true
    }

    fun markIngested(ctx: Context, requestId: Int) {
        if (requestId <= 0) return
        val p = prefs(ctx)
        val cur = (p.getStringSet(KEY_INGESTED_REQUEST_IDS, emptySet()) ?: emptySet()).toMutableSet()
        if (!cur.add(requestId.toString())) return
        p.edit().putStringSet(KEY_INGESTED_REQUEST_IDS, cur).apply()
    }
}

object TasksIngestor {
    /**
     * Ingests plain tasks (type="task") from requests history into backend /tasks.
     * Returns number of tasks added.
     */
    suspend fun ingestFromRequests(ctx: Context, deviceId: String, items: List<WhatSaidRequestItem>): Int {
        if (deviceId.isBlank()) return 0
        var added = 0
        val appCtx = ctx.applicationContext

        for (it in items) {
            val reqId = it.id ?: continue
            if (IngestedTasksStore.isIngested(appCtx, reqId)) continue

            val type = (it.pendingAction?.type)
                ?: (it.payload?.intent?.get("type") as? String)
                ?: (it.payload?.nlu?.get("type") as? String)

            if (type?.lowercase() != "task") {
                // Mark as ingested anyway so we don't scan it again and again.
                IngestedTasksStore.markIngested(appCtx, reqId)
                continue
            }

            val intent = it.payload?.intent
            val textFromIntent = intent?.get("text") as? String
            val desc = (textFromIntent ?: it.payload?.received?.text ?: "").trim().ifBlank { "Задание #$reqId" }

            val urgent = intent?.get("urgent") as? Boolean ?: false
            val important = intent?.get("important") as? Boolean ?: false

            try {
                ApiClient.instance.createTask(
                    CreateTaskRequest(
                        deviceId = deviceId,
                        text = desc,
                        urgent = urgent,
                        important = important,
                    )
                )
                IngestedTasksStore.markIngested(appCtx, reqId)
                added += 1
            } catch (_: Throwable) {
                // Do not mark ingested on failures (we'll retry on next refresh).
            }
        }

        return added
    }
}

