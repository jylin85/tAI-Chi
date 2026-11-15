package com.example.tai_chi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Connects to the two XIAO nRF52840 IMU boards (advertising as "tAIChi-L" and "tAIChi-R", see
 * firmware/xiao_imu) over Bluetooth LE and exposes their latest readings as Compose state.
 * The boards do not need to be paired in Android's Bluetooth settings; the app scans for them.
 */
@SuppressLint("MissingPermission")   // hasPermission() is checked before every Bluetooth call
class ImuClient(context: Context) {
    enum class Side { LEFT, RIGHT }

    /** Accelerometer in g, gyroscope in degrees per second. */
    data class Reading(val ax: Float, val ay: Float, val az: Float, val gx: Float, val gy: Float, val gz: Float)

    data class State(val status: String, val reading: Reading? = null)

    val left = mutableStateOf(State("Searching for tAIChi-L…"))
    val right = mutableStateOf(State("Searching for tAIChi-R…"))

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val gatts = mutableMapOf<Side, BluetoothGatt>()   // main thread only
    private var running = false
    private var scanning = false

    fun start() {
        if (running) return
        running = true
        tick()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatts.values.forEach { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatts.clear()
    }

    // Re-checks every few seconds so Bluetooth being switched on, or a board being powered up late, just works.
    private fun tick() {
        if (!running) return
        ensureScanning()
        main.postDelayed(::tick, 3000)
    }

    private fun stateOf(side: Side) = if (side == Side.LEFT) left else right

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureScanning() {
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val problem = when {
            !hasPermission() -> "Bluetooth permission needed"
            adapter == null -> "No Bluetooth on this device"
            !adapter.isEnabled -> "Bluetooth is off"
            else -> null
        }
        if (problem != null) {
            stopScan()
            Side.values().forEach { if (it !in gatts) stateOf(it).value = State(problem) }
            return
        }
        if (gatts.size >= 2) { stopScan(); return }
        if (scanning) return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter!!.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            scanning = true
        } catch (_: Exception) {
            scanning = false
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val name = result.scanRecord?.deviceName ?: return
            val side = when {
                name.endsWith("-L") -> Side.LEFT
                name.endsWith("-R") -> Side.RIGHT
                else -> return
            }
            if (side in gatts) return
            stateOf(side).value = State("Connecting…")
            gatts[side] = result.device.connectGatt(appContext, false, gattCallback(side), BluetoothDevice.TRANSPORT_LE)
            if (gatts.size >= 2) stopScan()
        }

        override fun onScanFailed(errorCode: Int) { scanning = false }
    }

    private fun gattCallback(side: Side) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else {
                main.post {
                    try { gatt.close() } catch (_: Exception) {}
                    if (gatts[side] === gatt) gatts.remove(side)
                    stateOf(side).value = State("Disconnected, searching…")
                    ensureScanning()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(DATA_UUID)
            val cccd = ch?.getDescriptor(CCCD_UUID)
            if (ch == null || cccd == null) {
                main.post { stateOf(side).value = State("Not an IMU board") }
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(ch, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        // Android 13+ delivers the value directly...
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            publish(side, value)
        }

        // ...older versions put it on the characteristic.
        @Deprecated("Used on Android 12 and below")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { publish(side, it) }
        }
    }

    private fun publish(side: Side, data: ByteArray) {
        if (data.size < 12) return
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val r = Reading(
            b.getShort(0) / 1000f, b.getShort(2) / 1000f, b.getShort(4) / 1000f,
            b.getShort(6) / 10f, b.getShort(8) / 10f, b.getShort(10) / 10f
        )
        main.post { stateOf(side).value = State("Connected", r) }
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a1c0001-5a1e-4c3b-9d00-7a1c1e5d0001")
        val DATA_UUID: UUID = UUID.fromString("7a1c0002-5a1e-4c3b-9d00-7a1c1e5d0001")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
