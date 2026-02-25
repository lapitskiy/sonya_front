package com.example.sonya_front

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
    private val onNotifyBytes: (ByteArray) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    @Volatile private var connected = false
    private val connecting = AtomicBoolean(false)
    private val scanSessionId = AtomicInteger(0)
    private val scanLoggedAddrs = HashSet<String>()
    private var scanOtherLogBudget = 20

    private val writeQueue = ArrayDeque<ByteArray>(64)
    private var writeInFlight = false
    private var writeOkCount: Long = 0
    private var writeFailCount: Long = 0
    private var writeLastLogAtMs: Long = 0L

    fun isConnected(): Boolean = connected

    @SuppressLint("MissingPermission")
    fun disconnect() {
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
    fun scanAndConnect() {
        val adapter = getAdapter()
        if (adapter == null) {
            log("Bluetooth adapter is null")
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is disabled")
            return
        }

        disconnect()

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
                try {
                    scanner.stopScan(this)
                } catch (_: Throwable) {
                }
                scannerCallback = null
                connectGatt(dev)
            }

            override fun onScanFailed(errorCode: Int) {
                if (session != scanSessionId.get()) return
                log("scan failed: errorCode=$errorCode")
            }
        }

        scannerCallback = cb
        log("scan: start (looking for name=${SonyaWatchProtocol.DEVICE_NAME})")
        try {
            // No ScanFilter: in practice some firmwares don't advertise service UUIDs reliably,
            // and name can be in scan record (not always matched by ScanFilter as expected).
            scanner.startScan(null, settings, cb)
        } catch (se: SecurityException) {
            log("scan: SecurityException (missing BLUETOOTH_SCAN?): ${se.message}")
        } catch (t: Throwable) {
            log("scan: failed: ${t.javaClass.simpleName}: ${t.message}")
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
            val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dev.connectGatt(appCtx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            } else {
                dev.connectGatt(appCtx, false, gattCb)
            }
            gatt = g
        } catch (se: SecurityException) {
            log("connectGatt: SecurityException (missing BLUETOOTH_CONNECT?): ${se.message}")
        } catch (t: Throwable) {
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

    private fun log(msg: String) {
        mainHandler.post { onLog(msg) }
    }
}

