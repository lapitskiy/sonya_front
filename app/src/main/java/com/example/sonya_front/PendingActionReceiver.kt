package com.example.sonya_front

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class PendingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val id = intent.getIntExtra(EXTRA_ACTION_ID, -1)
        if (id <= 0) return

        when (action) {
            ACTION_FIRE -> {
                // Mark handled early to avoid duplicates if multiple alarms fire close together.
                PendingActionStore.markHandled(context, id)

                val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
                val label = intent.getStringExtra(EXTRA_TTS).orEmpty().ifBlank { "Событие" }
                val normalizedType = type.lowercase()

                if (normalizedType == "timer") {
                    // Classic "timer": we don't keep it as ringing in UI; it auto-stops (service has auto-stop).
                    ActiveActionsStore.remove(context, id)
                    PendingActionsScheduler.cancelCountdownNotification(context, id)
                } else {
                    // text-timer / alarm: keep as ringing until user presses Done or Snooze.
                    ActiveActionsStore.upsert(
                        context,
                        ActiveActionsStore.ActiveAction(
                            actionId = id,
                            type = type.ifBlank { "unknown" },
                            label = label,
                            triggerAtEpochMs = System.currentTimeMillis(),
                            scheduledAtEpochMs = System.currentTimeMillis(),
                            durationMs = null,
                            state = "ringing",
                        )
                    )
                    PendingActionsScheduler.cancelCountdownNotification(context, id)
                }

                val svc = Intent(context, PendingActionForegroundService::class.java).apply {
                    this.action = ACTION_FIRE
                    putExtras(intent.extras ?: return)
                }

                Log.i("ALARM", "Fired action_id=$id -> starting ForegroundService")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            }
            ACTION_CANCEL -> {
                Log.i("SCHED", "Cancel requested from notification action_id=$id")
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
                if (deviceId.isNotBlank()) {
                    PendingActionsScheduler.cancelWithAck(context, deviceId, id)
                } else {
                    PendingActionsScheduler.cancel(context, id)
                }
            }
            else -> return
        }
    }

    companion object {
        const val ACTION_FIRE = "com.example.sonya_front.PENDING_ACTION_FIRE"
        const val ACTION_CANCEL = "com.example.sonya_front.PENDING_ACTION_CANCEL"

        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TTS = "tts"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_VIBRATION = "vibration"
    }
}

