package com.example.sonya_front

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SonyaWatchBleClient(
    private val appCtx: Context,
    private val onLog: (String) -> Unit,
    private val onConnectedChanged: (Boolean) -> Unit,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onNotifyBytes: (ByteArray) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs = appCtx.getSharedPreferences("sonya_watch_ble", Context.MODE_PRIVATE)
    private val prefKeyLastAddr = "last_addr"

    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    @Volatile private var connected = false
    @Volatile private var scanning = false
    private val connecting = AtomicBoolean(false)
    private val scanSessionId = AtomicInteger(0)
    private val scanLoggedAddrs = HashSet<String>()
    private var scanOtherLogBudget = 20
    @Volatile private var autoEnabled = false
    private var autoIntervalMs: Long = 15_000L
    private var autoScanWindowMs: Long = 6_000L
    private var autoTickRunnable: Runnable? = null
    private var scanStopRunnable: Runnable? = null
    private var connectTimeoutRunnable: Runnable? = null

    private val writeQueue = ArrayDeque<ByteArray>(64)
    private var writeInFlight = false
    private var writeOkCount: Long = 0
    private var writeFailCount: Long = 0
    private var writeLastLogAtMs: Long = 0L

    fun isConnected(): Boolean = connected
    fun isScanning(): Boolean = scanning

    fun isAutoEnabled(): Boolean = autoEnabled

    fun setAutoConnectEnabled(
        enabled: Boolean,
        intervalMs: Long = 15_000L,
        scanWindowMs: Long = 6_000L,
    ) {
        autoIntervalMs = intervalMs
        autoScanWindowMs = scanWindowMs
        if (autoEnabled == enabled) return
        autoEnabled = enabled
        if (!enabled) {
            cancelAutoRunnables()
            stopScanIfRunning(reason = "auto_disabled")
            // Do NOT disconnect an active connection here; user may want to keep it.
            log("auto: disabled")
            return
        }
        log("auto: enabled (interval=${autoIntervalMs}ms window=${autoScanWindowMs}ms)")
        kickAutoConnectNow()
    }

    fun kickAutoConnectNow() {
        if (!autoEnabled) return
        mainHandler.post { autoTick() }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(stopAuto: Boolean = true) {
        // User explicit disconnect should stop auto-reconnect to avoid "fighting" the UI.
        if (stopAuto) {
            autoEnabled = false
            cancelAutoRunnables()
        }

        // Invalidate any in-flight scan callbacks and pending "connect once" gate.
        scanSessionId.incrementAndGet()
        connecting.set(false)
        try {
            scannerCallback?.let { cb ->
                getAdapter()?.bluetoothLeScanner?.stopScan(cb)
            }
        } catch (_: Throwable) {
        } finally {
            scannerCallback = null
            setScanning(false)
        }

        try {
            gatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        service = null
        rxChar = null
        txChar = null
        writeQueue.clear()
        writeInFlight = false
        setConnected(false)
        log("disconnect(): done")
    }

    @SuppressLint("MissingPermission")
    fun scanAndConnect(force: Boolean = true) {
        val adapter = getAdapter()
        if (adapter == null) {
            log("Bluetooth adapter is null")
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is disabled")
            return
        }

        if (!force && (connected || connecting.get())) {
            log("scanAndConnect(force=false): already connected/connecting")
            return
        }

        if (!hasBlePermissionsForScanAndConnect()) {
            log("scan: missing Bluetooth permissions (SCAN/CONNECT)")
            return
        }

        // Manual button should be a "force reconnect": reset state.
        if (force) {
            disconnect(stopAuto = false)
        } else {
            closeGattState(reason = "scan_restart")
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            log("BLE scanner is null")
            return
        }

        // New scan session: used to ignore stale callbacks.
        scanLoggedAddrs.clear()
        scanOtherLogBudget = 20
        connecting.set(false)
        val session = scanSessionId.incrementAndGet()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (session != scanSessionId.get()) return
                val dev = result.device
                val advName = try { result.scanRecord?.deviceName } catch (_: Throwable) { null }
                val name = (advName ?: safeName(dev))?.trim()
                val serviceUuids = try { result.scanRecord?.serviceUuids } catch (_: Throwable) { null }
                val hasOurService = serviceUuids?.any { it?.uuid == SonyaWatchProtocol.SERVICE_UUID } == true

                if (name != SonyaWatchProtocol.DEVICE_NAME && !hasOurService) {
                    // Limited debug: log a few distinct devices so user can see what's being scanned.
                    val addr = dev.address ?: "?"
                    if (scanOtherLogBudget > 0 && scanLoggedAddrs.add(addr)) {
                        scanOtherLogBudget -= 1
                        val uuidsStr = serviceUuids?.joinToString(",") { it.uuid.toString() } ?: ""
                        log("scan: other name='${name ?: ""}' addr=$addr rssi=${result.rssi} uuids=[$uuidsStr]")
                    }
                    return
                }

                // Make sure we only initiate one connect for this scan session.
                if (!connecting.compareAndSet(false, true)) return

                log("scan: found $name addr=${dev.address}, connecting...")
                saveLastAddr(dev.address)
                try {
                    scanner.stopScan(this)
                } catch (_: Throwable) {
                }
                scannerCallback = null
                setScanning(false)
                scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
                scanStopRunnable = null
                connectGatt(dev)
            }

            override fun onScanFailed(errorCode: Int) {
                if (session != scanSessionId.get()) return
                log("scan failed: errorCode=$errorCode")
                scannerCallback = null
                setScanning(false)
            }
        }

        scannerCallback = cb
        log("scan: start (looking for name=${SonyaWatchProtocol.DEVICE_NAME})")
        try {
            // No ScanFilter: in practice some firmwares don't advertise service UUIDs reliably,
            // and name can be in scan record (not always matched by ScanFilter as expected).
            scanner.startScan(null, settings, cb)
            setScanning(true)
            scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
            scanStopRunnable = Runnable {
                if (session != scanSessionId.get()) return@Runnable
                // Stop scan window if we didn't connect.
                stopScanIfRunning(reason = "scan_window_timeout")
            }
            mainHandler.postDelayed(scanStopRunnable!!, autoScanWindowMs.coerceAtLeast(1500L))
        } catch (se: SecurityException) {
            log("scan: SecurityException (missing BLUETOOTH_SCAN?): ${se.message}")
            scannerCallback = null
            setScanning(false)
        } catch (t: Throwable) {
            log("scan: failed: ${t.javaClass.simpleName}: ${t.message}")
            scannerCallback = null
            setScanning(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun writeAsciiCommand(cmd: String) {
        val g = gatt
        val c = rxChar
        if (g == null || c == null) {
            log("write: not ready (gatt/rx is null)")
            return
        }

        val bytes = cmd.toByteArray(Charsets.US_ASCII)
        log("write RX: '$cmd' (${bytes.size} bytes)")

        mainHandler.post {
            // Backpressure: queue writes so we never spam GATT and get "returned false".
            // Also use Write Without Response (the firmware allows it) for maximum throughput.
            if (writeQueue.size >= 256) {
                writeQueue.clear()
                writeInFlight = false
                log("write RX: queue overflow -> dropped")
                return@post
            }
            writeQueue.addLast(bytes)
            maybeLogWriteStats()
            drainWriteQueue()
        }
    }

    private fun maybeLogWriteStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - writeLastLogAtMs < 1500L) return
        writeLastLogAtMs = now
        log("writeQ: size=${writeQueue.size} inflight=${if (writeInFlight) 1 else 0} ok=$writeOkCount fail=$writeFailCount")
    }

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val c = rxChar ?: return
        val next = writeQueue.pollFirst() ?: return

        writeInFlight = true
        try {
            val ok: Boolean = if (Build.VERSION.SDK_INT >= 33) {
                val status = g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                status == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = next
                g.writeCharacteristic(c)
            }
            if (!ok) {
                writeInFlight = false
                writeFailCount++
                log("write RX: writeCharacteristic returned false (queue=${writeQueue.size})")
                // Put it back at head and retry later.
                writeQueue.addFirst(next)
                mainHandler.postDelayed({ drainWriteQueue() }, 20L)
            } else {
                writeOkCount++
                maybeLogWriteStats()
                // Some stacks may not reliably call onCharacteristicWrite() for NO_RESPONSE.
                // Add a safety release so the queue cannot deadlock.
                mainHandler.postDelayed({
                    if (writeInFlight) {
                        writeInFlight = false
                        drainWriteQueue()
                    }
                }, 200L)
            }
        } catch (se: SecurityException) {
            writeInFlight = false
            log("write RX: SecurityException (missing BLUETOOTH_CONNECT?): ${se.message}")
        } catch (t: Throwable) {
            writeInFlight = false
            log("write RX: failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(dev: BluetoothDevice) {
        try {
            if (!hasBlePermissionsForScanAndConnect()) {
                connecting.set(false)
                log("connectGatt: missing Bluetooth permissions (CONNECT)")
                return
            }
            tryEnsureBond(dev)
            scheduleConnectTimeout()
            val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dev.connectGatt(appCtx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            } else {
                dev.connectGatt(appCtx, false, gattCb)
            }
            gatt = g
        } catch (se: SecurityException) {
            connecting.set(false)
            log("connectGatt: SecurityException (missing BLUETOOTH_CONNECT?): ${se.message}")
        } catch (t: Throwable) {
            connecting.set(false)
            log("connectGatt: failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("gatt: connectionState status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    setConnected(true)
                    connecting.set(false)
                    cancelConnectTimeout()
                    saveLastAddr(gatt.device?.address)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            gatt.requestMtu(247)
                        }
                    } catch (_: Throwable) {
                    }
                    try {
                        gatt.discoverServices()
                    } catch (t: Throwable) {
                        log("discoverServices failed: ${t.message}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    setConnected(false)
                    connecting.set(false)
                    cancelConnectTimeout()
                    try {
                        gatt.close()
                    } catch (_: Throwable) {
                    }
                    if (this@SonyaWatchBleClient.gatt == gatt) {
                        this@SonyaWatchBleClient.gatt = null
                        service = null
                        rxChar = null
                        txChar = null
                    }
                    if (autoEnabled) {
                        scheduleNextAutoTick(1200L)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("gatt: mtuChanged mtu=$mtu status=$status")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            log("gatt: servicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val svc = gatt.getService(SonyaWatchProtocol.SERVICE_UUID)
            if (svc == null) {
                log("gatt: service not found ${SonyaWatchProtocol.SERVICE_UUID}")
                return
            }

            service = svc
            rxChar = svc.getCharacteristic(SonyaWatchProtocol.RX_UUID)
            txChar = svc.getCharacteristic(SonyaWatchProtocol.TX_UUID)

            if (rxChar == null) log("gatt: RX characteristic not found ${SonyaWatchProtocol.RX_UUID}")
            if (txChar == null) log("gatt: TX characteristic not found ${SonyaWatchProtocol.TX_UUID}")

            val tx = txChar ?: return
            enableNotify(gatt, tx)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != SonyaWatchProtocol.RX_UUID) return
            writeInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writeFailCount++
                log("gatt: charWrite status=$status (queue=${writeQueue.size})")
            } else {
                writeOkCount++
            }
            maybeLogWriteStats()
            drainWriteQueue()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = try { descriptor.uuid.toString() } catch (_: Throwable) { "<uuid?>" }
            val chUuid = try { descriptor.characteristic?.uuid?.toString() } catch (_: Throwable) { "<ch?>" }
            log("gatt: descriptorWrite status=$status desc=$uuid ch=$chUuid")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != SonyaWatchProtocol.TX_UUID) return
            val v = characteristic.value ?: return
            mainHandler.post { onNotifyBytes(v) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != SonyaWatchProtocol.TX_UUID) return
            mainHandler.post { onNotifyBytes(value) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        try {
            val ok = gatt.setCharacteristicNotification(ch, true)
            if (!ok) {
                log("notify: setCharacteristicNotification returned false")
            }
        } catch (se: SecurityException) {
            log("notify: SecurityException (missing BLUETOOTH_CONNECT?): ${se.message}")
            return
        } catch (t: Throwable) {
            log("notify: setCharacteristicNotification failed: ${t.message}")
            return
        }

        val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val d: BluetoothGattDescriptor? = try {
            ch.getDescriptor(cccdUuid)
        } catch (_: Throwable) {
            null
        }
        if (d == null) {
            log("notify: CCCD descriptor not found (0x2902)")
            return
        }

        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        try {
            val ok: Boolean = if (Build.VERSION.SDK_INT >= 33) {
                val status = gatt.writeDescriptor(d, value)
                status == BluetoothStatusCodes.SUCCESS
            } else {
                d.value = value
                gatt.writeDescriptor(d)
            }
            log("notify: enable request sent ok=$ok")
        } catch (se: SecurityException) {
            log("notify: SecurityException (missing BLUETOOTH_CONNECT?): ${se.message}")
        } catch (t: Throwable) {
            log("notify: writeDescriptor failed: ${t.message}")
        }
    }

    private fun getAdapter(): BluetoothAdapter? {
        return try {
            val bm = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter
        } catch (t: Throwable) {
            Log.w(SonyaWatchProtocol.TAG, "getAdapter failed: ${t.message}", t)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(dev: BluetoothDevice?): String? {
        if (dev == null) return null
        return try {
            dev.name
        } catch (_: SecurityException) {
            null
        }
    }

    private fun setConnected(v: Boolean) {
        connected = v
        mainHandler.post {
            onConnectedChanged(v)
        }
    }

    private fun setScanning(v: Boolean) {
        if (scanning == v) return
        scanning = v
        mainHandler.post {
            onScanningChanged(v)
        }
    }

    private fun log(msg: String) {
        mainHandler.post { onLog(msg) }
    }

    private fun hasBlePermissionsForScanAndConnect(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val scanOk = appCtx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connOk = appCtx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return scanOk && connOk
    }

    private fun saveLastAddr(addr: String?) {
        val a = addr?.trim().orEmpty()
        if (a.isBlank() || a == "00:00:00:00:00:00") return
        try {
            prefs.edit().putString(prefKeyLastAddr, a).apply()
        } catch (_: Throwable) {
        }
    }

    private fun loadLastAddr(): String {
        return try { prefs.getString(prefKeyLastAddr, "")?.trim().orEmpty() } catch (_: Throwable) { "" }
    }

    private fun autoTick() {
        if (!autoEnabled) return
        if (connected) {
            scheduleNextAutoTick(autoIntervalMs)
            return
        }
        if (connecting.get()) {
            scheduleNextAutoTick(autoIntervalMs)
            return
        }

        val adapter = getAdapter()
        if (adapter == null) {
            log("auto: Bluetooth adapter is null")
            scheduleNextAutoTick(autoIntervalMs)
            return
        }
        if (!adapter.isEnabled) {
            log("auto: Bluetooth is disabled")
            scheduleNextAutoTick(autoIntervalMs)
            return
        }
        if (!hasBlePermissionsForScanAndConnect()) {
            log("auto: missing Bluetooth permissions (SCAN/CONNECT)")
            scheduleNextAutoTick(autoIntervalMs)
            return
        }

        // 1) Try direct connect to last known address.
        val addr = loadLastAddr()
        if (addr.isNotBlank()) {
            try {
                val dev = adapter.getRemoteDevice(addr)
                if (connecting.compareAndSet(false, true)) {
                    closeGattState(reason = "auto_direct_connect")
                    log("auto: direct connect addr=$addr")
                    connectGatt(dev)
                    scheduleNextAutoTick(autoIntervalMs)
                    return
                }
            } catch (se: SecurityException) {
                log("auto: direct connect SecurityException: ${se.message}")
            } catch (t: Throwable) {
                log("auto: direct connect failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        // 2) Otherwise scan in a short window.
        if (scannerCallback != null) {
            scheduleNextAutoTick(autoIntervalMs)
            return
        }
        log("auto: scan+connect")
        scanAndConnect(force = false)
        scheduleNextAutoTick(autoIntervalMs)
    }

    private fun scheduleNextAutoTick(delayMs: Long) {
        if (!autoEnabled) return
        autoTickRunnable?.let { mainHandler.removeCallbacks(it) }
        autoTickRunnable = Runnable { autoTick() }
        mainHandler.postDelayed(autoTickRunnable!!, delayMs.coerceAtLeast(800L))
    }

    private fun cancelAutoRunnables() {
        autoTickRunnable?.let { mainHandler.removeCallbacks(it) }
        autoTickRunnable = null
        scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
        scanStopRunnable = null
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun stopScanIfRunning(reason: String) {
        val cb = scannerCallback ?: return
        try {
            getAdapter()?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Throwable) {
        } finally {
            scannerCallback = null
            scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
            scanStopRunnable = null
        }
        setScanning(false)
        log("scan: stopped ($reason)")
    }

    private fun scheduleConnectTimeout() {
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = Runnable {
            if (!connecting.get() || connected) return@Runnable
            log("connect: timeout -> disconnect")
            closeGattState(reason = "connect_timeout")
            connecting.set(false)
            setConnected(false)
        }
        mainHandler.postDelayed(connectTimeoutRunnable!!, 12_000L)
    }

    private fun cancelConnectTimeout() {
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGattState(reason: String) {
        try {
            gatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        service = null
        rxChar = null
        txChar = null
        writeQueue.clear()
        writeInFlight = false
        cancelConnectTimeout()
        log("gatt: cleared ($reason)")
    }

    @SuppressLint("MissingPermission")
    private fun tryEnsureBond(dev: BluetoothDevice) {
        try {
            // Bonding is optional for BLE; for some devices it improves reconnect stability.
            if (dev.bondState == BluetoothDevice.BOND_BONDED) return
            if (dev.bondState == BluetoothDevice.BOND_BONDING) return
            val ok = dev.createBond()
            if (!ok) {
                log("bond: createBond returned false (may require user confirmation)")
            } else {
                log("bond: createBond requested (system may ask confirmation)")
            }
        } catch (se: SecurityException) {
            log("bond: SecurityException (missing BLUETOOTH_CONNECT?): ${se.message}")
        } catch (t: Throwable) {
            log("bond: failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

