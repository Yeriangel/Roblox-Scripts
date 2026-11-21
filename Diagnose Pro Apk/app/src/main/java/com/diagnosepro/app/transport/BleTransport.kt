package com.diagnosepro.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Bluetooth Low Energy transport for BLE ELM327 adapters (Vgate iCar Pro BLE, Veepeak BLE,
 * OBDLink CX, many "V-LINK" clones).
 *
 * BLE adapters expose a UART-like GATT service: one characteristic we write commands to and
 * one that sends notifications with the response. Known layouts are tried first, otherwise any
 * service with a notify + write characteristic pair is used.
 */
@SuppressLint("MissingPermission") // Permissions are checked before a connection is attempted.
class BleTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val address: String,
) : BaseTransport() {

    override val type = TransportType.BLE

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var open = false
    override val isOpen get() = open

    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var mtuDeferred: CompletableDeferred<Int>? = null
    private var descriptorDeferred: CompletableDeferred<Boolean>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private val writeMutex = Mutex()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectDeferred?.complete(false)
                if (open) {
                    open = false
                    closeIncoming(TransportClosedException("BLE disconnected (status $status)"))
                }
                runCatching { g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtuDeferred?.complete(if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            // Responses can be split across many notifications; the ELM layer re-assembles
            // them until the '>' prompt arrives.
            deliver(value)
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                c.value?.let { deliver(it.copyOf()) }
            }
        }
    }

    override suspend fun open() {
        val bt = adapter ?: throw TransportOpenException(OpenFailure.BLUETOOTH_OFF, "No Bluetooth")
        if (!bt.isEnabled) throw TransportOpenException(OpenFailure.BLUETOOTH_OFF, "Bluetooth is off")
        val device: BluetoothDevice = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw TransportOpenException(OpenFailure.DEVICE_NOT_FOUND, "Bad address", e)
        }
        try {
            connectDeferred = CompletableDeferred()
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val connected = withTimeoutOrNull(15_000) { connectDeferred!!.await() } ?: false
            if (!connected) fail(OpenFailure.TIMEOUT, "GATT connect failed")
            val g = gatt!!

            servicesDeferred = CompletableDeferred()
            if (!g.discoverServices()) fail(OpenFailure.SOCKET_FAILED, "discoverServices refused")
            if (withTimeoutOrNull(10_000) { servicesDeferred!!.await() } != true) fail(OpenFailure.TIMEOUT, "No services")

            mtuDeferred = CompletableDeferred()
            if (g.requestMtu(247)) mtu = withTimeoutOrNull(3_000) { mtuDeferred!!.await() } ?: 23

            val (w, n) = findCharacteristics(g.services) ?: fail(OpenFailure.NO_OBD_SERVICE, "No ELM327 GATT service")
            writeChar = w; notifyChar = n
            enableNotifications(g, n)
            open = true
        } catch (e: SecurityException) {
            close()
            throw TransportOpenException(OpenFailure.PERMISSION, "Permission", e)
        } catch (e: TransportOpenException) {
            close()
            throw e
        }
    }

    private fun fail(reason: OpenFailure, msg: String): Nothing = throw TransportOpenException(reason, msg)

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return // some adapters notify without a CCCD
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        descriptorDeferred = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        withTimeoutOrNull(3_000) { descriptorDeferred!!.await() }
    }

    /** Picks (write, notify) characteristics: known services first, then auto-detection. */
    private fun findCharacteristics(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (known in KNOWN_LAYOUTS) {
            val s = services.firstOrNull { it.uuid == known.service } ?: continue
            val w = s.getCharacteristic(known.write)
            val n = s.getCharacteristic(known.notify)
            if (w != null && n != null) return w to n
        }
        // Auto-detect: skip the standard GAP/GATT/Device-info services.
        for (s in services.filterNot { it.uuid.toString().lowercase() in IGNORED_SERVICES }) {
            val n = s.characteristics.firstOrNull { it.properties and (PROP_NOTIFY or PROP_INDICATE) != 0 } ?: continue
            val w = s.characteristics.firstOrNull { it.properties and (PROP_WRITE or PROP_WRITE_NR) != 0 } ?: continue
            return w to n
        }
        return null
    }

    override suspend fun write(data: ByteArray) {
        val g = gatt; val c = writeChar
        if (!open || g == null || c == null) throw TransportClosedException("not open")
        writeMutex.withLock {
            val noResponse = c.properties and PROP_WRITE_NR != 0
            val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // Split by MTU (ATT payload = MTU - 3).
            for (chunk in data.toList().chunked((mtu - 3).coerceAtLeast(20))) {
                val bytes = chunk.toByteArray()
                writeDeferred = CompletableDeferred()
                val ok = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        c.writeType = type
                        @Suppress("DEPRECATION")
                        c.value = bytes
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(c)
                    }
                } catch (e: SecurityException) { false }
                if (!ok) throw TransportClosedException("BLE write rejected")
                // Wait for the write callback (also fires for no-response writes on most stacks).
                withTimeoutOrNull(if (noResponse) 300 else 2_000) { writeDeferred!!.await() }
            }
        }
    }

    override fun close() {
        open = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        closeIncoming(null)
    }

    private data class Layout(val service: UUID, val write: UUID, val notify: UUID)

    companion object {
        private fun u16(s: String) = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
        private val CCCD = u16("2902")
        private const val PROP_NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        private const val PROP_INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
        private const val PROP_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        private const val PROP_WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        val KNOWN_SERVICE_UUIDS: List<UUID> get() = KNOWN_LAYOUTS.map { it.service }.distinct()

        private val KNOWN_LAYOUTS = listOf(
            Layout(u16("fff0"), u16("fff2"), u16("fff1")),   // most BLE ELM327 clones, OBDLink CX
            Layout(u16("ffe0"), u16("ffe1"), u16("ffe1")),   // HM-10 style UART
            Layout(u16("18f0"), u16("2af1"), u16("2af0")),   // Vgate iCar / Veepeak
            Layout(                                             // Vgate iCar Pro BLE4.0
                UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            ),
        )
        private val IGNORED_SERVICES = setOf(
            "00001800-0000-1000-8000-00805f9b34fb", "00001801-0000-1000-8000-00805f9b34fb",
            "0000180a-0000-1000-8000-00805f9b34fb", "0000180f-0000-1000-8000-00805f9b34fb",
        )
    }
}
