package com.diagnosepro.app.obd

import com.diagnosepro.app.elm327.ElmError
import com.diagnosepro.app.elm327.ElmResponseParser
import com.diagnosepro.app.elm327.ElmResult
import com.diagnosepro.app.elm327.Elm327
import com.diagnosepro.app.elm327.QueryResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class DtcReport(
    val stored: List<Dtc>,
    val pending: List<Dtc>,
    val permanent: List<Dtc>,
    val monitor: ObdDecoders.MonitorStatus?,
    /** Errors per kind (e.g. permanent codes not supported by older cars). */
    val errors: Map<DtcKind, ElmError> = emptyMap(),
) {
    val all get() = stored + pending + permanent
    val milOn get() = monitor?.milOn ?: false
    /** True if nothing could be read at all (link problem / car off). */
    val failed get() = errors.size == 3 && monitor == null
}

data class ClearResponse(val success: Boolean, val error: ElmError?)

data class FreezeFrame(val dtc: String?, val values: List<Pair<PidDef, Double>>)

data class VehicleInfo(
    val vin: String?,
    val calibrationIds: List<Pair<String, String>>,
    val cvns: List<Pair<String, String>>,
    val ecuNames: List<Pair<String, String>>,
)

/**
 * High level generic OBD2 operations on top of [Elm327]. Read-only, except [clearDtcs] (Mode 04).
 */
class ObdService(val elm: Elm327) {

    val isCan: Boolean get() = elm.protocol?.isCan ?: true

