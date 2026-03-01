package com.example.sonya_front

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant
import java.util.Locale

/**
 * Runs when an AlarmManager event fires.
 * Must be a ForegroundService if we play sound / TTS in background.
 */
class PendingActionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != PendingActionReceiver.ACTION_FIRE &&
            action != ACTION_DONE &&
            action != ACTION_SNOOZE &&          // backward compat
            action != ACTION_SNOOZE_30 &&       // backward compat
            action != ACTION_SNOOZE_15 &&
            action != ACTION_SNOOZE_60 &&
            action != ACTION_SNOOZE_180 &&
            action != ACTION_STOP
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        val deviceId = intent.getStringExtra(PendingActionReceiver.EXTRA_DEVICE_ID).orEmpty()
        val id = intent.getIntExtra(PendingActionReceiver.EXTRA_ACTION_ID, -1)
        val type = intent.getStringExtra(PendingActionReceiver.EXTRA_TYPE).orEmpty()
        val ttsText = intent.getStringExtra(PendingActionReceiver.EXTRA_TTS)
        val sound = intent.getBooleanExtra(PendingActionReceiver.EXTRA_SOUND, false)
        val vib = intent.getBooleanExtra(PendingActionReceiver.EXTRA_VIBRATION, false)
        val normalizedType = type.lowercase()

        if (action == ACTION_DONE || action == ACTION_STOP) {
            stopPlayback()
            cancelNotification(id)

            // Mark as done and remove from UI list.
            ActiveActionsStore.remove(applicationContext, id)
            PendingActionsScheduler.cancelCountdownNotification(applicationContext, id)

            // Send ack for manual "done" if we have deviceId
            if (deviceId.isNotBlank() && id > 0) {
                serviceScope.launch {
                    try {
                        ApiClient.instance.ack(
                            AckRequest(
                                deviceId = deviceId,
                                actionId = id,
                                status = "fired",
                                ack = mapOf(
                                    "reason" to "done_confirmed",
                                    "source" to "android_foreground_service",
                                    "done_at" to Instant.now().toString()
                                )
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack done action_id=$id: ${t.message}")
                    }
                }
            }

            stopSelf()
            return START_NOT_STICKY
        }

        // Backward-compat: old "+5" action.
        if (action == ACTION_SNOOZE) {
            stopPlayback()
            cancelNotification(id)

            // Reschedule +5m and update UI store.
            val ok = PendingActionsScheduler.snooze5m(
                context = applicationContext,
                deviceId = deviceId,
                actionId = id,
                type = type,
                label = (ttsText ?: "").ifBlank { "Событие" },
            )

            if (deviceId.isNotBlank() && id > 0) {
                serviceScope.launch {
                    try {
                        ApiClient.instance.ack(
                            AckRequest(
                                deviceId = deviceId,
                                actionId = id,
                                status = "scheduled",
                                ack = mapOf(
                                    "reason" to "snoozed_5m",
                                    "source" to "android_foreground_service",
                                    "snoozed_ok" to ok.toString(),
                                    "snoozed_at" to Instant.now().toString(),
                                )
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack snooze action_id=$id: ${t.message}")
                    }
                }
            }

            stopSelf()
            return START_NOT_STICKY
        }

        // Backward-compat: old "+30" action.
        if (action == ACTION_SNOOZE_30) {
            stopPlayback()
            cancelNotification(id)

            // Reschedule +30m and update UI store.
            val ok = PendingActionsScheduler.snooze30m(
                context = applicationContext,
                deviceId = deviceId,
                actionId = id,
                type = type,
                label = (ttsText ?: "").ifBlank { "Событие" },
            )

            if (deviceId.isNotBlank() && id > 0) {
                serviceScope.launch {
                    try {
                        ApiClient.instance.ack(
                            AckRequest(
                                deviceId = deviceId,
                                actionId = id,
                                status = "scheduled",
                                ack = mapOf(
                                    "reason" to "snoozed_30m",
                                    "source" to "android_foreground_service",
                                    "snoozed_ok" to ok.toString(),
                                    "snoozed_at" to Instant.now().toString(),
                                )
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack snooze30 action_id=$id: ${t.message}")
                    }
                }
            }

            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_SNOOZE_15) {
            stopPlayback()
            cancelNotification(id)

            val ok = PendingActionsScheduler.snooze15m(
                context = applicationContext,
                deviceId = deviceId,
                actionId = id,
                type = type,
                label = (ttsText ?: "").ifBlank { "Событие" },
            )

            if (deviceId.isNotBlank() && id > 0) {
                serviceScope.launch {
                    try {
                        ApiClient.instance.ack(
                            AckRequest(
                                deviceId = deviceId,
                                actionId = id,
                                status = "scheduled",
                                ack = mapOf(
                                    "reason" to "snoozed_15m",
                                    "source" to "android_foreground_service",
                                    "snoozed_ok" to ok.toString(),
                                    "snoozed_at" to Instant.now().toString(),
                                )
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack snooze15 action_id=$id: ${t.message}")
                    }
                }
            }

            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_SNOOZE_60) {
            stopPlayback()
            cancelNotification(id)

            val ok = PendingActionsScheduler.snooze60m(
                context = applicationContext,
                deviceId = deviceId,
                actionId = id,
                type = type,
                label = (ttsText ?: "").ifBlank { "Событие" },
            )

            if (deviceId.isNotBlank() && id > 0) {
                serviceScope.launch {
                    try {
                        ApiClient.instance.ack(
                            AckRequest(
                                deviceId = deviceId,
                                actionId = id,
                                status = "scheduled",
                                ack = mapOf(
                                    "reason" to "snoozed_60m",
                                    "source" to "android_foreground_service",
                                    "snoozed_ok" to ok.toString(),
                                    "snoozed_at" to Instant.now().toString(),
                                )
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack snooze60 action_id=$id: ${t.message}")
                    }
                }
            }

            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_SNOOZE_180) {
            stopPlayback()
            cancelNotification(id)

            val ok = PendingActionsScheduler.snooze180m(
                context = applicationContext,
                deviceId = deviceId,
                actionId = id,
                type = type,
                label = (ttsText ?: "").ifBlank { "Событие" },
            )

            if (deviceId.isNotBlank() && id > 0) {
                serviceScope.launch {
                    try {
                        ApiClient.instance.ack(
                            AckRequest(
                                deviceId = deviceId,
                                actionId = id,
                                status = "scheduled",
                                ack = mapOf(
                                    "reason" to "snoozed_180m",
                                    "source" to "android_foreground_service",
                                    "snoozed_ok" to ok.toString(),
                                    "snoozed_at" to Instant.now().toString(),
                                )
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w("ACK", "Failed to ack snooze180 action_id=$id: ${t.message}")
                    }
                }
            }

            stopSelf()
            return START_NOT_STICKY
        }

        val title = when (type.lowercase()) {
            "timer" -> "Таймер"
            "text-timer" -> "Напоминание"
            "reminder" -> "Напоминание"
            else -> "Событие"
        }
        val body = ttsText ?: "Сработало событие: $title"

        val notificationId = notificationIdForAction(id)
        startForeground(notificationId, buildNotification(deviceId, id, title, body, type, ttsText))

        if (vib) vibrate()
        if (sound) playBeep()
        if (!ttsText.isNullOrBlank()) speak(ttsText)

        // Ack fired (best-effort).
        if (deviceId.isNotBlank() && id > 0) {
            serviceScope.launch {
                try {
                    ApiClient.instance.ack(
                        AckRequest(
                            deviceId = deviceId,
                            actionId = id,
                            status = "fired",
                            ack = mapOf(
                                "reason" to "fired",
                                "fired_at" to Instant.now().toString(),
                                "type" to type,
                                "sound" to sound.toString(),
                                "vibration" to vib.toString(),
                                "tts" to (ttsText ?: ""),
                            )
                        )
                    )
                } catch (t: Throwable) {
                    Log.w("ACK", "Failed to ack fired action_id=$id: ${t.message}")
                }
            }
        }

        // Auto-stop ONLY for classic "timer". For text-timer/alarm: keep playing until Done/Snooze.
        mainHandler.removeCallbacksAndMessages(null)
        if (normalizedType == "timer") {
            mainHandler.postDelayed({
                stopPlayback()
                cancelNotification(id)
                stopSelf()
            }, AUTO_STOP_MS)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        stopPlayback()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        } finally {
            tts = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarm/timer/reminder notifications"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(
        deviceId: String,
        actionId: Int,
        title: String,
        body: String,
        type: String,
        ttsText: String?,
    ): Notification {
        val normalizedType = type.lowercase()
        if (normalizedType == "timer") {
            // Classic timer: show a single "Stop" action (old behavior).
            val stopIntent = Intent(this, PendingActionForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
                putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
                putExtra(PendingActionReceiver.EXTRA_TYPE, type)
                putExtra(PendingActionReceiver.EXTRA_TTS, ttsText)
            }
            val stopPi = PendingIntent.getService(
                this,
                actionId,
                stopIntent,
                (PendingIntent.FLAG_UPDATE_CURRENT) or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                // Avoid adaptive mipmap launcher (XML) here: some OEM SystemUI fails to decode it.
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ico_64))
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPi)
                .build()
        }

        val doneIntent = Intent(this, PendingActionForegroundService::class.java).apply {
            action = ACTION_DONE
            putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
            putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(PendingActionReceiver.EXTRA_TYPE, type)
            putExtra(PendingActionReceiver.EXTRA_TTS, ttsText)
        }
        val donePi = PendingIntent.getService(
            this,
            actionId,
            doneIntent,
            (PendingIntent.FLAG_UPDATE_CURRENT) or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val snoozeIntent = Intent(this, PendingActionForegroundService::class.java).apply {
            action = ACTION_SNOOZE_15
            putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
            putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(PendingActionReceiver.EXTRA_TYPE, type)
            putExtra(PendingActionReceiver.EXTRA_TTS, ttsText)
        }
        val snoozePi = PendingIntent.getService(
            this,
            actionId + 1,
            snoozeIntent,
            (PendingIntent.FLAG_UPDATE_CURRENT) or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val snooze60Intent = Intent(this, PendingActionForegroundService::class.java).apply {
            action = ACTION_SNOOZE_60
            putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
            putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(PendingActionReceiver.EXTRA_TYPE, type)
            putExtra(PendingActionReceiver.EXTRA_TTS, ttsText)
        }
        val snooze60Pi = PendingIntent.getService(
            this,
            actionId + 2,
            snooze60Intent,
            (PendingIntent.FLAG_UPDATE_CURRENT) or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val snooze180Intent = Intent(this, PendingActionForegroundService::class.java).apply {
            action = ACTION_SNOOZE_180
            putExtra(PendingActionReceiver.EXTRA_DEVICE_ID, deviceId)
            putExtra(PendingActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(PendingActionReceiver.EXTRA_TYPE, type)
            putExtra(PendingActionReceiver.EXTRA_TTS, ttsText)
        }
        val snooze180Pi = PendingIntent.getService(
            this,
            actionId + 3,
            snooze180Intent,
            (PendingIntent.FLAG_UPDATE_CURRENT) or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // Avoid adaptive mipmap launcher (XML) here: some OEM SystemUI fails to decode it.
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ico_64))
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            // Some OEMs don't show actions when icon=0, so use a real icon.
            .addAction(android.R.drawable.ic_menu_save, "Выполнено", donePi)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "+15", snoozePi)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "+60", snooze60Pi)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "+180", snooze180Pi)
            .build()
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            } ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(700)
            }
        } catch (t: Throwable) {
            Log.w("ALARM", "Vibrate failed: ${t.message}")
        }
    }

    private fun playBeep() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            stopPlayback()

            val p = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@PendingActionForegroundService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { mp, _, _ ->
                    try { mp.stop() } catch (_: Throwable) {}
                    try { mp.release() } catch (_: Throwable) {}
                    if (player === mp) player = null
                    true
                }
                prepareAsync()
            }
            player = p
            Log.i("ALARM_SOUND", "MediaPlayer started (looping=true)")
        } catch (t: Throwable) {
            Log.w("ALARM", "Beep failed: ${t.message}")
        }
    }

    private fun speak(text: String) {
        try {
            if (tts != null) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "evt")
                return
            }
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("ru", "RU")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "evt")
                }
            }
        } catch (t: Throwable) {
            Log.w("ALARM", "TTS failed: ${t.message}")
        }
    }

    private fun stopPlayback() {
        try {
            player?.let { p ->
                try { if (p.isPlaying) p.stop() } catch (_: Throwable) {}
                try { p.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        } finally {
            player = null
        }
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
        Log.i("ALARM_SOUND", "Playback stopped (player+tts)")
    }

    private fun cancelNotification(actionId: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(notificationIdForAction(actionId))
        } catch (_: Throwable) {
        }
    }

    private fun notificationIdForAction(actionId: Int): Int = (NOTIF_ID_BASE + actionId).coerceAtLeast(NOTIF_ID_BASE + 1)

    companion object {
        private const val CHANNEL_ID = "ALARM_CHANNEL"
        private const val NOTIF_ID_BASE = 10_000
        private const val AUTO_STOP_MS = 25_000L
        private const val ACTION_STOP = "com.example.sonya_front.PENDING_ACTION_STOP" // backward compat
        private const val ACTION_DONE = "com.example.sonya_front.PENDING_ACTION_DONE"
        private const val ACTION_SNOOZE = "com.example.sonya_front.PENDING_ACTION_SNOOZE" // backward compat (+5)
        private const val ACTION_SNOOZE_30 = "com.example.sonya_front.PENDING_ACTION_SNOOZE_30" // backward compat (+30)

        const val ACTION_SNOOZE_15 = "com.example.sonya_front.PENDING_ACTION_SNOOZE_15"
        const val ACTION_SNOOZE_60 = "com.example.sonya_front.PENDING_ACTION_SNOOZE_60"
        const val ACTION_SNOOZE_180 = "com.example.sonya_front.PENDING_ACTION_SNOOZE_180"
    }
}

