package com.example.sonya_front

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime

object PendingActionsScheduler {
    // Backward-compat (old notification actions)
    private const val SNOOZE_MS = 5 * 60 * 1000L
    private const val SNOOZE_30M_MS = 30 * 60 * 1000L

    // New snooze presets
    private const val SNOOZE_15M_MS = 15 * 60 * 1000L
    private const val SNOOZE_60M_MS = 60 * 60 * 1000L
    private const val SNOOZE_180M_MS = 180 * 60 * 1000L
    /**
     * Re-renders ongoing countdown notifications for active timers.
     * (Needed because NotificationManager won't update remaining time automatically.)
     *
     * This is safe to call often (e.g. once per second while UI is visible).
     */
    fun refreshCountdownNotifications(context: Context) {
        val ctx = context.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.cancel(TIMER_GROUP_SUMMARY_ID)
        val items = ActiveActionsStore.getAll(ctx)
        val nowEpochMs = System.currentTimeMillis()
        for (a in items) {
            // "ringing" means it already fired; showing/updating countdown makes no sense and can stick at 00:00.
            if (a.state.lowercase() == "ringing") continue
            val t = a.type.lowercase()
            if (t != "timer" && t != "text-timer") continue

            // Safety: if we are overdue and the alarm didn't fire (OEM/Doze quirks), force-fire while UI is visible.
            val overdueMs = nowEpochMs - a.triggerAtEpochMs
            if (overdueMs > 2500L) {
                forceFireOverdueTimerAction(ctx, a)
                continue
            }

            // We need deviceId for notifications to support cancellation with ACK.
            // Since we don't store it in ActiveActionsStore yet, we'll try to find it from standard source.
            val deviceId = "android-" + android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            showTimerCountdownNotification(
                context = ctx,
                deviceId = deviceId,
                actionId = a.actionId,
                label = a.label,
                durationMs = a.durationMs ?: 0L,
                triggerAtEpochMs = a.triggerAtEpochMs,
            )
        }
    }

    private fun forceFireOverdueTimerAction(context: Context, a: ActiveActionsStore.ActiveAction) {
        try {
            // Cancel the scheduled alarm to avoid duplicate triggers later.
            cancelAlarmOnly(context, a.actionId)
        } catch (_: Throwable) {
        }
        try {
            val normalizedType = a.type.lowercase()
            val defaultSound = normalizedType == "timer" || normalizedType == "text-timer"
            val defaultVibration = defaultSound

            val deviceId = "android-" + android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            val intent = Intent(context, PendingActionReceiver::class.java).apply {
                action = PendingActionReceiver.ACTION_FIRE
                putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
                putExtra(PendingActionReceiver.EXTRA_ACTION_ID, a.actionId)
                putExtra(PendingActionReceiver.EXTRA_TYPE, a.type)
                putExtra(PendingActionReceiver.EXTRA_TTS, a.label)
                putExtra(PendingActionReceiver.EXTRA_SOUND, defaultSound)
                putExtra(PendingActionReceiver.EXTRA_VIBRATION, defaultVibration)
            }

            Log.w("SCHED", "Overdue timer detected; force-firing action_id=${a.actionId} type=${a.type} label=${a.label}")
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w("SCHED", "Failed to force-fire overdue timer action_id=${a.actionId}: ${t.message}")
        }
    }

