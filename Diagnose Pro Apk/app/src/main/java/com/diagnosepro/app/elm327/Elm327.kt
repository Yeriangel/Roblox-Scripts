package com.diagnosepro.app.elm327

import com.diagnosepro.app.transport.ObdTransport
import com.diagnosepro.app.transport.TransportClosedException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Receives every raw command/response for the developer console. */
fun interface RawLogger {
    fun log(direction: Direction, text: String)
    enum class Direction { TX, RX, INFO }
}

enum class InitStage { RESETTING, CONFIGURING, SEARCHING_PROTOCOL, READING_INFO }

data class AdapterInfo(
    val version: String,
    val protocol: ObdProtocol?,
    val voltage: Double?,
    val likelyClone: Boolean,
    val cloneReason: String?,
)

/** Thrown by [Elm327.initialize] with a reason the UI can explain. */
class ElmInitException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_ELM, NO_VEHICLE, BUS_ERROR }
}

/**
 * ELM327 command layer: strictly one command at a time, waits for the '>' prompt,
 * configurable timeout, retries once on timeout. Thread-safe via a [Mutex].
 */
class Elm327(
    private val transport: ObdTransport,
    private val logger: RawLogger = RawLogger { _, _ -> },
    var defaultTimeoutMs: Long = 2_000,
) {
    private val mutex = Mutex()
    var protocol: ObdProtocol? = null
        private set
    var headersOn: Boolean = false
        private set

    /** Sends [command] and returns the parsed result. Throws [TransportClosedException] on link loss. */
    suspend fun send(command: String, timeoutMs: Long = defaultTimeoutMs, retry: Boolean = true): ElmResult =
        mutex.withLock { sendLocked(command, timeoutMs, retry) }

    /** Runs several commands atomically (e.g. ATH1 → 03 → ATH0) so the poller cannot interleave. */
    suspend fun <T> batch(block: suspend Session.() -> T): T = mutex.withLock { block(Session()) }

    inner class Session {
        suspend fun send(command: String, timeoutMs: Long = defaultTimeoutMs, retry: Boolean = true) =
            sendLocked(command, timeoutMs, retry)

        suspend fun query(command: String, timeoutMs: Long = defaultTimeoutMs) = queryLocked(command, timeoutMs)

        /** Like [query] but keeps negative responses ("7F ..."), which still prove a module is there. */
        suspend fun queryRaw(command: String, timeoutMs: Long = defaultTimeoutMs): List<EcuMessage> =
            when (val r = sendLocked(command, timeoutMs, true)) {
                is ElmResult.Data -> ObdFrames.assemble(r.lines.filter { ElmResponseParser.isHexLine(it) }, protocol, headersOn)
                is ElmResult.Error -> emptyList()
            }

        suspend fun setHeaders(on: Boolean) {
            if (headersOn == on) return
            val r = sendLocked(if (on) "ATH1" else "ATH0", defaultTimeoutMs, true)
            if (r.isOk) headersOn = on
        }
    }

    private suspend fun sendLocked(command: String, timeoutMs: Long, retry: Boolean): ElmResult {
        var result = exchange(command, timeoutMs)
        if (retry && result is ElmResult.Error &&
            (result.error == ElmError.TIMEOUT || result.error == ElmError.STOPPED || result.error == ElmError.GARBAGE)
        ) {
            logger.log(RawLogger.Direction.INFO, "retry $command")
            // Send a bare CR to stop anything in progress, then retry once.
            drain()
            result = exchange(command, timeoutMs)
        }
        return result
    }

    private fun drain() {
        while (true) {
            val r = transport.incoming.tryReceive()
            if (r.isClosed) throw TransportClosedException("closed", r.exceptionOrNull())
            if (r.getOrNull() == null) break
        }
    }

    private suspend fun exchange(command: String, timeoutMs: Long): ElmResult {
        drain()
        logger.log(RawLogger.Direction.TX, command)
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val chunk = try {
                withTimeoutOrNull(remaining) { transport.incoming.receive() }
            } catch (e: ClosedReceiveChannelException) {
                throw TransportClosedException("closed", e)
            } catch (e: TransportClosedException) {
                throw e
            } ?: break
            sb.append(String(chunk, Charsets.ISO_8859_1))
            if (sb.indexOf(">") >= 0) break
            if (sb.length > 64_000) break // garbage protection
        }
        val raw = sb.toString()
        logger.log(RawLogger.Direction.RX, raw.replace("\r", "\\r"))
        if (!raw.contains('>')) {
            return ElmResult.Error(if (raw.isBlank()) ElmError.TIMEOUT else ElmError.GARBAGE, raw)
        }
        val printable = raw.substringBeforeLast('>')
        // Lots of non-printable bytes = wrong baud rate / not an ELM device.
        val nonPrintable = printable.count { it.code < 0x09 || (it.code in 0x0E..0x1F) || it.code > 0x7E }
        if (printable.isNotEmpty() && nonPrintable * 3 > printable.length) {
            return ElmResult.Error(ElmError.GARBAGE, raw)
        }
        return ElmResponseParser.parse(printable, command)
    }

    /**
     * Standard init: ATZ, ATE0, ATL0, ATS0, ATH0, ATAT1, ATSP0, 0100 (forces protocol search), ATDPN.
     */
    suspend fun initialize(onStage: (InitStage) -> Unit = {}): AdapterInfo {
        onStage(InitStage.RESETTING)
        // ATZ performs a full reset; the chip needs up to ~1 s before it prints its banner.
        var reset = send("ATZ", 4_000)
        if (reset !is ElmResult.Data) {
            delay(500)
            reset = send("ATZ", 5_000, retry = false)
        }
        val banner = (reset as? ElmResult.Data)?.lines?.joinToString(" ").orEmpty()
        if (reset !is ElmResult.Data) {
            throw ElmInitException(ElmInitException.Reason.NOT_ELM, "No answer to ATZ")
        }

        onStage(InitStage.CONFIGURING)
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) send(cmd)
        headersOn = false
        send("ATSP0")

        val versionLine = (send("ATI") as? ElmResult.Data)?.lines?.firstOrNull() ?: banner
        val version = Regex("v\\d+\\.\\d+[a-z]?", RegexOption.IGNORE_CASE).find(versionLine)?.value
            ?: Regex("v\\d+\\.\\d+", RegexOption.IGNORE_CASE).find(banner)?.value ?: "?"

        onStage(InitStage.SEARCHING_PROTOCOL)
        // Protocol search can take >10 s on older cars (5-baud init).
        val first = send("0100", 20_000)
        if (first is ElmResult.Error) {
            when (first.error) {
                ElmError.UNABLE_TO_CONNECT, ElmError.NO_DATA, ElmError.TIMEOUT ->
                    throw ElmInitException(ElmInitException.Reason.NO_VEHICLE, first.error.name)
                ElmError.CAN_ERROR, ElmError.BUS_INIT_ERROR, ElmError.BUS_ERROR ->
                    throw ElmInitException(ElmInitException.Reason.BUS_ERROR, first.error.name)
                else -> Unit
            }
        }
        val dpn = (send("ATDPN") as? ElmResult.Data)?.lines?.firstOrNull()
        protocol = ObdProtocol.fromDpn(dpn)

        onStage(InitStage.READING_INFO)
        val voltage = (send("ATRV") as? ElmResult.Data)?.let { ElmResponseParser.parseVoltage(it.lines) }
        val (clone, why) = detectClone(version)
        return AdapterInfo(version, protocol, voltage, clone, why)
    }

    /**
     * Heuristic clone detection. Genuine ELM327 chips never shipped as v1.5, and genuine
     * v1.4+ chips answer AT@1 (device description) and ATPPS (programmable parameters).
     * Clones that claim v2.x but reject these are flagged — they usually still work for basic
     * reading, but can be slow or unstable.
     */
    private suspend fun detectClone(version: String): Pair<Boolean, String?> {
        if (version.equals("v1.5", ignoreCase = true)) return true to "v1.5"
        val desc = send("AT@1", retry = false)
        val pps = send("ATPPS", retry = false)
        val failed = listOf(desc, pps).count { it is ElmResult.Error && it.error == ElmError.UNKNOWN_COMMAND }
        val claimsNew = Regex("v2\\.\\d").containsMatchIn(version)
        return if (claimsNew && failed >= 1) true to "claims $version but rejects AT@1/ATPPS" else false to null
    }

    /** Sends an OBD request and assembles the answer into per-ECU messages. */
    suspend fun query(command: String, timeoutMs: Long = defaultTimeoutMs): QueryResult =
        mutex.withLock { queryLocked(command, timeoutMs) }

    private suspend fun queryLocked(command: String, timeoutMs: Long): QueryResult =
        when (val r = sendLocked(command, timeoutMs, true)) {
            is ElmResult.Data -> {
                val msgs = ObdFrames.assemble(r.lines.filter { ElmResponseParser.isHexLine(it) }, protocol, headersOn)
                // A "7F xx yy" negative response means "service not supported / conditions not correct".
                val positive = msgs.filterNot { it.bytes.firstOrNull() == 0x7F }
                if (positive.isEmpty()) QueryResult.Failure(if (msgs.isNotEmpty()) ElmError.NO_DATA else ElmError.GARBAGE)
                else QueryResult.Success(positive)
            }
            is ElmResult.Error -> QueryResult.Failure(r.error)
        }
}

sealed class QueryResult {
    data class Success(val messages: List<EcuMessage>) : QueryResult()
    data class Failure(val error: ElmError) : QueryResult()

    fun messagesOrEmpty(): List<EcuMessage> = (this as? Success)?.messages ?: emptyList()
}
