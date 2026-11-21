package com.diagnosepro.app.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidFormulaTest {
    private fun v(key: String, vararg bytes: Int) = Pids.decode(Pids.byKey(key)!!, bytes)!!

    @Test fun rpm() {
        assertEquals(1726.0, v("rpm", 0x1A, 0xF8), 0.001) // (26*256+248)/4
        assertEquals(0.0, v("rpm", 0, 0), 0.0)
        assertEquals(16383.75, v("rpm", 0xFF, 0xFF), 0.001)
    }

    @Test fun speed() = assertEquals(100.0, v("speed", 0x64), 0.0)

    @Test fun temperatures() {
        assertEquals(90.0, v("coolant_temp", 130), 0.0)
        assertEquals(-40.0, v("coolant_temp", 0), 0.0)
        assertEquals(25.0, v("intake_temp", 65), 0.0)
        assertEquals(215.0, v("oil_temp", 255), 0.0)
    }

    @Test fun fuelTrims() {
        assertEquals(0.0, v("stft_b1", 128), 0.001)
        assertEquals(-100.0, v("ltft_b1", 0), 0.001)
        assertEquals(99.21875, v("stft_b2", 255), 0.0001)
        assertEquals(7.03125, v("ltft_b2", 137), 0.0001)
    }

    @Test fun maf() {
        assertEquals(2.55, v("maf", 0x00, 0xFF), 0.0001)
        assertEquals(655.35, v("maf", 0xFF, 0xFF), 0.0001)
    }

    @Test fun percentAndOthers() {
        assertEquals(100.0, v("throttle", 255), 0.001)
        assertEquals(50.196, v("engine_load", 128), 0.001)
        assertEquals(14.2, v("module_voltage", 0x37, 0x78), 0.001)
        assertEquals(-64.0, v("timing_advance", 0), 0.0)
        assertEquals(0.9, v("o2_b1s1", 180, 0xFF), 0.0001)
        assertEquals(123456.7, v("odometer", 0x00, 0x12, 0xD6, 0x87), 0.01)
        assertEquals(300.0, v("fuel_pressure", 100), 0.0)
    }

    @Test fun tooFewBytesReturnsNull() = assertNull(Pids.decode(Pids.byKey("rpm")!!, intArrayOf(0x1A)))

    @Test fun warningRanges() {
        val coolant = Pids.byKey("coolant_temp")!!
        assertTrue(coolant.isWarning(106.0))
        assertFalse(coolant.isWarning(90.0))
        assertTrue(Pids.byKey("module_voltage")!!.isWarning(11.0))
    }

    @Test fun tableIsConsistent() {
        assertEquals(Pids.all.size, Pids.all.map { it.pid }.toSet().size)
        assertEquals(Pids.all.size, Pids.all.map { it.key }.toSet().size)
        Pids.defaultDashboard.forEach { assertTrue(it, Pids.byKey(it) != null) }
    }
}
