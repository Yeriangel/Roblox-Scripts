package com.diagnosepro.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic RFCOMM/SPP transport — used by most cheap ELM327 adapters.
 */
@SuppressLint("MissingPermission") // Permissions are checked before a connection is attempted.
class ClassicSppTransport(
    private val adapter: BluetoothAdapter?,
    private val address: String,
) : BaseTransport() {

    override val type = TransportType.CLASSIC
    private var socket: BluetoothSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    @Volatile private var open = false
    override val isOpen get() = open

    override suspend fun open() = withContext(Dispatchers.IO) {
        val bt = adapter ?: throw TransportOpenException(OpenFailure.BLUETOOTH_OFF, "No Bluetooth")
        if (!bt.isEnabled) throw TransportOpenException(OpenFailure.BLUETOOTH_OFF, "Bluetooth is off")
        val device = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw TransportOpenException(OpenFailure.DEVICE_NOT_FOUND, "Bad address", e)
        } catch (e: SecurityException) {
            throw TransportOpenException(OpenFailure.PERMISSION, "Permission", e)
        }
        // Discovery slows down / breaks RFCOMM connects.
        runCatching { bt.cancelDiscovery() }

        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            // Some clones do not publish an SDP record — connect to channel 1 directly (hidden API).
            {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var last: Throwable? = null
        for (make in attempts) {
            val s = try { make() } catch (e: SecurityException) {
                throw TransportOpenException(OpenFailure.PERMISSION, "Permission", e)
            } catch (e: Throwable) { last = e; continue }
            try {
                s.connect()
                socket = s
                break
            } catch (e: IOException) {
                last = e
                runCatching { s.close() }
            } catch (e: SecurityException) {
                runCatching { s.close() }
                throw TransportOpenException(OpenFailure.PERMISSION, "Permission", e)
            }
        }
        val s = socket ?: throw TransportOpenException(OpenFailure.SOCKET_FAILED, "Could not open socket", last)
        open = true
        readerJob = scope.launch {
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("EOF")
                    if (n > 0) deliver(buf.copyOf(n))
                }
            } catch (e: Throwable) {
                open = false
                closeIncoming(TransportClosedException("Classic link lost", e))
            }
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket
        if (s == null || !open) throw TransportClosedException("not open")
        try {
            s.outputStream.write(data)
            s.outputStream.flush()
        } catch (e: IOException) {
            open = false
            throw TransportClosedException("write failed", e)
        }
    }

    override fun close() {
        open = false
        runCatching { socket?.close() }
        socket = null
        readerJob?.cancel()
        scope.cancel()
        closeIncoming(null)
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
