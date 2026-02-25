package com.example.sonya_front

import android.content.Context

object UserSettingsStore {
    private const val PREFS = "user_settings"
    private const val KEY_VIBRATE_ON_CONFIRM = "vibrate_on_confirm"
    private const val KEY_WAKE_LISTENING_ENABLED = "wake_listening_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getVibrateOnConfirm(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_VIBRATE_ON_CONFIRM, true)
    }

    fun setVibrateOnConfirm(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VIBRATE_ON_CONFIRM, value).apply()
    }

    fun getWakeListeningEnabled(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_WAKE_LISTENING_ENABLED, true)
    }

    fun setWakeListeningEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WAKE_LISTENING_ENABLED, value).apply()
    }
}

