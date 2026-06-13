package com.example.sonya_front

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.sonya_front.AppLog as Log

object WatchBackendResultSender {
    private const val MAX_ATTEMPTS = 6
    private const val RETRY_DELAY_MS = 700L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun send(ctx: Context, ok: Boolean) {
        val appCtx = ctx.applicationContext
        val cmd = if (ok) "UI:OK" else "UI:ERR"
        Log.i(SonyaWatchProtocol.TAG, "watch backend result -> $cmd")
        sendAttempt(appCtx, cmd, attempt = 1)
    }

    private fun sendAttempt(ctx: Context, cmd: String, attempt: Int) {
        val client = SonyaWatchBleManager.getClient(ctx)
        if (!client.isConnected()) {
            Log.i(SonyaWatchProtocol.TAG, "watch backend result skipped: BLE not connected cmd=$cmd")
            return
        }
        if (!client.isGattReady()) {
            if (attempt >= MAX_ATTEMPTS) {
                Log.w(SonyaWatchProtocol.TAG, "watch backend result dropped: GATT not ready cmd=$cmd attempts=$attempt")
                return
            }
            Log.i(SonyaWatchProtocol.TAG, "watch backend result wait GATT cmd=$cmd attempt=$attempt")
            mainHandler.postDelayed({ sendAttempt(ctx, cmd, attempt + 1) }, RETRY_DELAY_MS)
            return
        }

        Log.i(SonyaWatchProtocol.TAG, "watch backend result send cmd=$cmd attempt=$attempt")
        client.writeAsciiCommand(cmd)
    }
}
