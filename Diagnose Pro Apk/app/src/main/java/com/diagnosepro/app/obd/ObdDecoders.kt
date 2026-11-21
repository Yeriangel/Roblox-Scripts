package com.diagnosepro.app.obd

import com.diagnosepro.app.elm327.EcuMessage

enum class DtcKind { STORED, PENDING, PERMANENT }

data class Dtc(val code: String, val kind: DtcKind, val ecu: String)

/** Decoders for OBD service responses. All inputs are assembled per-ECU messages. */
object ObdDecoders {

    // ---------------- Supported PIDs (01 00 / 20 / 40 ...) ----------------

    /**
     * Bitmask PIDs: for request PID `base` (0x00, 0x20, ...), bit 7 of byte A = PID base+1,
     * ... bit 0 of byte D = PID base+0x20 (which itself says whether the next range exists).
     * Results from several ECUs are OR-ed together.
     */
    fun decodeSupportedPids(messages: List<EcuMessage>, base: Int, service: Int = 0x41): Set<Int> {
        val out = mutableSetOf<Int>()
        for (m in messages) {
            val b = m.bytes
            if (b.size < 6 || b[0] != service || b[1] != base) continue
            for (byteIdx in 0 until 4) {
                for (bit in 0 until 8) {
                    if ((b[2 + byteIdx] shr (7 - bit)) and 1 == 1) out += base + byteIdx * 8 + bit + 1
                }
            }
        }
        return out
    }

    /** Extracts Mode 01 data bytes for one PID from the first ECU that answered. */
    fun mode01Data(messages: List<EcuMessage>, pid: Int): IntArray? {
        val m = messages.firstOrNull { it.bytes.size >= 2 && it.bytes[0] == 0x41 && it.bytes[1] == pid } ?: return null
        return m.bytes.copyOfRange(2, m.bytes.size)
    }

    /** Mode 02 answer "42 PID FRAME data..." → data bytes. */
    fun mode02Data(messages: List<EcuMessage>, pid: Int): IntArray? {
        val m = messages.firstOrNull { it.bytes.size >= 3 && it.bytes[0] == 0x42 && it.bytes[1] == pid } ?: return null
        return m.bytes.copyOfRange(3, m.bytes.size)
    }

    // ---------------- DTCs (03 / 07 / 0A) ----------------

