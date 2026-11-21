package com.diagnosepro.app.transport

import com.diagnosepro.app.obd.Pids
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * In-process ELM327 simulator used by Demo mode and by unit tests.
 *
 * It speaks the real text protocol (AT commands, '>' prompt, CAN ISO-TP framing with or
 * without headers), so the whole stack — parser, decoders, polling, clear-codes flow — is
 * exercised exactly as with a real adapter. Values change realistically over time:
 * a warm-up curve for coolant and a repeating city drive cycle for speed/RPM.
 */
class DemoTransport(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, // relative time only
    private val latencyMs: Long = 25,
) : BaseTransport() {

    override val type = TransportType.DEMO
    @Volatile private var open = false
    override val isOpen get() = open

    private var echo = true
    private var headers = false
    private var spaces = true
    private var protocolSearched = false
    private val start = clock()
    private val rnd = Random(42)
    private val pending = StringBuilder()

    // Fault state
    private val storedEngine = mutableListOf("P0301", "P0420")
    private val storedTrans = mutableListOf("P0715")
    private val pendingEngine = mutableListOf("P0171")
    private val permanentEngine = mutableListOf("P0420")
    private var cleared = false
    private var clearedAt = 0L

    private val simulatedPids = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15,
        0x1C, 0x1F, 0x21, 0x2E, 0x2F, 0x31, 0x33, 0x3C, 0x41, 0x42, 0x44, 0x46, 0x49, 0x5C, 0x5E, 0x62, 0x63, 0xA6,
    )

    override suspend fun open() {
        delay(300)
        open = true
    }

    override suspend fun write(data: ByteArray) {
        if (!open) throw TransportClosedException("demo closed")
        pending.append(String(data, Charsets.US_ASCII))
        while (true) {
            val idx = pending.indexOf("\r")
            if (idx < 0) break
            val cmd = pending.substring(0, idx).trim().uppercase().replace(" ", "")
            pending.delete(0, idx + 1)
            if (cmd.isEmpty()) continue
            val answer = handle(cmd)
            delay(latencyMs)
            val sb = StringBuilder()
            if (echo) sb.append(cmd).append('\r')
            sb.append(answer).append("\r\r>")
            // Deliver in small pieces like a real serial link / BLE notifications.
            sb.toString().toByteArray(Charsets.US_ASCII).toList().chunked(20).forEach { deliver(it.toByteArray()) }
        }
    }

    override fun close() {
        open = false
        closeIncoming(null)
    }

    // ------------------------------------------------------------------------------------

    private fun handle(cmd: String): String {
        if (cmd.startsWith("AT")) return handleAt(cmd.substring(2))
        if (!cmd.all { it.isDigit() || it in 'A'..'F' } || cmd.length < 2) return "?"
        var prefix = ""
        if (!protocolSearched) { protocolSearched = true; prefix = "SEARCHING...\r" }
        val mode = cmd.substring(0, 2).toInt(16)
        val pid = if (cmd.length >= 4) cmd.substring(2, 4).toInt(16) else -1
        return prefix + when (mode) {
            0x01 -> mode01(pid)
            0x02 -> mode02(pid)
            0x03 -> dtcResponse(0x43, storedEngine, storedTrans)
            0x07 -> dtcResponse(0x47, pendingEngine, emptyList())
            0x0A -> dtcResponse(0x4A, permanentEngine, emptyList())
            0x04 -> {
                storedEngine.clear(); storedTrans.clear(); pendingEngine.clear()
                cleared = true; clearedAt = clock()
                frame("7E8", listOf(0x44)) + "\r" + frame("7E9", listOf(0x44))
            }
            0x06 -> mode06(pid)
            0x09 -> mode09(pid)
            // Read-data-by-id (used by custom PIDs): answer any id with the RPM word.
            0x22 -> if (cmd.length >= 6) frame("7E8", listOf(0x62, cmd.substring(2, 4).toInt(16), cmd.substring(4, 6).toInt(16)) + word((rpm() * 4).toInt())) else "?"
            else -> "NO DATA"
        }
    }

    private fun handleAt(c: String): String = when {
        c == "Z" -> { echo = true; headers = false; spaces = true; protocolSearched = false; "\r\rELM327 v2.2" }
        c == "WS" -> "\r\rELM327 v2.2"
        c == "I" -> "ELM327 v2.2"
        c == "@1" -> "OBDII to RS232 Interpreter"
        c == "E0" -> { echo = false; "OK" }
        c == "E1" -> { echo = true; "OK" }
        c == "H0" -> { headers = false; "OK" }
        c == "H1" -> { headers = true; "OK" }
        c == "S0" -> { spaces = false; "OK" }
        c == "S1" -> { spaces = true; "OK" }
        c == "RV" -> "%.1fV".format(java.util.Locale.US, if (cleared || seconds() > 2) 14.1 + rnd.nextDouble(-0.1, 0.1) else 12.6)
        c == "DPN" -> "A6"
        c == "DP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        c == "PPS" -> "00:FF F  01:FF F  02:FF F"
        c.startsWith("SP") || c.startsWith("TP") || c.startsWith("ST") || c.startsWith("AT") ||
            c.startsWith("L") || c.startsWith("M") || c == "D" || c.startsWith("CAF") || c.startsWith("SH") -> "OK"
        else -> "?"
    }

    private fun seconds() = (clock() - start) / 1000.0

    // ---- Vehicle simulation ----
    private fun speedKmh(): Double {
        // 90-second city cycle: idle, accelerate to ~95 km/h, cruise, brake, idle.
        val t = seconds() % 90
        return when {
            t < 10 -> 0.0
            t < 30 -> (t - 10) / 20.0 * 95
            t < 55 -> 95 + 5 * sin(t / 3)
            t < 75 -> 95 * (1 - (t - 55) / 20.0)
            else -> 0.0
        }.coerceAtLeast(0.0)
    }

    private fun rpm(): Double {
        val v = speedKmh()
        if (v < 1) return 780 + 30 * sin(seconds() * 2) + rnd.nextDouble(-15.0, 15.0)
        val gearRatio = when { v < 20 -> 110.0; v < 40 -> 65.0; v < 60 -> 45.0; v < 80 -> 35.0; else -> 28.0 }
        return (900 + v * gearRatio).coerceAtMost(6200.0)
    }

    private fun coolant() = min(91.0, 35 + seconds() * 0.6) + rnd.nextDouble(-0.4, 0.4)
    private fun throttle(): Double {
        val t = seconds() % 90
        return when { t in 10.0..30.0 -> 45.0; t in 30.0..55.0 -> 20.0; else -> 14.0 }
    }
    private fun load() = (throttle() * 1.2 + 15).coerceAtMost(100.0)
    private fun maf() = rpm() / 1000.0 * (2.0 + throttle() / 10.0)

    private fun mode01(pid: Int): String {
        if (pid < 0) return "?"
        if (pid % 0x20 == 0) return supportedBitmask(pid)
        if (pid !in simulatedPids) return "NO DATA"
        val data: List<Int> = when (pid) {
            0x01 -> monitorBytes(includeMil = true)
            0x41 -> monitorBytes(includeMil = false)
            0x03 -> listOf(0x02, 0x00)
            0x04 -> listOf(pct(load()))
            0x05 -> listOf((coolant() + 40).toInt())
            0x06 -> listOf((128 + 3 + 4 * sin(seconds())).toInt())
            0x07 -> listOf(128 + 9) // +7% long term trim (lean, matches P0171)
            0x0B -> listOf((30 + throttle() * 0.6).toInt())
            0x0C -> word((rpm() * 4).toInt())
            0x0D -> listOf(speedKmh().toInt())
            0x0E -> listOf(((12 + throttle() / 5 + 64) * 2).toInt())
            0x0F -> listOf((28 + 40))
            0x10 -> word((maf() * 100).toInt())
            0x11 -> listOf(pct(throttle()))
            0x13 -> listOf(0x33)
            0x14 -> listOf((0.45 + 0.4 * sin(seconds() * 5)).let { (it * 200).toInt().coerceIn(0, 255) }, 0xFF)
            0x15 -> listOf(140, 0xFF)
            0x1C -> listOf(0x06) // EOBD
            0x1F -> word(seconds().toInt())
            0x21 -> word(if (cleared) 0 else 152)
            0x2F -> listOf(pct(max(5.0, 62 - seconds() / 120)))
            0x31 -> word(if (cleared) ((clock() - clearedAt) / 60_000).toInt() else 1840)
            0x33 -> listOf(101)
            0x2E -> listOf(pct(12.0))
            0x3C -> word(((min(540.0, 150 + seconds() * 3)) + 40).toInt() * 10)
            0x44 -> word(32768) // lambda 1.000
            0x49 -> listOf(pct(throttle() * 0.9))
            0x62 -> listOf((load() * 0.8).toInt() + 125)
            0x63 -> word(250)
            0x42 -> word(((14.1 + rnd.nextDouble(-0.08, 0.08)) * 1000).toInt())
            0x46 -> listOf(27 + 40)
            0x5C -> listOf((min(102.0, 30 + seconds() * 0.5) + 40).toInt())
            0x5E -> word((maf() * 3600 / 14.7 / 740 * 20).toInt())
            0xA6 -> { val km10 = 1_234_567L + (seconds() * 0.25).toLong(); listOf((km10 shr 24).toInt() and 255, (km10 shr 16).toInt() and 255, (km10 shr 8).toInt() and 255, km10.toInt() and 255) }
            else -> return "NO DATA"
        }
        return frame("7E8", listOf(0x41, pid) + data)
    }

    private fun monitorBytes(includeMil: Boolean): List<Int> {
        val codes = storedEngine.size + storedTrans.size
        val mil = storedEngine.isNotEmpty()
        val a = if (includeMil) ((if (mil) 0x80 else 0) or codes) else 0
        // B: misfire/fuel/components supported (bits 0-2), all complete unless cleared
        val b = 0x07 or (if (cleared) 0x30 else 0x00)
        // C: catalyst, heated cat? no; EVAP, O2, O2 heater, EGR supported
        val c = 0b1110_0101
        // D: incomplete flags — EVAP not ready even before clear; everything after clear
        val d = if (cleared) c else 0b0000_0100
        return listOf(a, b, c, d)
    }

    private fun supportedBitmask(base: Int): String {
        val bytes = IntArray(4)
        for (p in (base + 1)..(base + 0x20)) {
            val has = p in simulatedPids || (p == base + 0x20 && simulatedPids.any { it > base + 0x20 })
            if (has) {
                val i = p - base - 1
                bytes[i / 8] = bytes[i / 8] or (0x80 shr (i % 8))
            }
        }
        if (bytes.all { it == 0 } && base > 0) return "NO DATA"
        return frame("7E8", listOf(0x41, base) + bytes.toList())
    }

    private fun mode02(pid: Int): String {
        if (cleared) return "NO DATA" // freeze frame is erased by Mode 04
        val data: List<Int> = when (pid) {
            0x00 -> return frame("7E8", listOf(0x42, 0x00, 0x00, 0b0101_1000, 0b0011_1000, 0x80, 0x00)) // 02,04,05,0B,0C,0D,0E
            0x02 -> listOf(0x03, 0x01) // DTC P0301 caused the freeze frame
            0x04 -> listOf(pct(38.0))
            0x05 -> listOf(88 + 40)
            0x0B -> listOf(45)
            0x0C -> word(2150 * 4)
            0x0D -> listOf(57)
            0x0E -> listOf((14 + 64) * 2)
            else -> return "NO DATA"
        }
        return frame("7E8", listOf(0x42, pid, 0x00) + data)
    }

    private fun mode06(mid: Int): String = when (mid) {
        0x00 -> frame("7E8", listOf(0x46, 0x00, 0x80, 0x00, 0x00, 0x01)) // MID 01 + next range
        0x20 -> frame("7E8", listOf(0x46, 0x20, 0x80, 0x00, 0x00, 0x00)) // MID 21
        0x01 -> frame("7E8", listOf(0x46, 0x01, 0x01, 0x0A, 0x0B, 0x8F, 0x0A, 0x00, 0x0E, 0x00,
            0x01, 0x02, 0x0A, 0x01, 0xC2, 0x00, 0x00, 0x03, 0x00))
        0x21 -> frame("7E8", listOf(0x46, 0x21, 0x80, 0x01, 0x00, 0x52, 0x00, 0x00, 0x00, 0x64))
        else -> "NO DATA"
    }

    private fun mode09(pid: Int): String = when (pid) {
        0x00 -> frame("7E8", listOf(0x49, 0x00, 0x54, 0x40, 0x00, 0x00)) // 02, 04, 06, 0A
        0x02 -> frame("7E8", listOf(0x49, 0x02, 0x01) + "WVWZZZ1KZ9W123456".map { it.code })
        0x04 -> frame("7E8", listOf(0x49, 0x04, 0x01) + "DEMO-CAL-0012345".map { it.code })
        0x06 -> frame("7E8", listOf(0x49, 0x06, 0x01, 0x1A, 0x2B, 0x3C, 0x4D))
        0x0A -> frame("7E8", listOf(0x49, 0x0A, 0x01) + "ECM-EngineControl\u0000\u0000\u0000".map { it.code })
        else -> "NO DATA"
    }

    private fun dtcResponse(service: Int, engine: List<String>, trans: List<String>): String {
        fun bytesFor(codes: List<String>) = listOf(service, codes.size) + codes.flatMap { dtcBytes(it) }
        val parts = mutableListOf(frame("7E8", bytesFor(engine)))
        if (service == 0x43) parts += frame("7E9", bytesFor(trans))
        return parts.joinToString("\r")
    }

    private fun dtcBytes(code: String): List<Int> {
        val letter = "PCBU".indexOf(code[0])
        val a = (letter shl 6) or ((code[1] - '0') shl 4) or code[2].digitToInt(16)
        val b = code.substring(3, 5).toInt(16)
        return listOf(a, b)
    }

    private fun pct(v: Double) = (v * 255 / 100).toInt().coerceIn(0, 255)
    private fun word(v: Int) = listOf((v shr 8) and 0xFF, v and 0xFF)

    // ---- CAN formatting (ISO 15765-4, 11-bit) ----
    private fun hex(b: Int) = "%02X".format(b)
    private fun join(bytes: List<String>) = if (spaces) bytes.joinToString(" ") else bytes.joinToString("")

    /** Formats a response the way a real ELM327 prints it for the current AT settings. */
    internal fun frame(ecu: String, payload: List<Int>): String {
        if (payload.size <= 7) {
            return if (headers) join(listOf(ecu, hex(payload.size)) + payload.map(::hex))
            else join(payload.map(::hex))
        }
        // Multi-frame (ISO-TP): first frame 6 bytes, consecutive frames 7 bytes each
        val lines = mutableListOf<String>()
        val first = payload.take(6)
        val rest = payload.drop(6).chunked(7)
        if (headers) {
            lines += join(listOf(ecu, hex(0x10 or (payload.size shr 8)), hex(payload.size and 0xFF)) + first.map(::hex))
            rest.forEachIndexed { i, chunk ->
                val padded = chunk + List(7 - chunk.size) { 0x00 }
                lines += join(listOf(ecu, hex(0x20 or ((i + 1) and 0x0F))) + padded.map(::hex))
            }
        } else {
            lines += "%03X".format(payload.size)
            lines += "0: " + join(first.map(::hex))
            rest.forEachIndexed { i, chunk ->
                val padded = chunk + List(7 - chunk.size) { 0x00 }
                lines += "${(i + 1) % 16}: " + join(padded.map(::hex))
            }
        }
        return lines.joinToString("\r")
    }
}
