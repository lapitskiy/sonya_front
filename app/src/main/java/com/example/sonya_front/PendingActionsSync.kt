package com.example.sonya_front

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

object PendingActionsSync {
    private fun interestRatio(v: Any?): Double? {
        return try {
            when (v) {
                null -> null
                is Number -> {
                    val d = v.toDouble()
                    when {
                        d.isNaN() || d.isInfinite() -> null
                        d > 1.0 -> d / 100.0
                        else -> d
                    }
                }
                is String -> {
                    val s0 = v.trim()
                    if (s0.isBlank()) return null
                    val isPct = s0.endsWith("%")
                    val numStr = if (isPct) s0.removeSuffix("%").trim() else s0
                    val d = numStr.toDoubleOrNull() ?: return null
                    when {
                        d.isNaN() || d.isInfinite() -> null
                        isPct -> d / 100.0
                        d > 1.0 -> d / 100.0
                        else -> d
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun syncNow(context: Context, deviceId: String, reason: String) {
        if (deviceId.isBlank()) return
        try {
            val limit = 30
            var offset = 0
            var pulledTotal = 0
            var scheduledTotal = 0
            var storedTasksTotal = 0

            while (true) {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.instance.getPendingActions(deviceId = deviceId, limit = limit, offset = offset)
                }
                val items = resp.items
                if (items.isEmpty()) {
                    if (pulledTotal == 0) Log.d("SYNC", "No pending-actions (reason=$reason)")
                    break
                }

                pulledTotal += items.size

                // Special-case: "task" and "approx-alarm" items → save into backend /tasks.
                for (a in items) {
                    if (a.id <= 0) continue
                    val aType = a.type.lowercase()
                    if (aType != "task" && aType != "approx-alarm") continue
                    if (PendingActionStore.isHandled(context, a.id)) continue

                    val desc = (a.text?.trim()).takeUnless { it.isNullOrBlank() } ?: "Задание #${a.id}"

                    // Create in backend /tasks so it appears on Tasks/Day page.
                    val score = interestRatio(a.interest) ?: 0.0
                    val urgent = score >= 0.80
                    val important = score >= 0.40
                    try {
                        withContext(Dispatchers.IO) {
                            ApiClient.instance.createTask(
                                CreateTaskRequest(
                                    deviceId = deviceId,
                                    text = desc,
                                    urgent = urgent,
                                    important = important,
                                    dueDate = if (aType == "approx-alarm") a.time else null,
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w("TASKS", "Failed to create backend task from pending-action id=${a.id}: ${t.message}")
                        // Do not mark handled, we'll retry later.
                        continue
                    }

                    PendingActionStore.markHandled(context.applicationContext, a.id)
                    storedTasksTotal += 1

                    // Ack as "scheduled" so backend stops returning it as pending.
                    try {
                        withContext(Dispatchers.IO) {
                            ApiClient.instance.ack(
                                AckRequest(
                                    deviceId = deviceId,
                                    actionId = a.id,
                                    status = "scheduled",
                                    ack = mapOf(
                                        "reason" to "stored_as_task",
                                        "source" to reason,
                                        "stored_at" to Instant.now().toString(),
                                    )
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack stored task action_id=${a.id}: ${t.message}")
                    }
                }

                val scheduledIds = PendingActionsScheduler.scheduleAll(context, deviceId, items)
                scheduledTotal += scheduledIds.size

                // Ack each scheduled action (backend accepts per-item ack; idempotent).
                for (id in scheduledIds) {
                    try {
                        withContext(Dispatchers.IO) {
                            ApiClient.instance.ack(
                                AckRequest(
                                    deviceId = deviceId,
                                    actionId = id,
                                    status = "scheduled",
                                    ack = mapOf(
                                        "reason" to "scheduled",
                                        "source" to reason,
                                        "scheduled_at" to Instant.now().toString(),
                                    )
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack scheduled action_id=$id: ${t.message}")
                    }
                }

                // Next page
                if (items.size < limit) break
                offset += items.size
            }

            if (pulledTotal > 0) {
                Log.i("SYNC", "Pulled=$pulledTotal scheduled=$scheduledTotal tasksStored=$storedTasksTotal (reason=$reason)")
            }
        } catch (t: Throwable) {
            Log.w("SYNC", "Sync failed (reason=$reason): ${t.message}")
        }
    }
}

