package com.diagnosepro.app.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class BtDevice(
    val address: String,
    val name: String,
    val type: TransportType,
    val paired: Boolean,
    val rssi: Int? = null,
) {
    val looksLikeObd: Boolean get() = BluetoothHelper.looksLikeObd(name)
}

@Singleton
@SuppressLint("MissingPermission") // every public method checks hasPermissions() first
class BluetoothHelper @Inject constructor(@ApplicationContext private val context: Context) {

    val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isSupported get() = adapter != null
    val isEnabled get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    private val _scanResults = MutableStateFlow<List<BtDevice>>(emptyList())
    val scanResults: StateFlow<List<BtDevice>> = _scanResults
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun pairedDevices(): List<BtDevice> {
        if (!hasPermissions()) return emptyList()
        return try {
            adapter?.bondedDevices.orEmpty().map { d ->
                BtDevice(d.address, d.name ?: d.address, typeOf(d), paired = true)
            }.sortedByDescending { it.looksLikeObd }
        } catch (e: SecurityException) { emptyList() }
    }

    private fun typeOf(d: BluetoothDevice): TransportType =
        if (d.type == BluetoothDevice.DEVICE_TYPE_LE) TransportType.BLE else TransportType.CLASSIC

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                    d?.let { add(it, rssi) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (!bleScanning) _scanning.value = false
            }
        }
    }
    private var receiverRegistered = false
    private var bleScanning = false

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result.device, result.rssi, forceBle = true)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { add(it.device, it.rssi, true) }
        override fun onScanFailed(errorCode: Int) { bleScanning = false }
    }

    private fun add(d: BluetoothDevice, rssi: Int?, forceBle: Boolean = false) {
        val name = try { d.name } catch (e: SecurityException) { null } ?: return // unnamed devices are noise
        val type = if (forceBle && d.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && d.type != BluetoothDevice.DEVICE_TYPE_DUAL) TransportType.BLE else typeOf(d)
        val paired = try { d.bondState == BluetoothDevice.BOND_BONDED } catch (e: SecurityException) { false }
        val dev = BtDevice(d.address, name, type, paired, rssi)
        _scanResults.value = (_scanResults.value.filterNot { it.address == dev.address } + dev)
            .sortedWith(compareByDescending<BtDevice> { it.looksLikeObd }.thenByDescending { it.rssi ?: -200 })
    }

    /** Classic discovery + BLE scan in parallel for ~12 s. */
    fun startScan() {
        if (!hasPermissions() || !isEnabled) return
        val bt = adapter ?: return
        _scanResults.value = emptyList()
        _scanning.value = true
        try {
            if (!receiverRegistered) {
                val f = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
            }
            bt.startDiscovery()
            bt.bluetoothLeScanner?.let {
                it.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), bleCallback)
                bleScanning = true
            }
        } catch (e: SecurityException) {
            _scanning.value = false
        }
    }

    fun stopScan() {
        try {
            adapter?.cancelDiscovery()
            if (bleScanning) adapter?.bluetoothLeScanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        bleScanning = false
        _scanning.value = false
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private val obdNamePatterns = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI", "OBDLINK", "CAN", "KW9", "SCAN", "V-CHECK")

        fun looksLikeObd(name: String): Boolean = name.uppercase().let { n -> obdNamePatterns.any { n.contains(it) } }

        fun requiredPermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
