package com.diagnosepro.app.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** Thrown when the link to the adapter is lost (unplugged, out of range, BT turned off). */
class TransportClosedException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** Thrown when opening the link fails. [reason] is used to show a helpful message. */
class TransportOpenException(val reason: OpenFailure, message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

enum class OpenFailure { BLUETOOTH_OFF, PERMISSION, DEVICE_NOT_FOUND, SOCKET_FAILED, NO_OBD_SERVICE, TIMEOUT }

enum class TransportType { CLASSIC, BLE, WIFI, DEMO }

/**
 * Byte pipe to an ELM327-compatible adapter. Implementations: Bluetooth Classic SPP, BLE GATT,
 * and an in-process simulator. Incoming bytes are pushed to [incoming] as they arrive; the
 * ELM layer accumulates them until it sees the '>' prompt.
 */
interface ObdTransport {
    val type: TransportType
    val incoming: ReceiveChannel<ByteArray>
    val isOpen: Boolean

    /** Opens the link. Throws [TransportOpenException]. */
    suspend fun open()

    /** Writes bytes. Throws [TransportClosedException] if the link is gone. */
    suspend fun write(data: ByteArray)

    /** Closes the link. Safe to call multiple times. */
    fun close()
}

/** Shared helper: unlimited inbound buffer, closed with a cause when the link drops. */
abstract class BaseTransport : ObdTransport {
    protected val rx = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = rx

    protected fun deliver(bytes: ByteArray) {
        if (bytes.isNotEmpty()) rx.trySend(bytes)
    }

    protected fun closeIncoming(cause: Throwable?) {
        rx.close(cause ?: TransportClosedException("closed"))
    }
}
