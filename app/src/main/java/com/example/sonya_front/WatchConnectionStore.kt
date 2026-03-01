package com.example.sonya_front

import android.content.Context
import android.content.Intent

object WatchConnectionStore {
    const val ACTION_WATCH_CONNECTION_CHANGED = "com.example.sonya_front.WATCH_CONNECTION_CHANGED"
    const val EXTRA_CONNECTED = "connected"

    private const val PREFS = "sonya_watch_state"
    private const val KEY_CONNECTED = "connected"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isConnected(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_CONNECTED, false)
    }

    fun setConnected(ctx: Context, connected: Boolean) {
        try {
            prefs(ctx).edit().putBoolean(KEY_CONNECTED, connected).apply()
        } catch (_: Throwable) {
            // ignore
        }
        try {
            ctx.sendBroadcast(
                Intent(ACTION_WATCH_CONNECTION_CHANGED)
                    .putExtra(EXTRA_CONNECTED, connected)
            )
        } catch (_: Throwable) {
            // ignore
        }
    }
}