    private fun cancelAlarmOnly(context: Context, actionId: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PendingActionReceiver::class.java).apply {
            action = PendingActionReceiver.ACTION_FIRE
        }
        val flags = (PendingIntent.FLAG_NO_CREATE) or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getBroadcast(context, actionId, intent, flags)
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * Cancels an action locally and sends a "cancelled" ACK to the backend.
     */
    fun cancelWithAck(context: Context, deviceId: String, actionId: Int): Boolean {
        val ok = cancel(context, actionId)
        if (!ok || deviceId.isBlank()) return ok

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ApiClient.instance.ack(
                    AckRequest(
                        deviceId = deviceId,
                        actionId = actionId,
                        status = "cancelled",
                        ack = mapOf(
                            "reason" to "cancelled_by_user",
                            "source" to "android",
                            "cancelled_at" to Instant.now().toString(),
                        ),
                    )
                )
            } catch (t: Throwable) {
                Log.w("ACK", "Failed to ack cancelled action_id=$actionId: ${t.message}")
            }
        }
        return ok
    }

    fun scheduleAll(context: Context, deviceId: String, actions: List<PendingAction>): List<Int> {
        Log.d("SCHED", "scheduleAll: ${actions.size} actions deviceId=$deviceId")
        val scheduledIds = mutableListOf<Int>()

        for (a in actions) {
            if (a.id <= 0) {
                Log.e("SCHED", "Backend sent pending-action without valid id: type=${a.type} time=${a.time} text=${a.text}")
                // We can't reliably update a specific task without id, but we still report the issue to backend.
                if (deviceId.isNotBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ApiClient.instance.ack(
                                AckRequest(
                                    deviceId = deviceId,
                                    actionId = 0,
                                    status = "cancelled",
                                    ack = mapOf(
                                        "reason" to "invalid_action_id_from_backend",
                                        "error" to "pending-action id must be > 0",
                                        "received_type" to a.type,
                                        "received_time" to (a.time ?: ""),
                                        "received_text" to (a.text ?: ""),
                                        "cancelled_at" to Instant.now().toString(),
                                    ),
                                )
                            )
                        } catch (t: Throwable) {
                            Log.w("ACK", "Failed to report invalid pending-action id: ${t.message}")
                        }
                    }
                }
                continue
            }
            val isHandled = PendingActionStore.isHandled(context, a.id)
            val isScheduled = PendingActionStore.isScheduled(context, a.id)
            if (isHandled || isScheduled) {
                Log.d("SCHED", "Skip id=${a.id} type=${a.type}: isHandled=$isHandled isScheduled=$isScheduled")
                continue
            }

            Log.i("SCHED", "Scheduling id=${a.id} type=${a.type} time=${a.time}")
            val ok = scheduleOne(context, deviceId, a)
            Log.i("SCHED", "scheduleOne result=$ok id=${a.id} type=${a.type}")
            if (ok) scheduledIds.add(a.id)
        }

        if (scheduledIds.isNotEmpty()) {
            PendingActionStore.markScheduled(context, scheduledIds)
        }
        return scheduledIds
    }

    private fun scheduleOne(context: Context, deviceId: String, a: PendingAction): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false

        val pi = buildPendingIntent(context, deviceId, a)

        val normalizedType = a.type.lowercase()
        val scheduledAtEpochMs = System.currentTimeMillis()

        if (normalizedType == "memory") {
            // Memory actions are "processed" immediately.
            Log.i("SCHED", "Memory action id=${a.id} detected, acking as fired immediately.")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ApiClient.instance.ack(
                        AckRequest(
                            deviceId = deviceId,
                            actionId = a.id,
                            status = "fired",
                            ack = mapOf(
                                "reason" to "memory_processed",
                                "fired_at" to Instant.now().toString()
                            )
                        )
                    )
                } catch (t: Throwable) {
                    Log.w("ACK", "Failed to ack memory action_id=${a.id}: ${t.message}")
                }
            }
            // Mark handled so we don't process it again in next sync.
            PendingActionStore.markHandled(context, a.id)
            vibrateConfirmation(context)
            return true
        }

        val alarmType: Int
        val triggerAtMs: Long
        val triggerAtEpochMsForUi: Long
        val durationMsForUi: Long?
        when (normalizedType) {
            "timer", "text-timer" -> {
                val timeStr = a.time
                if (timeStr == null) {
                    Log.e("SCHED", "scheduleOne FAIL id=${a.id} type=$normalizedType: time is null")
                    return false
                }
                val durMs = parseDurationMs(timeStr)
                if (durMs == null) {
                    Log.e("SCHED", "scheduleOne FAIL id=${a.id} type=$normalizedType: parseDurationMs failed for '$timeStr'")
                    return false
                }
                Log.d("SCHED", "scheduleOne timer id=${a.id} durMs=$durMs (${durMs/1000}s)")
                alarmType = AlarmManager.ELAPSED_REALTIME_WAKEUP
                triggerAtMs = SystemClock.elapsedRealtime() + durMs
                triggerAtEpochMsForUi = System.currentTimeMillis() + durMs
                durationMsForUi = durMs
            }
            "approx-alarm" -> {
                val timeStr = a.time
                if (timeStr == null) {
                    Log.e("SCHED", "scheduleOne FAIL id=${a.id} type=$normalizedType: time is null")
                    return false
                }
                val epochMs = parseDateTimeEpochMs(timeStr)
                if (epochMs == null) {
                    Log.e("SCHED", "scheduleOne FAIL id=${a.id} type=$normalizedType: parseDateTimeEpochMs failed for '$timeStr'")
                    return false
                }
                val nowMs = System.currentTimeMillis()
                if (epochMs <= nowMs) {
                    Log.w("SCHED", "approx-alarm id=${a.id} time is in the past (epochMs=$epochMs nowMs=$nowMs), skipping scheduling")
                    PendingActionStore.markHandled(context, a.id)
                    return false
                }
                alarmType = AlarmManager.RTC_WAKEUP
                triggerAtMs = epochMs
                triggerAtEpochMsForUi = epochMs
                durationMsForUi = null
            }
            else -> {
                Log.e("SCHED", "scheduleOne FAIL id=${a.id}: unsupported type='$normalizedType'")
                return false
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Exact alarms may be blocked unless the user granted permission (SCHEDULE_EXACT_ALARM).
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.w("SCHED", "Exact alarms not allowed; scheduling may be inexact. id=${a.id} type=${a.type}")
                }
            }
            var exactUsed = true
            try {
                alarmManager.setExactAndAllowWhileIdle(alarmType, triggerAtMs, pi)
            } catch (se: SecurityException) {
                // Some devices/OS versions throw here when exact-alarms permission isn't granted.
                exactUsed = false
                Log.w("SCHED", "Exact alarm blocked; falling back to inexact set. id=${a.id} type=${a.type} err=${se.message}")
                try {
                    // Still try "allow while idle" (may or may not be permitted).
                    alarmManager.setAndAllowWhileIdle(alarmType, triggerAtMs, pi)
                } catch (se2: SecurityException) {
                    // Last resort: plain set (inexact).
                    Log.w("SCHED", "setAndAllowWhileIdle blocked too; falling back to set(). id=${a.id} type=${a.type} err=${se2.message}")
                    alarmManager.set(alarmType, triggerAtMs, pi)
                }
            }
            Log.i("SCHED", "Scheduled id=${a.id} type=${a.type} time=${a.time ?: "<no-time>"} atMs=$triggerAtMs exact=$exactUsed")

            // Track in local UI store so user can see countdown / pending timers.
            val label = a.text ?: when (normalizedType) {
                "timer", "text-timer" -> "Таймер"
                "approx-alarm" -> "Напоминание"
                else -> "Событие"
            }
            ActiveActionsStore.upsert(
                context,
                ActiveActionsStore.ActiveAction(
                    actionId = a.id,
                    type = a.type,
                    label = label,
                    triggerAtEpochMs = triggerAtEpochMsForUi,
                    scheduledAtEpochMs = scheduledAtEpochMs,
                    durationMs = durationMsForUi,
                    state = "scheduled",
                )
            )

            // Show an ongoing "countdown" notification for timers so the user can cancel from the tray.
            if (normalizedType == "timer" || normalizedType == "text-timer") {
                showTimerCountdownNotification(
                    context = context,
                    deviceId = deviceId,
                    actionId = a.id,
                    label = label,
                    durationMs = durationMsForUi ?: 0L,
                    triggerAtEpochMs = triggerAtEpochMsForUi,
                )
            }
            vibrateConfirmation(context)
            return true
        } catch (t: Throwable) {
            Log.e("SCHED", "Failed to schedule alarm: ${t.message}", t)
            return false
        }
    }

    private fun buildPendingIntent(context: Context, deviceId: String, a: PendingAction): PendingIntent {
        val normalizedType = a.type.lowercase()
        val defaultTts = when (normalizedType) {
            "timer", "text-timer" -> a.text ?: "Таймер завершён"
            "approx-alarm" -> a.text ?: "Напоминание"
            else -> a.text
        }
        val defaultSound = normalizedType == "timer" || normalizedType == "text-timer" || normalizedType == "approx-alarm"
        val defaultVibration = defaultSound

        val intent = Intent(context, PendingActionReceiver::class.java).apply {
            action = PendingActionReceiver.ACTION_FIRE
            putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
            putExtra(PendingActionReceiver.EXTRA_ACTION_ID, a.id)
            putExtra(PendingActionReceiver.EXTRA_TYPE, a.type)
            putExtra(PendingActionReceiver.EXTRA_TTS, defaultTts)
            putExtra(PendingActionReceiver.EXTRA_SOUND, defaultSound)
            putExtra(PendingActionReceiver.EXTRA_VIBRATION, defaultVibration)
        }

        val flags = (PendingIntent.FLAG_UPDATE_CURRENT) or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, stableRequestCode(a.id), intent, flags)
    }

    private fun stableRequestCode(id: Int): Int = id

    /**
     * Snooze a ringing timer/alarm by +5 minutes from now.
     * Keeps the same action id (backend id), re-schedules AlarmManager, and updates local UI store.
     */
    fun snooze5m(context: Context, deviceId: String, actionId: Int, type: String, label: String): Boolean {
        return snoozeImpl(
            context = context,
            deviceId = deviceId,
            actionId = actionId,
            type = type,
            label = label,
            minutes = 5,
            offsetMs = SNOOZE_MS,
        )
    }

    /**
     * Snooze a ringing timer/alarm by +30 minutes from now.
     * Keeps the same action id (backend id), re-schedules AlarmManager, and updates local UI store.
     */
    fun snooze30m(context: Context, deviceId: String, actionId: Int, type: String, label: String): Boolean {
        return snoozeImpl(
            context = context,
            deviceId = deviceId,
            actionId = actionId,
            type = type,
            label = label,
            minutes = 30,
            offsetMs = SNOOZE_30M_MS,
        )
    }

    /**
     * Snooze a ringing timer/alarm by +15 minutes from now.
     */
    fun snooze15m(context: Context, deviceId: String, actionId: Int, type: String, label: String): Boolean {
        return snoozeImpl(
            context = context,
            deviceId = deviceId,
            actionId = actionId,
            type = type,
            label = label,
            minutes = 15,
            offsetMs = SNOOZE_15M_MS,
        )
    }

    /**
     * Snooze a ringing timer/alarm by +60 minutes from now.
     */
    fun snooze60m(context: Context, deviceId: String, actionId: Int, type: String, label: String): Boolean {
        return snoozeImpl(
            context = context,
            deviceId = deviceId,
            actionId = actionId,
            type = type,
            label = label,
            minutes = 60,
            offsetMs = SNOOZE_60M_MS,
        )
    }

    /**
     * Snooze a ringing timer/alarm by +180 minutes from now.
     */
    fun snooze180m(context: Context, deviceId: String, actionId: Int, type: String, label: String): Boolean {
        return snoozeImpl(
            context = context,
            deviceId = deviceId,
            actionId = actionId,
            type = type,
            label = label,
            minutes = 180,
            offsetMs = SNOOZE_180M_MS,
        )
    }

    private fun snoozeImpl(
        context: Context,
        deviceId: String,
        actionId: Int,
        type: String,
        label: String,
        minutes: Int,
        offsetMs: Long,
    ): Boolean {
        if (deviceId.isBlank() || actionId <= 0) return false
        val ctx = context.applicationContext

        val normalizedType = type.lowercase()

        val timeStr = when (normalizedType) {
            "timer", "text-timer" -> "PT${minutes}M"
            else -> return false
        }

        // Allow re-scheduling the same action id after it fired.
        PendingActionStore.unmarkHandled(ctx, actionId)

        val a = PendingAction(
            id = actionId,
            type = type,
            time = timeStr,
            text = label,
        )

        val ok = scheduleOne(ctx, deviceId, a)
        if (ok) {
            PendingActionStore.markScheduled(ctx, listOf(actionId))
        }
        return ok
    }

    fun cancel(context: Context, actionId: Int): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            val intent = Intent(context, PendingActionReceiver::class.java).apply {
                action = PendingActionReceiver.ACTION_FIRE
            }
            val flags = (PendingIntent.FLAG_NO_CREATE) or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, actionId, intent, flags)
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
            ActiveActionsStore.remove(context, actionId)
            PendingActionStore.markHandled(context, actionId)
            cancelCountdownNotification(context, actionId)
            Log.i("SCHED", "Cancelled action_id=$actionId")
            true
        } catch (t: Throwable) {
            Log.w("SCHED", "Cancel failed action_id=$actionId: ${t.message}")
            false
        }
    }

    fun cancelCountdownNotification(context: Context, actionId: Int) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(notificationIdForTimer(actionId))
            nm.cancel(TIMER_GROUP_SUMMARY_ID)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun showTimerCountdownNotification(
        context: Context,
        deviceId: String,
        actionId: Int,
        label: String,
        durationMs: Long,
        triggerAtEpochMs: Long,
    ) {
        try {
            ensureTimerChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return

            val remainMs = (triggerAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val mm = (remainMs / 60_000L).toInt().coerceAtLeast(0)
            val ss = ((remainMs % 60_000L) / 1000L).toInt().coerceIn(0, 59)
            val pretty = "%02d:%02d".format(mm, ss)

            val cancelIntent = Intent(context, PendingActionReceiver::class.java).apply {
                action = PendingActionReceiver.ACTION_CANCEL
                putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
                putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
            }
            val cancelPi = PendingIntent.getBroadcast(
                context,
                actionId,
                cancelIntent,
                (PendingIntent.FLAG_UPDATE_CURRENT) or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val title = "Таймер"
            val body = "$label • осталось $pretty"

            val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val openPi = if (openIntent != null) {
                PendingIntent.getActivity(
                    context,
                    actionId,
                    openIntent,
                    (PendingIntent.FLAG_UPDATE_CURRENT) or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
            } else null

            val builder = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                // Some OEMs don't show actions when icon=0, so use a real icon.
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancelPi)

            // Keep only textual countdown; don't show progress bar (blue strip) in the notification.
            if (openPi != null) builder.setContentIntent(openPi)

            nm.notify(notificationIdForTimer(actionId), builder.build())
            nm.cancel(TIMER_GROUP_SUMMARY_ID)
        } catch (t: Throwable) {
            Log.w("SCHED", "Failed to show timer notification action_id=$actionId: ${t.message}")
        }
    }

    private fun ensureTimerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(TIMER_CHANNEL_ID)
        if (existing != null) return
        val ch = NotificationChannel(
            TIMER_CHANNEL_ID,
            "Timers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active timer countdowns"
        }
        nm.createNotificationChannel(ch)
    }

    private fun notificationIdForTimer(actionId: Int): Int = (TIMER_NOTIF_ID_BASE + actionId).coerceAtLeast(TIMER_NOTIF_ID_BASE + 1)

    private fun parseDurationMs(text: String): Long? {
        val trimmed = text.trim()

        // Primary: ISO-8601 duration (PT10S, PT30M, PT1H5M, etc) - manual parse first (more reliable across devices).
        if (trimmed.startsWith("PT", ignoreCase = true)) {
            try {
                var totalMs = 0L
                val re = Regex("(\\d+)([HMS])", RegexOption.IGNORE_CASE)
                val matches = re.findAll(trimmed.uppercase())
                var found = false
                for (m in matches) {
                    val v = m.groupValues[1].toLong()
                    val unit = m.groupValues[2]
                    totalMs += when (unit) {
                        "H" -> v * 3_600_000L
                        "M" -> v * 60_000L
                        "S" -> v * 1_000L
                        else -> 0L
                    }
                    found = true
                }
                if (found) return totalMs.coerceAtLeast(1L)
            } catch (_: Throwable) {
            }
        }

        // Secondary: java.time.Duration.parse (PT..)
        try {
            val d = Duration.parse(trimmed)
            return d.toMillis().coerceAtLeast(1L)
        } catch (_: Throwable) {
        }

        // Fallback: H:M:S like "0:0:10"
        try {
            val parts = trimmed.split(":")
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val s = parts[2].toLong()
                return ((h * 3600 + m * 60 + s).coerceAtLeast(1L)) * 1000L
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun parseDateTimeEpochMs(text: String): Long? {
        // Contract: ISO-8601 datetime with timezone, e.g. 2026-01-29T09:00:00+03:00
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Throwable) {
        }
        try {
            return ZonedDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Throwable) {
        }
        try {
            return Instant.parse(text).toEpochMilli()
        } catch (_: Throwable) {
        }
        return null
    }

    private fun vibrateConfirmation(context: Context) {
        if (!UserSettingsStore.getVibrateOnConfirm(context.applicationContext)) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(1000)
            }
        } catch (t: Throwable) {
            Log.w("SCHED", "Failed to vibrate confirmation: ${t.message}")
        }
    }

    private const val TIMER_CHANNEL_ID = "TIMER_CHANNEL"
    private const val TIMER_NOTIF_ID_BASE = 20_000
    private const val TIMER_GROUP_SUMMARY_ID = 20_000
}

