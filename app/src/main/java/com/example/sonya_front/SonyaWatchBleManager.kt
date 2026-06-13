package com.example.sonya_front

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.sonya_front.AppLog as Log

object SonyaWatchBleManager {
    interface Listener {
        fun onWatchLog(line: String)
        fun onWatchConnectedChanged(connected: Boolean)
        fun onWatchScanningChanged(scanning: Boolean)
        fun onWatchGattReady()
        fun onWatchNotifyBytes(bytes: ByteArray)
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    @Volatile private var client: SonyaWatchBleClient? = null

    fun getClient(ctx: Context): SonyaWatchBleClient {
        val existing = client
        if (existing != null) return existing

        synchronized(lock) {
            val again = client
            if (again != null) return again

            val appCtx = ctx.applicationContext
            val created = SonyaWatchBleClient(
                appCtx = appCtx,
                onLog = { line ->
                    logLine(line)
                },
                onConnectedChanged = { connected ->
                    dispatch { it.onWatchConnectedChanged(connected) }
                },
                onScanningChanged = { scanning ->
                    dispatch { it.onWatchScanningChanged(scanning) }
                },
                onGattReady = {
                    dispatch { it.onWatchGattReady() }
                },
                onNotifyBytes = { bytes ->
                    dispatch { it.onWatchNotifyBytes(bytes) }
                },
            )
            client = created
            return created
        }
    }

    fun registerListener(ctx: Context, listener: Listener): SonyaWatchBleClient {
        val c = getClient(ctx)
        synchronized(lock) {
            listeners.add(listener)
        }
        listener.onWatchConnectedChanged(c.isConnected())
        listener.onWatchScanningChanged(c.isScanning())
        if (c.isGattReady()) {
            listener.onWatchGattReady()
        }
        return c
    }

    fun unregisterListener(listener: Listener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    fun sendBackendResult(ctx: Context, ok: Boolean) {
        val cmd = if (ok) "UI:OK" else "UI:ERR"
        val c = getClient(ctx)
        logLine("watch backend result -> $cmd")
        if (!c.isConnected()) {
            logLine("watch backend result skipped: BLE not connected")
            return
        }
        c.writeAsciiCommand(cmd)
    }

    private fun logLine(line: String) {
        Log.i(SonyaWatchProtocol.TAG, line)
        dispatch { it.onWatchLog(line) }
    }

    private fun dispatch(block: (Listener) -> Unit) {
        mainHandler.post {
            snapshotListeners().forEach(block)
        }
    }

    private fun snapshotListeners(): List<Listener> {
        return synchronized(lock) { listeners.toList() }
    }
}
