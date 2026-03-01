package com.example.sonya_front

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

object PendingActionsSync {
    private fun str(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        else -> v.toString()
    }?.trim()?.takeIf { it.isNotBlank() }

    private fun parseEpochMs(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        val candidates = linkedSetOf(s, s.replace(' ', 'T'))
        for (c in candidates) {
            try {
                return OffsetDateTime.parse(c).toInstant().toEpochMilli()
            } catch (_: Throwable) {
            }
            try {
                return ZonedDateTime.parse(c).toInstant().toEpochMilli()
            } catch (_: Throwable) {
            }
            try {
                return Instant.parse(c).toEpochMilli()
            } catch (_: Throwable) {
            }
            try {
                return java.time.LocalDateTime.parse(c).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun dayTypeByEpochMs(epochMs: Long): String {
        val today = LocalDate.now()
        val dueDay = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return when {
            dueDay.isEqual(today.minusDays(1)) -> "yesterday"
            dueDay.isEqual(today) -> "today"
            dueDay.isEqual(today.plusDays(1)) -> "tomorrow"
            dueDay.isAfter(today.plusDays(1)) -> "future"
            else -> "today"
        }
    }

    private fun ackScheduled(deviceId: String, actionId: Int, source: String, status: String = "scheduled"): AckRequest {
        return AckRequest(
            deviceId = deviceId,
            actionId = actionId,
            status = status,
            ack = mapOf(
                "reason" to "scheduled",
                "source" to source,
                "scheduled_at" to Instant.now().toString(),
            )
        )
    }

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
        Log.i("SYNC", "syncNow start reason=$reason deviceId=$deviceId")
        try {
            val limit = 30
            var offset = 0
            var pulledTotal = 0
            var scheduledTotal = 0
            var storedTasksTotal = 0

            // 1) Primary source: /pending-actions (backend -> Android scheduler list)
            while (true) {
                Log.d("SYNC", "Fetching pending-actions offset=$offset limit=$limit reason=$reason")
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.instance.getPendingActions(deviceId = deviceId, limit = limit, offset = offset)
                }
                val items = resp.items
                Log.i("SYNC", "Got ${items.size} pending-actions (offset=$offset reason=$reason)")
                if (items.isEmpty()) {
                    if (pulledTotal == 0) Log.d("SYNC", "No pending-actions (reason=$reason)")
                    break
                }

                for (a in items) {
                    Log.d("SYNC", "  item id=${a.id} type=${a.type} time=${a.time} text=${a.text}")
                }

                pulledTotal += items.size

                // Save into backend /tasks only explicit "task" items.
                for (a in items) {
                    if (a.id <= 0) continue
                    val aType = a.type.lowercase()
                    if (aType != "task") continue
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
                Log.i("SYNC", "scheduleAll returned scheduledIds=$scheduledIds (reason=$reason)")
                scheduledTotal += scheduledIds.size

                // Retry ACK for locally scheduled items too:
                // if initial ACK failed due to network, backend can stay "pending" forever.
                val retryScheduledIds = items
                    .asSequence()
                    .map { it.id }
                    .filter { it > 0 && PendingActionStore.isScheduled(context, it) }
                    .toSet()
                val ackScheduledIds = (scheduledIds + retryScheduledIds).toSet()
                Log.i("SYNC", "Will ACK ids=$ackScheduledIds (new=${scheduledIds.size} retry=${retryScheduledIds.size} reason=$reason)")

                // Ack each scheduled action (backend accepts per-item ack; idempotent).
                for (id in ackScheduledIds) {
                    try {
                        withContext(Dispatchers.IO) {
                            ApiClient.instance.ack(
                                ackScheduled(deviceId = deviceId, actionId = id, source = "pending_actions:$reason", status = "scheduled")
                            )
                        }
                        Log.i("ACK", "ACK sent ok action_id=$id status=scheduled source=$reason")
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack scheduled action_id=$id: ${t.message}")
                    }
                }

                // Next page
                if (items.size < limit) break
                offset += items.size
            }

            // 2) Secondary source: /what-said/requests pending_action (this is what UI shows as "pending").
            // Some backends do not populate /pending-actions but still attach pending_action to requests history.
            try {
                Log.d("SYNC", "Fetching what-said/requests for pending_action scheduling (reason=$reason)")
                val respReq = withContext(Dispatchers.IO) {
                    ApiClient.instance.getWhatSaidRequests(deviceId = deviceId, limit = 50, offset = 0)
                }
                val reqItems = respReq.items
                Log.i("SYNC", "Got ${reqItems.size} requests (reason=$reason)")

                for (r in reqItems) {
                    val pa = r.pendingAction ?: continue
                    val paId = pa.id ?: continue
                    val paStatus = pa.status?.trim()?.lowercase()
                    if (paStatus != null && paStatus != "pending") continue

                    val alreadyHandled = PendingActionStore.isHandled(context, paId)
                    val alreadyScheduled = PendingActionStore.isScheduled(context, paId)
                    if (alreadyHandled || alreadyScheduled) {
                        Log.d("SYNC", "Skip req pending_action id=$paId: handled=$alreadyHandled scheduled=$alreadyScheduled")
                        continue
                    }

                    val intent = r.payload?.intent
                    val nlu = r.payload?.nlu
                    val rawType = pa.type
                        ?: (intent?.get("type") as? String)
                        ?: (nlu?.get("type") as? String)
                    val ty = rawType?.trim()?.lowercase().orEmpty()

                    // Try to get duration or due time from intent/nlu.
                    val rawTime = str(intent?.get("time")) ?: str(nlu?.get("time"))
                    val rawDueAt = str(intent?.get("due_at")) ?: str(nlu?.get("due_at"))

                    Log.i(
                        "SYNC",
                        "req pending_action id=$paId type='$ty' status=${pa.status} time=$rawTime due_at=$rawDueAt text='${r.payload?.received?.text}'"
                    )

                    val action: PendingAction? = when (ty) {
                        "timer", "text-timer" -> {
                            val t = rawTime
                            if (t != null) {
                                PendingAction(id = paId, type = ty, time = t, text = r.payload?.received?.text)
                            } else {
                                val dueMs = parseEpochMs(rawDueAt)
                                val nowMs = System.currentTimeMillis()
                                val durMs = if (dueMs != null) (dueMs - nowMs).coerceAtLeast(0L) else null
                                if (durMs != null && durMs > 0) {
                                    PendingAction(id = paId, type = "timer", time = "PT${durMs / 1000L}S", text = r.payload?.received?.text)
                                } else null
                            }
                        }
                        // Backend may return "type":"time" with "due_at" (see /command response).
                        "time", "approx-alarm" -> {
                            val dueMs = parseEpochMs(rawDueAt)
                            if (dueMs != null) {
                                PendingAction(id = paId, type = "approx-alarm", time = rawDueAt, text = r.payload?.received?.text)
                            } else null
                        }
                        else -> null
                    }

                    if (action == null) {
                        Log.w("SYNC", "Cannot schedule req pending_action id=$paId: unsupported/missing fields type='$ty' time=$rawTime due_at=$rawDueAt")
                        continue
                    }

                    val scheduled = PendingActionsScheduler.scheduleAll(context, deviceId, listOf(action))
                    if (scheduled.isEmpty()) {
                        Log.w("SYNC", "scheduleAll returned empty for req pending_action id=$paId type=${action.type}")
                        continue
                    }
                    scheduledTotal += scheduled.size

                    for (id in scheduled) {
                        try {
                            withContext(Dispatchers.IO) {
                                // Backend stores whatever status Android sends (scheduled/fired/etc).
                                ApiClient.instance.ack(ackScheduled(deviceId = deviceId, actionId = id, source = "requests:$reason", status = "scheduled"))
                            }
                            Log.i("ACK", "ACK sent ok action_id=$id status=scheduled source=requests:$reason")
                        } catch (t: Throwable) {
                            Log.w("ACK", "Failed to ack requests-scheduled action_id=$id: ${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("SYNC", "Requests scheduling failed (reason=$reason): ${t.message}")
            }

            if (pulledTotal > 0) {
                Log.i("SYNC", "Pulled=$pulledTotal scheduled=$scheduledTotal tasksStored=$storedTasksTotal (reason=$reason)")
            }
        } catch (t: Throwable) {
            Log.e("SYNC", "Sync FAILED (reason=$reason): ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }
}

