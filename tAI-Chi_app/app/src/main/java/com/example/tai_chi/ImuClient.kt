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
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Connects to the two XIAO nRF52840 IMU boards (advertising as "tAIChi-L" and "tAIChi-R", see
 * XIAOinterfaceAppTablet/XIAOinterfaceAppTablet.ino) over Bluetooth LE and exposes their latest readings as Compose state.
 * The boards do not need to be paired in Android's Bluetooth settings; the app scans for them.
 *
 * Each reading's [Reading.timeNs] is when the board took the sample, converted to tablet time
 * (SystemClock.elapsedRealtimeNanos, the same clock as camera frames) by one [ClockSync] per board.
 */
@SuppressLint("MissingPermission")   // hasPermission() is checked before every Bluetooth call
class ImuClient(context: Context) {
    enum class Side { LEFT, RIGHT }

    /**
     * Accelerometer in g, gyroscope in degrees per second.
     * [timeNs]: when the board took the sample, in tablet time (SystemClock.elapsedRealtimeNanos).
     * [seq]: the board's sample number, or -1 from old firmware that sends no timestamps.
     * [arrivalNs]: when the packet reached the tablet (elapsedRealtimeNanos).
     * [boardTicks]: the board's own clock when it took the sample (1024 per second), or -1 from old firmware.
     * [offsetNs]: the clock offset used to convert board time to tablet time (0 from old firmware).
     */
    data class Reading(
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val timeNs: Long, val seq: Int,
        val arrivalNs: Long, val boardTicks: Long, val offsetNs: Long,
    )

    /**
     * Called for every reading, on a Bluetooth thread, as soon as it arrives (used to record sessions).
     * Must return quickly: hand the work to another thread.
     */
    @Volatile var onReading: ((Side, Reading) -> Unit)? = null

    /**
     * [clock]: one line about time sync for the debug readout, or null before any data.
     * [battery]: the band's battery, or null if not known yet. [batterySupported] is false for bands
     * running firmware that does not report the battery.
     */
    data class State(
        val status: String,
        val reading: Reading? = null,
        val clock: String? = null,
        val battery: Battery? = null,
        val batterySupported: Boolean = true,
    )

    val left = mutableStateOf(State("Searching for tAIChi-L…"))
    val right = mutableStateOf(State("Searching for tAIChi-R…"))

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val gatts = mutableMapOf<Side, BluetoothGatt>()   // main thread only
    private var running = false
    private var scanning = false

    // Per board: clock sync and lost-packet counting. Only touched from that board's Bluetooth callbacks.
    private class Link {
        val clock = ClockSync()
        var lastSeq = -1
        var lost = 0L
        fun reset() { clock.reset(); lastSeq = -1; lost = 0 }
    }
    private val links = mapOf(Side.LEFT to Link(), Side.RIGHT to Link())

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
                links.getValue(side).reset()   // the board may have restarted since last time
                // Shorter Bluetooth intervals mean less delay, so a sharper clock-offset estimate.
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else {
                links.getValue(side).reset()
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
        // The arrival time is read first thing, before anything else can delay it.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val arrivalNs = SystemClock.elapsedRealtimeNanos()
            publish(side, value, arrivalNs)
        }

        // ...older versions put it on the characteristic.
        @Deprecated("Used on Android 12 and below")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val arrivalNs = SystemClock.elapsedRealtimeNanos()
            characteristic.value?.let { publish(side, it, arrivalNs) }
        }
    }

    private fun publish(side: Side, data: ByteArray, arrivalNs: Long) {
        if (data.size < 12) return
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val link = links.getValue(side)
        val timeNs: Long
        val seq: Int
        val clockText: String
        var ticks = -1L
        var offsetNs = 0L
        if (data.size >= 18) {
            // Bytes 12-15: board tick count when sampled (uint32). Bytes 16-17: sample number (uint16).
            ticks = b.getInt(12).toLong() and 0xFFFF_FFFFL
            seq = b.getShort(16).toInt() and 0xFFFF
            timeNs = link.clock.toTabletNs(ticks, arrivalNs)
            offsetNs = link.clock.offsetNs
            if (link.lastSeq >= 0) link.lost += ((seq - link.lastSeq - 1) and 0xFFFF).toLong()
            link.lastSeq = seq
            clockText = clockLine(link)
        } else {
            // Old 12-byte firmware: no board time, so fall back to the arrival time.
            timeNs = arrivalNs
            seq = -1
            clockText = "No timestamps (old firmware)"
        }
        // Bytes 18-19 (newest firmware): battery millivolts in bits 0-14, charging in bit 15.
        // 0 mV means the board has not measured it yet.
        val battery = if (data.size >= 20) {
            val raw = b.getShort(18).toInt() and 0xFFFF
            val mv = raw and 0x7FFF
            if (mv > 0) Battery(mv, raw and 0x8000 != 0) else null
        } else null
        val batterySupported = data.size >= 20
        val r = Reading(
            b.getShort(0) / 1000f, b.getShort(2) / 1000f, b.getShort(4) / 1000f,
            b.getShort(6) / 10f, b.getShort(8) / 10f, b.getShort(10) / 10f,
            timeNs, seq, arrivalNs, ticks, offsetNs
        )
        onReading?.invoke(side, r)
        main.post { stateOf(side).value = State("Connected", r, clockText, battery, batterySupported) }
    }

    private fun clockLine(link: Link): String {
        val c = link.clock
        if (!c.settled) return "Syncing clock…"
        val drift = c.driftPpm?.let { "drift %+.0f ppm".format(it) } ?: "drift: measuring…"
        return "Clock synced · $drift · delay %.0f ms · lost ${link.lost}".format(c.lastDelayNs / 1e6)
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a1c0001-5a1e-4c3b-9d00-7a1c1e5d0001")
        val DATA_UUID: UUID = UUID.fromString("7a1c0002-5a1e-4c3b-9d00-7a1c1e5d0001")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
