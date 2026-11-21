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
