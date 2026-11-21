package com.diagnosepro.app.elm327

/**
 * Error conditions an ELM327 can report in place of data.
 * See the ELM327 data sheet, "Messages and Alerts".
 */
enum class ElmError {
    NO_DATA,            // ECU did not answer (PID not supported, or ignition off)
    UNABLE_TO_CONNECT,  // Adapter could not find any OBD protocol (car off / not OBD2)
    CAN_ERROR,          // CAN bus problem (wrong protocol, wiring)
    BUS_INIT_ERROR,     // ISO 9141 / KWP slow init failed
    BUS_ERROR,          // generic bus failure
    STOPPED,            // command interrupted by a new character
    UNKNOWN_COMMAND,    // "?" — adapter does not understand the command
    BUFFER_FULL,        // adapter RX buffer overflowed
    DATA_ERROR,         // checksum / framing errors
    LOW_VOLTAGE,        // "LV RESET" — supply voltage dropped
    TIMEOUT,            // no ">" prompt received in time
    GARBAGE,            // unreadable response
}

sealed class ElmResult {
    /** Cleaned, non-empty response lines (echo, SEARCHING..., BUS INIT OK removed). */
    data class Data(val lines: List<String>) : ElmResult()
    data class Error(val error: ElmError, val raw: String) : ElmResult()

    val isOk get() = this is Data
}

/**
 * Converts the raw text the ELM327 sends before its ">" prompt into a [ElmResult].
 * Pure function, no Android dependencies, heavily unit-tested.
 */
object ElmResponseParser {

    private val errorPatterns: List<Pair<Regex, ElmError>> = listOf(
        Regex("NO\\s*DATA") to ElmError.NO_DATA,
        Regex("UNABLE\\s*TO\\s*CONNECT") to ElmError.UNABLE_TO_CONNECT,
        Regex("CAN\\s*ERROR") to ElmError.CAN_ERROR,
        Regex("BUS\\s*INIT.*ERROR") to ElmError.BUS_INIT_ERROR,
        Regex("BUS\\s*(ERROR|BUSY)") to ElmError.BUS_ERROR,
        Regex("FB\\s*ERROR") to ElmError.BUS_ERROR,
        Regex("STOPPED") to ElmError.STOPPED,
        Regex("BUFFER\\s*FULL") to ElmError.BUFFER_FULL,
        Regex("(<\\s*)?DATA\\s*ERROR|<RX\\s*ERROR|RX\\s*ERROR") to ElmError.DATA_ERROR,
        Regex("LV\\s*RESET") to ElmError.LOW_VOLTAGE,
        Regex("ERR\\d\\d") to ElmError.BUS_ERROR,
        Regex("ACT\\s*ALERT") to ElmError.STOPPED,
    )

    /**
     * @param raw text received from the adapter (may include the trailing '>').
     * @param command the command that was sent; used to strip a possible echo line.
     */
    fun parse(raw: String, command: String? = null): ElmResult {
        val text = raw.replace(">", "").replace('\u0000', ' ')
        // ELM uses '\r' as line terminator; some clones send "\r\n".
        val rawLines = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        val cmdNorm = command?.replace(" ", "")?.uppercase()
        val lines = rawLines
            .filterIndexed { idx, line ->
                // Echo line (only if ATE0 did not take effect yet)
                !(idx == 0 && cmdNorm != null && line.replace(" ", "").uppercase() == cmdNorm)
            }
            .map { stripPrefixNoise(it) }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) {
            return if (rawLines.isEmpty()) ElmResult.Error(ElmError.TIMEOUT, raw)
            // Only echo / "SEARCHING..." / "OK" from bus init — treat as empty answer
            else ElmResult.Error(ElmError.NO_DATA, raw)
        }

        val joined = lines.joinToString(" ").uppercase()
        if (lines.size == 1 && lines[0] == "?") return ElmResult.Error(ElmError.UNKNOWN_COMMAND, raw)
        for ((re, err) in errorPatterns) {
            if (re.containsMatchIn(joined)) {
                // A multi-ECU answer can contain one good line plus "NO DATA" from another
                // module; keep the good lines in that case.
                val good = lines.filter { l -> errorPatterns.none { it.first.containsMatchIn(l.uppercase()) } }
                return if (err == ElmError.NO_DATA && good.isNotEmpty() && good.all { isHexLine(it) }) {
                    ElmResult.Data(good)
                } else ElmResult.Error(err, raw)
            }
        }
        return ElmResult.Data(lines)
    }

    /** Removes "SEARCHING...", "BUS INIT: ...OK" and similar progress text. */
    private fun stripPrefixNoise(line: String): String {
        var l = line
        l = l.replace(Regex("^SEARCHING\\.*", RegexOption.IGNORE_CASE), "").trim()
        l = l.replace(Regex("^BUS\\s*INIT:?\\s*\\.*\\s*OK", RegexOption.IGNORE_CASE), "").trim()
        // Some adapters print "BUS INIT: ..." on one line and "OK"/data after.
        if (l.matches(Regex("^BUS\\s*INIT:?\\s*\\.*$", RegexOption.IGNORE_CASE))) return ""
        return l
    }

    /** True for a line that contains only hex digits, spaces and an optional "N:" frame index. */
    fun isHexLine(line: String): Boolean =
        line.replace(" ", "").matches(Regex("^([0-9A-Fa-f]{1,2}:)?[0-9A-Fa-f]+$"))

    /** Parses "12.4V" from ATRV. */
    fun parseVoltage(lines: List<String>): Double? =
        lines.firstNotNullOfOrNull { Regex("(\\d{1,2}(?:\\.\\d{1,2})?)\\s*V", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)?.toDoubleOrNull()
}
