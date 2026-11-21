package com.diagnosepro.app.obd

import com.diagnosepro.app.domain.AlertRule
import com.diagnosepro.app.domain.BatteryTest
import com.diagnosepro.app.domain.PerformanceTracker
import com.diagnosepro.app.elm327.Elm327
import com.diagnosepro.app.transport.DemoTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NewFeaturesTest {
    private fun ev(f: String, vararg b: Int) = Expression.parse(f).eval(b)

    @Test fun expressionArithmetic() {
        assertEquals(1726.0, ev("(A*256+B)/4", 0x1A, 0xF8), 1e-9)
        assertEquals(50.0, ev("A - 40", 90), 1e-9)
        assertEquals(-3.0, ev("-(1+2)"), 1e-9)
        assertEquals(7.0, ev("1+2*3"), 1e-9)
        assertEquals(2.5, ev("5/2"), 1e-9)
        assertEquals(1.0, ev("7 % 3"), 1e-9)
    }

    @Test fun expressionBitsAndFunctions() {
        assertEquals(1.0, ev("(A>>3)&1", 0b0000_1000), 1e-9)
        assertEquals(6.0, ev("A|B", 2, 4), 1e-9)
        assertEquals(256.0, ev("A<<8", 1), 1e-9)
        assertEquals(-1.0, ev("signed(A)", 255), 1e-9)
        assertEquals(-2.0, ev("signed16(A*256+B)", 0xFF, 0xFE), 1e-9)
        assertEquals(5.0, ev("max(min(A,B),5)", 3, 9), 1e-9)
        assertEquals(3, Expression.parse("A+c").bytesNeeded)
    }

    @Test fun expressionErrors() {
        for (bad in listOf("", "A+", "(A", "foo(A)", "A $ B", "min(A)", "1..2")) {
            try { Expression.parse(bad); fail("should reject '$bad'") } catch (e: Expression.ParseException) { }
        }
        // divide by zero / missing byte are evaluation errors, caught by Pids.decode
        val def = CustomPid.toDef(1, "x", "221234", "A/B", "u", 0.0, 1.0)!!
        assertNull(Pids.decode(def, intArrayOf(1, 0)))
        assertNull(Pids.decode(def, intArrayOf(1)))
    }

    @Test fun customPidCommandsAreReadOnly() {
        assertEquals("221234", CustomPid.normalizeCommand("22 12 34"))
        assertEquals("0105", CustomPid.normalizeCommand("0105"))
        for (bad in listOf("04", "0800", "2E1234FF", "3101", "ATZ", "22123", "")) assertNull(bad, CustomPid.normalizeCommand(bad))
        assertTrue(CustomPid.dataBytes("221234", intArrayOf(0x62, 0x12, 0x34, 9, 8)).contentEquals(intArrayOf(9, 8)))
        assertNull(CustomPid.dataBytes("221234", intArrayOf(0x7F, 0x22, 0x31)))
        assertNull(CustomPid.dataBytes("221234", intArrayOf(0x62, 0x12, 0x35, 1)))
    }

    @Test fun customPidReadsThroughRealStack() = runBlocking {
        val t = DemoTransport(latencyMs = 0); t.open()
        val elm = Elm327(t); elm.initialize()
        val def = CustomPid.toDef(7, "RPM via 22", "22F40C", "(A*256+B)/4", "rpm", 0.0, 8000.0)!!
        val v = ObdService(elm).readPid(def)
        assertNotNull(v); assertTrue(v!! > 500)
    }

    @Test fun udsDtcDecoding() {
        // 59 02 FF | P0301-00 confirmed | C0035-13 pending | B1234-11 not failed (filtered out)
        val r = Uds.decodeDtcs(intArrayOf(0x59, 0x02, 0xFF, 0x03, 0x01, 0x00, 0x09, 0x40, 0x35, 0x13, 0x04, 0x92, 0x34, 0x11, 0x50))!!
        assertEquals(listOf("P0301", "C0035"), r.map { it.code })
        assertTrue(r[0].current); assertFalse(r[1].current); assertEquals(0x13, r[1].failureType)
        assertNull(Uds.decodeDtcs(intArrayOf(0x7F, 0x19, 0x11)))
        assertTrue(Uds.decodeDtcs(intArrayOf(0x59, 0x02, 0xFF))!!.isEmpty())
    }

    @Test fun moduleNaming() {
        assertEquals(ModuleKind.ABS, Uds.moduleKind("DA28F1", "28", true, null))        // SAE J2178
        assertEquals(ModuleKind.AIRBAG, Uds.moduleKind("DA58F1", "58", true, "Honda"))
        assertEquals(ModuleKind.ENGINE, Uds.moduleKind("7E0", "7E8", false, null))
        assertEquals(ModuleKind.ABS, Uds.moduleKind("760", "768", false, "Ford"))
        assertEquals(ModuleKind.UNKNOWN, Uds.moduleKind("760", "768", false, "Renault"))
        assertEquals(255, Uds.probeTargets(true).size)
        assertFalse("7DF" in Uds.probeTargets(false))
    }

    @Test fun fullSystemScanOnSimulatorIsReadOnlyAndRestoresAdapter() = runBlocking {
        val sent = mutableListOf<String>()
        val t = DemoTransport(latencyMs = 0); t.open()
        val elm = Elm327(t, com.diagnosepro.app.elm327.RawLogger { d, text -> if (d == com.diagnosepro.app.elm327.RawLogger.Direction.TX) sent += text })
        elm.initialize()
        val obd = ObdService(elm)
        val mods = obd.scanModules("Volkswagen") { _, _ -> }!!
        assertEquals(setOf("7E0", "713", "715", "70E"), mods.map { it.requestId }.toSet())
        assertEquals(ModuleKind.ABS, mods.first { it.requestId == "713" }.kind)
        assertEquals(listOf("C0035"), mods.first { it.requestId == "713" }.dtcs!!.map { it.code })
        assertTrue(mods.first { it.requestId == "715" }.dtcs!!.isEmpty())
        // Only AT commands, 10 01 and 19 02 may have been sent during the scan
        val obdCmds = sent.filterNot { it.startsWith("AT") || it.startsWith("retry") }
        assertTrue(obdCmds.toString(), obdCmds.none { it.startsWith("04") || it.startsWith("14") || it.startsWith("2E") || it.startsWith("31") || it.startsWith("11") })
        // Adapter restored: normal OBD reads work again and headers are off
        assertFalse(elm.headersOn)
        assertNotNull(obd.readPid(Pids.byKey("rpm")!!))
    }

    @Test fun moduleInfoAndModuleCustomPid() = runBlocking {
        val t = DemoTransport(latencyMs = 0); t.open()
        val elm = Elm327(t); elm.initialize()
        val obd = ObdService(elm)
        val info = obd.readModuleInfo("713")
        assertEquals("ABS-ESP", info[0xF197])
        assertEquals("WVWZZZ1KZ9W123456", info[0xF190])
        assertFalse(info.containsKey(0xF18C)) // negative response is skipped
        // custom PID aimed at the ABS module, then normal OBD must still work (address restored)
        val def = CustomPid.toDef(9, "abs", "221234", "(A*256+B)/4", "rpm", 0.0, 1.0, "713")!!
        assertEquals("713", def.customHeader)
        assertTrue(obd.readPid(def)!! > 500)
        assertNotNull(obd.readPid(Pids.byKey("speed")!!))
        assertFalse(elm.headersOn)
    }

    @Test fun headerValidationAndPrintable() {
        assertEquals("7B0", CustomPid.normalizeHeader("7b0"))
        assertEquals("DA28F1", CustomPid.normalizeHeader("DA 28 F1"))
        for (bad in listOf("8B0", "7B", "DA28F2", "XYZ")) assertNull(bad, CustomPid.normalizeHeader(bad))
        assertTrue(CustomPid.isHeaderValid("")); assertFalse(CustomPid.isHeaderValid("zz"))
        assertEquals("AB1", Uds.printable(intArrayOf(0x41, 0x42, 0x31, 0x00, 0x00)))
        assertEquals("01 FE", Uds.printable(intArrayOf(0x01, 0xFE)))
        assertNull(Uds.printable(intArrayOf(0xFF, 0xFF)))
    }

    @Test fun terminalGuard() {
        for (ok in listOf("ATRV", "at dp", "0100", "03", "0902", "22F190", "ATH1")) assertTrue(ok, TerminalGuard.isAllowed(ok))
        for (bad in listOf("04", "0801", "2E F1 90 01", "3101FF00", "1003", "2701", "ATPP 0C SV 23", "AT@3 x", "ATSD01", "", "010", "XYZ"))
            assertFalse(bad, TerminalGuard.isAllowed(bad))
    }

    @Test fun computedPids() {
        val boost = Pids.byKey("boost")!!
        assertEquals(50.0, boost.compute!!(mapOf("map" to 150.0, "baro" to 100.0))!!, 1e-9)
        assertEquals(48.7, boost.compute!!(mapOf("map" to 150.0))!!, 1e-9)
        assertNull(Pids.byKey("consumption_est")!!.compute!!(mapOf("maf" to 10.0, "speed" to 0.0)))
        assertEquals(125.0, Pids.byKey("power_est")!!.compute!!(mapOf("maf" to 100.0))!!, 1e-9)
        assertTrue(Pids.isAvailable(boost, setOf(0x0B)))
        assertFalse(Pids.isAvailable(boost, setOf(0x0C)))
    }

    @Test fun extendedPidFormulas() {
        fun v(k: String, vararg b: Int) = Pids.decode(Pids.byKey(k)!!, b)!!
        assertEquals(1.0, v("commanded_lambda", 0x80, 0x00), 1e-6)
        assertEquals(460.0, v("cat_temp_b1s1", 0x13, 0x88), 1e-6)     // 5000/10-40
        assertEquals(0.0, v("torque_actual", 125), 1e-9)
        assertEquals(-210.0, v("injection_timing", 0, 0), 1e-9)
        assertEquals(120.0, v("time_mil_on", 0, 2), 1e-9)              // minutes → seconds
        assertEquals(Pids.all.size, Pids.all.map { it.key }.toSet().size)
    }

    @Test fun performanceTracker() {
        // Constant 5 m/s² (18 km/h per second), sampled every 100 ms
        val p = PerformanceTracker()
        var t = 0L
        p.add(t, 0.0)
        while (t < 15_000) { t += 100; p.add(t, minOf(18.0 * t / 1000.0, 200.0)) }
        assertEquals(3.33, p.results.zeroTo60!!, 0.12)
        assertEquals(5.56, p.results.zeroTo100!!, 0.12)
        assertEquals((120 - 80) / 18.0, p.results.eightyTo120!!, 0.05)
        // 402.336 m = 0.5*5*t^2 → 12.69 s, but speed caps at 200 km/h (11.1 s): d(11.11)=308.6 m, rest at 55.56 m/s
        assertEquals(11.11 + (402.336 - 308.64) / 55.556, p.results.quarterMileTime!!, 0.15)
        // Brake from 200 at 10 m/s² (36 km/h per second)
        var v = 200.0
        while (v > 0) { t += 100; v = maxOf(0.0, v - 3.6); p.add(t, v) }
        assertEquals(100 / 36.0, p.results.braking100to0Time!!, 0.15)
        assertEquals(38.6, p.results.braking100to0Dist!!, 1.5) // v²/2a = 27.78²/20
        assertEquals(200.0, p.results.maxSpeed, 1e-9)
    }

    @Test fun batteryVerdicts() {
        val r = BatteryTest.evaluate(12.6, 10.2, 14.1)
        assertEquals(BatteryTest.Verdict.GOOD, r.overall)
        assertEquals(BatteryTest.Verdict.WEAK, BatteryTest.resting(12.1))
        assertEquals(BatteryTest.Verdict.BAD, BatteryTest.cranking(8.5))
        assertEquals(BatteryTest.Verdict.BAD, BatteryTest.charging(12.4))
        assertEquals(BatteryTest.Verdict.BAD, BatteryTest.evaluate(12.6, 8.0, 14.0).overall)
    }

    @Test fun alertRules() {
        val r = AlertRule("coolant_temp", true, 110.0)
        assertTrue(r.matches(111.0)); assertFalse(r.matches(110.0))
        assertEquals(r, AlertRule.decode(r.encode()))
        assertFalse(AlertRule("module_voltage", false, 11.5).matches(12.0))
        assertNull(AlertRule.decode("garbage"))
    }
}