    /** Walks 0100, 0120, 0140 ... as long as the "next range supported" bit is set. */
    suspend fun readSupportedPids(mode: Int = 0x01): Set<Int> {
        val out = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val cmd = if (mode == 0x02) "02%02X00".format(base) else "%02X%02X".format(mode, base)
            var msgs = elm.query(cmd).messagesOrEmpty()
            // Mode 02 answers carry a frame-number byte after the PID: "42 00 00 BE 1F ..."
            if (mode == 0x02) msgs = msgs.map { m -> if (m.bytes.size >= 7) m.copy(bytes = m.bytes.copyOfRange(0, 2) + m.bytes.copyOfRange(3, m.bytes.size)) else m }
            val set = ObdDecoders.decodeSupportedPids(msgs, base, 0x40 + mode)
            if (set.isEmpty()) break
            out += set
            if ((base + 0x20) !in set) break
            base += 0x20
        }
        return out
    }

    suspend fun readPid(def: PidDef): Double? {
        if (def.key == Pids.KEY_ADAPTER_VOLTAGE) return readVoltage()
        val r = elm.query(def.command)
        val data = ObdDecoders.mode01Data(r.messagesOrEmpty(), def.pid) ?: return null
        return Pids.decode(def, data)
    }

    suspend fun readVoltage(): Double? =
        (elm.send("ATRV") as? ElmResult.Data)?.let { ElmResponseParser.parseVoltage(it.lines) }

    suspend fun readMonitorStatus(pid: Int = 0x01): ObdDecoders.MonitorStatus? {
        val r = elm.query("01%02X".format(pid))
        return ObdDecoders.mode01Data(r.messagesOrEmpty(), pid)?.let { ObdDecoders.decodeMonitorStatus(it) }
    }

    /**
     * Reads one DTC list. Headers are switched on so we can tell which ECU reported each code;
     * multi-frame (ISO-TP) answers are re-assembled per ECU.
     */
    suspend fun readDtcs(kind: DtcKind): Pair<List<Dtc>, ElmError?> = elm.batch {
        val cmd = when (kind) { DtcKind.STORED -> "03"; DtcKind.PENDING -> "07"; DtcKind.PERMANENT -> "0A" }
        try {
            setHeaders(true)
            when (val r = query(cmd, 5_000)) {
                is QueryResult.Success -> ObdDecoders.decodeDtcs(r.messages, kind, isCan) to null
                // NO DATA for Mode 03/07 simply means "no codes" on many ECUs.
                is QueryResult.Failure -> if (r.error == ElmError.NO_DATA) emptyList<Dtc>() to null else emptyList<Dtc>() to r.error
            }
        } finally {
            // Must run even if the caller was cancelled, or the adapter stays in headers mode.
            withContext(NonCancellable) { setHeaders(false) }
        }
    }

    suspend fun readAllDtcs(): DtcReport {
        val monitor = readMonitorStatus(0x01)
        val errors = mutableMapOf<DtcKind, ElmError>()
        val lists = DtcKind.entries.associateWith { kind ->
            val (codes, err) = readDtcs(kind)
            if (err != null) errors[kind] = err
            codes
        }
        return DtcReport(lists.getValue(DtcKind.STORED), lists.getValue(DtcKind.PENDING), lists.getValue(DtcKind.PERMANENT), monitor, errors)
    }

    /**
     * Mode 04: clears stored/pending DTCs, freeze frame and readiness monitors, turns the MIL off.
     * A positive answer is "44". Some ECUs need longer than usual to respond.
     */
    suspend fun clearDtcs(): ClearResponse = when (val r = elm.query("04", 8_000)) {
        is QueryResult.Success -> if (ObdDecoders.isClearSuccess(r.messages)) ClearResponse(true, null) else ClearResponse(false, ElmError.GARBAGE)
        is QueryResult.Failure -> ClearResponse(false, r.error)
    }

    /** Mode 02 frame 0: which DTC triggered the snapshot and the supported values. */
    suspend fun readFreezeFrame(): FreezeFrame? {
        val dtcData = ObdDecoders.mode02Data(elm.query("020200").messagesOrEmpty(), 0x02)
        val supported = readSupportedPids(0x02).ifEmpty { Pids.all.map { it.pid }.filter { it in 0x04..0x11 }.toSet() }
        val values = mutableListOf<Pair<PidDef, Double>>()
        for (def in Pids.all) {
            if (def.pid !in supported) continue
            val d = ObdDecoders.mode02Data(elm.query("02%02X00".format(def.pid)).messagesOrEmpty(), def.pid) ?: continue
            Pids.decode(def, d)?.let { values += def to it }
        }
        val dtc = dtcData?.takeIf { it.size >= 2 && (it[0] != 0 || it[1] != 0) }?.let { ObdDecoders.dtcFromBytes(it[0], it[1]) }
        if (dtc == null && values.isEmpty()) return null
        return FreezeFrame(dtc, values)
    }

    suspend fun readVehicleInfo(): VehicleInfo = elm.batch {
        try {
            setHeaders(true)
            val vin = ObdDecoders.decodeVin(query("0902", 5_000).messagesOrEmpty(), isCan)
            val cal = ObdDecoders.decodeCalibrationIds(query("0904", 5_000).messagesOrEmpty(), isCan)
            val cvn = ObdDecoders.decodeCvns(query("0906", 5_000).messagesOrEmpty(), isCan)
            val names = ObdDecoders.decodeEcuNames(query("090A", 5_000).messagesOrEmpty(), isCan)
            VehicleInfo(vin, cal, cvn, names)
        } finally {
            // Must run even if the caller was cancelled, or the adapter stays in headers mode.
            withContext(NonCancellable) { setHeaders(false) }
        }
    }

    suspend fun readVin(): String? = elm.batch {
        try {
            setHeaders(true)
            ObdDecoders.decodeVin(query("0902", 5_000).messagesOrEmpty(), isCan)
        } finally {
            // Must run even if the caller was cancelled, or the adapter stays in headers mode.
            withContext(NonCancellable) { setHeaders(false) }
        }
    }

    /** Mode 06 on-board monitoring test results. Only the CAN format is supported. */
    suspend fun readMode06(): List<ObdDecoders.Mode06Result>? {
        if (!isCan) return null
        val mids = readSupportedPids(0x06)
        if (mids.isEmpty()) return emptyList()
        val out = mutableListOf<ObdDecoders.Mode06Result>()
        for (mid in mids.sorted()) {
            if (mid % 0x20 == 0) continue
            out += ObdDecoders.decodeMode06(elm.query("06%02X".format(mid), 4_000).messagesOrEmpty())
        }
        return out
    }
}
