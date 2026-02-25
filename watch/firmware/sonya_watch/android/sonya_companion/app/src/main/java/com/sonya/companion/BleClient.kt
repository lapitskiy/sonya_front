package com.sonya.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.concurrent.atomic.AtomicBoolean

class BleClient(
    private val ctx: Context,
    private val onLog: (String) -> Unit,
    private val onFrame: (Frame) -> Unit,
) {
    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter = btManager.adapter
    private val scanner get() = btAdapter.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    private val scanning = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun scanByName(name: String) {
        if (scanning.getAndSet(true)) return
        onLog("Scan: start (name contains '$name')")

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleUuids.SVC))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val dn = dev.name ?: ""
                if (dn.contains(name, ignoreCase = true)) {
                    onLog("Scan: found ${dev.address} name='$dn' rssi=${result.rssi}")
                    scanner.stopScan(this)
                    scanning.set(false)
                    connect(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning.set(false)
                onLog("Scan failed: $errorCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        onLog("Connecting to ${device.address} ...")
        gatt?.close()
        gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        onLog("Disconnect")
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxChar = null
        txChar = null
    }

    @SuppressLint("MissingPermission")
    fun writeRxAscii(cmd: String) {
        val g = gatt ?: return
        val ch = rxChar ?: return
        val data = cmd.toByteArray(Charsets.UTF_8)
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = data
        val ok = g.writeCharacteristic(ch)
        onLog(">> RX '$cmd' (${data.size} bytes) ok=$ok")
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onLog("GATT state: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("Discovering services...")
                gatt.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            onLog("Services discovered: status=$status")
            val svc = gatt.getService(BleUuids.SVC)
            if (svc == null) {
                onLog("Service not found: ${BleUuids.SVC}")
                return
            }
            rxChar = svc.getCharacteristic(BleUuids.RX)
            txChar = svc.getCharacteristic(BleUuids.TX)
            if (rxChar == null || txChar == null) {
                onLog("RX/TX characteristics not found")
                return
            }

            enableNotify(gatt, txChar!!)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            val f = Protocol.parseFrame(value) ?: return
            onFrame(f)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val ok = gatt.setCharacteristicNotification(ch, true)
        onLog("Enable notify setCharacteristicNotification ok=$ok")

        val cccd = ch.getDescriptor(UUID_CCCD)
        if (cccd == null) {
            onLog("CCCD not found")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val wrote = gatt.writeDescriptor(cccd)
        onLog("Enable notify writeDescriptor ok=$wrote")
    }

    companion object {
        val UUID_CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val REQUIRED_PERMS: Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()
    }
}

