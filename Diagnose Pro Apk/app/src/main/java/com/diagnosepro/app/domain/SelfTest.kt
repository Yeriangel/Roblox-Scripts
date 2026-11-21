package com.diagnosepro.app.domain

import com.diagnosepro.app.elm327.AdapterInfo
import com.diagnosepro.app.obd.DtcKind
import com.diagnosepro.app.obd.ObdService
import com.diagnosepro.app.obd.Pids
import java.util.Locale

enum class TestStepId { ADAPTER, SUPPORTED, LIVE_DATA, SPEED, MONITORS, STORED, PENDING, PERMANENT, FREEZE_FRAME, VEHICLE_INFO, MODE06, VOLTAGE }
enum class TestStatus { PASS, FAIL, NOT_SUPPORTED }

data class TestStep(val id: TestStepId, val status: TestStatus, val detail: String)

/**
 * Read-only end-to-end check against the connected car: runs every read function once and
 * records what happened. It never sends Mode 04 or any other command that changes the car.
 * The details are technical (LTR, English) because the report is meant to be shared for support.
 */
class SelfTest(private val obd: ObdService, private val adapter: AdapterInfo?) {

    suspend fun run(progress: (Int, Int) -> Unit): List<TestStep> {
        val steps = mutableListOf<TestStep>()
        val total = TestStepId.entries.size
        fun add(id: TestStepId, status: TestStatus, detail: String) { steps += TestStep(id, status, detail); progress(steps.size, total) }
        fun ok(b: Boolean) = if (b) TestStatus.PASS else TestStatus.FAIL

        add(TestStepId.ADAPTER, ok(adapter != null),
            "ELM327 ${adapter?.version ?: "?"}, protocol ${adapter?.protocol?.displayName ?: "unknown"}" + if (adapter?.likelyClone == true) ", likely clone (${adapter.cloneReason})" else "")

        val supported = obd.readSupportedPids()
        add(TestStepId.SUPPORTED, ok(supported.isNotEmpty()), "${supported.size} PIDs: " + supported.sorted().joinToString(" ") { "%02X".format(it) })

        // Read every supported PID we know once, with timing.
        val known = Pids.all.filter { it.pid in supported }
        val failed = mutableListOf<String>()
        var totalMs = 0L
        for (def in known) {
            val t0 = System.nanoTime()
            val v = obd.readPid(def)
            totalMs += (System.nanoTime() - t0) / 1_000_000
            if (v == null) failed += "%02X".format(def.pid)
        }
        val avg = if (known.isEmpty()) 0 else totalMs / known.size
        add(TestStepId.LIVE_DATA, ok(known.isNotEmpty() && failed.size <= known.size / 4),
            "${known.size - failed.size}/${known.size} read OK, avg $avg ms per PID" + if (failed.isNotEmpty()) ", no data: ${failed.joinToString(" ")}" else "")

        val rpm = Pids.byKey("rpm")!!.takeIf { it.pid in supported }?.let { obd.readPid(it) }
        val speed = Pids.byKey("speed")!!.takeIf { it.pid in supported }?.let { obd.readPid(it) }
        add(TestStepId.SPEED, ok(rpm != null || speed != null), "RPM ${rpm?.let { fmt(it) } ?: "n/a"}, speed ${speed?.let { fmt(it) } ?: "n/a"} km/h")

        val mon = obd.readMonitorStatus(0x01)
        add(TestStepId.MONITORS, ok(mon != null),
            mon?.let { "MIL ${if (it.milOn) "ON" else "off"}, ${it.dtcCount} codes, ${it.supported.size} monitors (${it.notReadyCount} not ready)${if (it.isDiesel) ", diesel" else ""}" } ?: "no answer to 0101")

        for ((kind, id) in listOf(DtcKind.STORED to TestStepId.STORED, DtcKind.PENDING to TestStepId.PENDING, DtcKind.PERMANENT to TestStepId.PERMANENT)) {
            val (codes, err) = obd.readDtcs(kind)
            when {
                err == null -> add(id, TestStatus.PASS, if (codes.isEmpty()) "no codes" else codes.joinToString(" ") { "${it.code}@${it.ecu}" })
                // Older cars do not implement Mode 0A (permanent codes); that is not a fault.
                kind == DtcKind.PERMANENT -> add(id, TestStatus.NOT_SUPPORTED, err.name)
                else -> add(id, TestStatus.FAIL, err.name)
            }
        }

        val ff = obd.readFreezeFrame()
        add(TestStepId.FREEZE_FRAME, if (ff != null) TestStatus.PASS else TestStatus.NOT_SUPPORTED,
            ff?.let { "for ${it.dtc ?: "?"}, ${it.values.size} values" } ?: "none stored (normal when there are no codes)")

        val vi = obd.readVehicleInfo()
        add(TestStepId.VEHICLE_INFO, if (vi.vin != null) TestStatus.PASS else TestStatus.NOT_SUPPORTED,
            "VIN ${vi.vin ?: "not reported"}, ${vi.calibrationIds.size} cal IDs, ${vi.cvns.size} CVNs, ${vi.ecuNames.size} ECU names")

        val m06 = obd.readMode06()
        add(TestStepId.MODE06, if (!m06.isNullOrEmpty()) TestStatus.PASS else TestStatus.NOT_SUPPORTED,
            when { m06 == null -> "not a CAN car"; m06.isEmpty() -> "no results reported"; else -> "${m06.size} test results, ${m06.count { !it.passed }} failing" })

        val volt = obd.readVoltage()
        add(TestStepId.VOLTAGE, ok(volt != null), volt?.let { "${fmt(it)} V" } ?: "no answer to ATRV")
        return steps
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

    companion object {
        fun report(steps: List<TestStep>, appVersion: String, android: String, rawLog: String): String = buildString {
            appendLine("Diagnose Pro self-test report")
            appendLine("App $appVersion, Android $android")
            appendLine()
            for (s in steps) appendLine("[${s.status.name}] ${s.id.name}: ${s.detail}")
            appendLine()
            appendLine("--- raw adapter log (last lines) ---")
            append(rawLog)
        }
    }
}
