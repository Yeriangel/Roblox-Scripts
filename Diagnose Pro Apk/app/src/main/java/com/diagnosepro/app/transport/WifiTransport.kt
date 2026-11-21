package com.diagnosepro.app.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Wi-Fi ELM327 adapters: the adapter is a Wi-Fi access point and exposes the ELM327 serial
 * stream on a plain TCP port (almost always 192.168.0.10:35000).
 *
 * The adapter's network has no internet, so Android normally routes new sockets over mobile
 * data instead. We therefore bind the socket to the Wi-Fi network explicitly. This is the only
 * use of the network permission: the app never contacts the internet.
 */
class WifiTransport(
    private val host: String,
    private val port: Int,
    private val context: Context? = null,
) : BaseTransport() {

    override val type = TransportType.WIFI
    private var socket: Socket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    @Volatile private var open = false
    override val isOpen get() = open

    private fun newSocket(): Socket {
        val cm = context?.getSystemService(ConnectivityManager::class.java) ?: return Socket()
        @Suppress("DEPRECATION") // allNetworks is the simplest way to find the (non-default) Wi-Fi network
        val wifi = runCatching {
            cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrNull()
        return wifi?.socketFactory?.createSocket() ?: Socket()
    }

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = try {
            // Note: no apply{} here — inside it, `port` would mean the socket's own (unset) port.
            val sock = newSocket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(this@WifiTransport.host, this@WifiTransport.port), CONNECT_TIMEOUT_MS)
            sock
        } catch (e: SocketTimeoutException) {
            throw TransportOpenException(OpenFailure.TIMEOUT, "Wi-Fi connect timed out", e)
        } catch (e: IOException) {
            throw TransportOpenException(OpenFailure.SOCKET_FAILED, "Wi-Fi connect failed", e)
        } catch (e: IllegalArgumentException) {
            throw TransportOpenException(OpenFailure.DEVICE_NOT_FOUND, "Bad address", e)
        } catch (e: SecurityException) {
            throw TransportOpenException(OpenFailure.PERMISSION, "Permission", e)
        }
        socket = s
        open = true
        readerJob = scope.launch {
            val buf = ByteArray(1024)
            try {
                val input = s.getInputStream()
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("EOF")
                    if (n > 0) deliver(buf.copyOf(n))
                }
            } catch (e: Throwable) {
                open = false
                closeIncoming(TransportClosedException("Wi-Fi link lost", e))
            }
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket
        if (s == null || !open) throw TransportClosedException("not open")
        try {
            s.getOutputStream().write(data)
            s.getOutputStream().flush()
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
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
        private const val CONNECT_TIMEOUT_MS = 8_000

        /** "192.168.0.10:35000" → (host, port); the port is optional. */
        fun parseAddress(address: String): Pair<String, Int>? {
            val a = address.trim()
            if (a.isEmpty()) return null
            val host = a.substringBefore(':').trim()
            val port = if (a.contains(':')) a.substringAfter(':').trim().toIntOrNull() ?: return null else DEFAULT_PORT
            if (host.isEmpty() || port !in 1..65535 || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            return host to port
        }
    }
}