    /** Two bytes → "P0133". Top two bits select the system letter. */
    fun dtcFromBytes(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 0x03]
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        return "%c%d%X%02X".format(letter, d1, d2, b)
    }

    /**
     * @param isCan CAN responses carry a DTC-count byte after the service byte;
     *              legacy protocols send 3 DTC slots per line (padded with 00 00) and no count.
     */
    fun decodeDtcs(messages: List<EcuMessage>, kind: DtcKind, isCan: Boolean): List<Dtc> {
        val service = when (kind) { DtcKind.STORED -> 0x43; DtcKind.PENDING -> 0x47; DtcKind.PERMANENT -> 0x4A }
        val out = LinkedHashSet<Dtc>()
        for (m in messages) {
            val b = m.bytes
            if (b.isEmpty() || b[0] != service) continue
            var i = if (isCan) 2 else 1
            if (isCan && b.size >= 2) {
                // Count byte sanity: if payload length doesn't fit, fall back to no-count parsing
                val count = b[1]
                if (count * 2 + 2 > b.size) i = 1
            }
            while (i + 1 < b.size) {
                val hi = b[i]; val lo = b[i + 1]
                if (hi != 0 || lo != 0) out += Dtc(dtcFromBytes(hi, lo), kind, m.ecu)
                i += 2
            }
        }
        return out.toList()
    }

    /** Mode 04 positive response is "44". */
    fun isClearSuccess(messages: List<EcuMessage>): Boolean = messages.any { it.bytes.isNotEmpty() && it.bytes[0] == 0x44 }

    // ---------------- Monitor status (01 01 / 01 41) ----------------

    enum class Monitor {
        MISFIRE, FUEL_SYSTEM, COMPONENTS,
        CATALYST, HEATED_CATALYST, EVAP, SECONDARY_AIR, AC_REFRIGERANT, O2_SENSOR, O2_HEATER, EGR,
        NMHC_CATALYST, NOX_SCR, BOOST_PRESSURE, EXHAUST_SENSOR, PM_FILTER, EGR_VVT,
    }

    enum class MonitorState { READY, NOT_READY, NOT_SUPPORTED }

    data class MonitorStatus(
        val milOn: Boolean,
        val dtcCount: Int,
        val isDiesel: Boolean,
        val monitors: Map<Monitor, MonitorState>,
    ) {
        val supported get() = monitors.filterValues { it != MonitorState.NOT_SUPPORTED }
        val allReady get() = supported.values.all { it == MonitorState.READY }
        val notReadyCount get() = supported.values.count { it == MonitorState.NOT_READY }
    }

    private val sparkMonitors = listOf(
        Monitor.CATALYST, Monitor.HEATED_CATALYST, Monitor.EVAP, Monitor.SECONDARY_AIR,
        Monitor.AC_REFRIGERANT, Monitor.O2_SENSOR, Monitor.O2_HEATER, Monitor.EGR,
    )
    private val dieselMonitors = listOf(
        Monitor.NMHC_CATALYST, Monitor.NOX_SCR, null, Monitor.BOOST_PRESSURE,
        null, Monitor.EXHAUST_SENSOR, Monitor.PM_FILTER, Monitor.EGR_VVT,
    )

    /**
     * PID 01: A7 = MIL, A0-6 = DTC count. B3 = compression ignition.
     * B0-2 supported (misfire, fuel, components), B4-6 = test NOT complete.
     * C = supported, D = NOT complete for the spark/diesel specific monitors.
     * For PID 41 the same layout is used (byte A is reserved).
     */
    fun decodeMonitorStatus(data: IntArray): MonitorStatus? {
        if (data.size < 4) return null
        val (a, b, c, d) = data
        val diesel = (b shr 3) and 1 == 1
        val map = LinkedHashMap<Monitor, MonitorState>()
        fun st(supported: Boolean, incomplete: Boolean) =
            if (!supported) MonitorState.NOT_SUPPORTED else if (incomplete) MonitorState.NOT_READY else MonitorState.READY
        listOf(Monitor.MISFIRE, Monitor.FUEL_SYSTEM, Monitor.COMPONENTS).forEachIndexed { i, mon ->
            map[mon] = st((b shr i) and 1 == 1, (b shr (i + 4)) and 1 == 1)
        }
        val specific = if (diesel) dieselMonitors else sparkMonitors
        specific.forEachIndexed { bit, mon ->
            if (mon != null) map[mon] = st((c shr bit) and 1 == 1, (d shr bit) and 1 == 1)
        }
        return MonitorStatus(milOn = (a shr 7) and 1 == 1, dtcCount = a and 0x7F, isDiesel = diesel, monitors = map)
    }

    // ---------------- Mode 09 vehicle info ----------------

    /**
     * Collects the data bytes of a Mode 09 answer for one ECU.
     * CAN: "49 PID N data..." (N = number of data items). Legacy: one message per 4-byte chunk:
     * "49 PID SEQ d d d d".
     */
    fun mode09Payload(messages: List<EcuMessage>, pid: Int, isCan: Boolean): Map<String, IntArray> {
        val byEcu = LinkedHashMap<String, MutableList<Int>>()
        for (m in messages) {
            val b = m.bytes
            if (b.size < 3 || b[0] != 0x49 || b[1] != pid) continue
            val list = byEcu.getOrPut(m.ecu) { mutableListOf() }
            // CAN: byte 2 = item count; legacy: byte 2 = message sequence. Both are skipped.
            list.addAll(b.drop(3))
        }
        return byEcu.mapValues { it.value.toIntArray() }
    }

    fun decodeVin(messages: List<EcuMessage>, isCan: Boolean): String? {
        val payload = mode09Payload(messages, 0x02, isCan).values.firstOrNull() ?: return null
        val chars = payload.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
            .replace(" ", "")
        // Some ECUs pad with leading zeros/garbage; the VIN is the last 17 characters.
        val vin = if (chars.length > 17) chars.takeLast(17) else chars
        return vin.takeIf { it.length == 17 && it.all { c -> c.isLetterOrDigit() } }?.uppercase()
    }

    /** Calibration IDs: 16 ASCII chars each. */
    fun decodeCalibrationIds(messages: List<EcuMessage>, isCan: Boolean): List<Pair<String, String>> =
        mode09Payload(messages, 0x04, isCan).flatMap { (ecu, bytes) ->
            bytes.toList().chunked(16).map { chunk ->
                ecu to chunk.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
            }.filter { it.second.isNotEmpty() }
        }

    /** CVN: 4 bytes each, shown as hex. */
    fun decodeCvns(messages: List<EcuMessage>, isCan: Boolean): List<Pair<String, String>> =
        mode09Payload(messages, 0x06, isCan).flatMap { (ecu, bytes) ->
            bytes.toList().chunked(4).filter { it.size == 4 }.map { ecu to it.joinToString("") { b -> "%02X".format(b) } }
        }

    /** ECU name (PID 0A): 20 ASCII bytes, e.g. "ECM-EngineControl". */
    fun decodeEcuNames(messages: List<EcuMessage>, isCan: Boolean): List<Pair<String, String>> =
        mode09Payload(messages, 0x0A, isCan).map { (ecu, bytes) ->
            ecu to bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").replace('\u0000', ' ').trim()
        }.filter { it.second.isNotEmpty() }

    // ---------------- Mode 06 (CAN format) ----------------

    data class Mode06Result(
        val mid: Int, val tid: Int, val unitId: Int,
        val value: Double, val min: Double, val max: Double, val unit: String,
    ) {
        val passed get() = value in min..max
    }

    /**
     * CAN Mode 06: "46 [MID TID UASID VH VL MINH MINL MAXH MAXL]..." — 9-byte records, the
     * MID is repeated in every record. MIDs 00/20/40... are supported-MID bitmasks.
     */
    fun decodeMode06(messages: List<EcuMessage>): List<Mode06Result> {
        val out = mutableListOf<Mode06Result>()
        for (m in messages) {
            val b = m.bytes
            if (b.size < 10 || b[0] != 0x46 || b[1] % 0x20 == 0) continue
            var i = 1
            while (i + 9 <= b.size) {
                val mid = b[i]; val tid = b[i + 1]; val uas = b[i + 2]
                fun word(o: Int) = b[i + o] * 256 + b[i + o + 1]
                val (scale, unit, signed) = uasScale(uas)
                fun conv(w: Int): Double = (if (signed && w > 32767) w - 65536 else w) * scale
                out += Mode06Result(mid, tid, uas, conv(word(3)), conv(word(5)), conv(word(7)), unit)
                i += 9
            }
        }
        return out
    }

    /** Subset of SAE J1979 Unit and Scaling IDs. Returns (scale, unit, signed). */
    fun uasScale(uas: Int): Triple<Double, String, Boolean> = when (uas) {
        0x01 -> Triple(1.0, "", false)
        0x02 -> Triple(0.1, "", false)
        0x03 -> Triple(0.01, "", false)
        0x04 -> Triple(0.001, "", false)
        0x05 -> Triple(0.0000305, "", false)
        0x06 -> Triple(0.000305, "", false)
        0x07 -> Triple(0.25, "rpm", false)
        0x08 -> Triple(0.01, "km/h", false)
        0x09 -> Triple(1.0, "km/h", false)
        0x0A -> Triple(0.122, "mV", false)
        0x0B -> Triple(0.001, "V", false)
        0x0C -> Triple(0.01, "V", false)
        0x0D -> Triple(0.00390625, "mA", false)
        0x0E -> Triple(0.001, "A", false)
        0x0F -> Triple(0.01, "A", false)
        0x10 -> Triple(1.0, "ms", false)
        0x11 -> Triple(100.0, "ms", false)
        0x12 -> Triple(1.0, "s", false)
        0x13 -> Triple(1.0, "mΩ", false)
        0x14 -> Triple(1.0, "Ω", false)
        0x15 -> Triple(1.0, "kΩ", false)
        0x16 -> Triple(0.1, "°C", false)
        0x17 -> Triple(0.01, "kPa", false)
        0x18 -> Triple(0.0117, "kPa", false)
        0x19 -> Triple(0.079, "kPa", false)
        0x1A -> Triple(1.0, "kPa", false)
        0x1B -> Triple(10.0, "kPa", false)
        0x1C -> Triple(0.01, "°", false)
        0x1D -> Triple(0.5, "°", false)
        0x1E -> Triple(0.0000305, "λ", false)
        0x1F -> Triple(0.05, "A/F", false)
        0x20 -> Triple(0.0039062, "", false)
        0x21 -> Triple(1.0, "mHz", false)
        0x22 -> Triple(1.0, "Hz", false)
        0x23 -> Triple(1.0, "kHz", false)
        0x24 -> Triple(1.0, "counts", false)
        0x25 -> Triple(1.0, "km", false)
        0x27 -> Triple(0.01, "g/s", false)
        0x81 -> Triple(1.0, "", true)
        0x82 -> Triple(0.1, "", true)
        0x83 -> Triple(0.01, "", true)
        0x84 -> Triple(0.001, "", true)
        0x8A -> Triple(0.122, "mV", true)
        0x8B -> Triple(0.001, "V", true)
        0x8C -> Triple(0.01, "V", true)
        0x8E -> Triple(0.001, "A", true)
        0x90 -> Triple(1.0, "ms", true)
        0x96 -> Triple(0.1, "°C", true)
        0x9C -> Triple(0.01, "°", true)
        0x9D -> Triple(0.5, "°", true)
        0xA8 -> Triple(1.0, "g/s", true)
        0xA9 -> Triple(0.25, "Pa/s", true)
        0xAF -> Triple(0.01, "%", true)
        else -> Triple(1.0, "", false)
    }
}
