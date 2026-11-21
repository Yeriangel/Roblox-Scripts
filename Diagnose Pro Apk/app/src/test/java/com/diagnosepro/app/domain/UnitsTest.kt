package com.diagnosepro.app.domain

import com.diagnosepro.app.obd.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    @Test fun conversions() {
        val imp = UnitPrefs(SpeedUnit.MPH, TempUnit.F, PressureUnit.PSI, EconomyUnit.MPG_US)
        assertEquals(62.137, Units.convert(Quantity.SPEED, 100.0, imp), 0.01)
        assertEquals(212.0, Units.convert(Quantity.TEMPERATURE, 100.0, imp), 0.01)
        assertEquals(14.5, Units.convert(Quantity.PRESSURE, 100.0, imp), 0.01)
        assertEquals(1.0, Units.convert(Quantity.PRESSURE, 100.0, UnitPrefs(pressure = PressureUnit.BAR)), 0.0001)
    }

    @Test fun formatting() {
        assertEquals("2,450", Units.format(2450.4, 0))
        assertEquals("1:05", Units.formatDuration(65))
        assertEquals("1:01:01", Units.formatDuration(3661))
    }

    @Test fun fuelEstimate() {
        // 10 g/s at 100 km/h ≈ 3.31 L/h ≈ 3.31 L/100km
        assertEquals(3.309, FuelEstimator.litersPer100Km(10.0, 100.0)!!, 0.01)
        assertNull(FuelEstimator.litersPer100Km(10.0, 0.0))
        assertEquals(23.52, FuelEstimator.toUnit(10.0, EconomyUnit.MPG_US), 0.01)
    }

    @Test fun tripSummary() {
        val sp = listOf(0L to 0.0, 1000L to 36.0, 2000L to 36.0) // ~0.015 km
        val s = TripStats.summarize(sp, listOf(0L to 1000.0, 1000L to 2000.0), listOf(0L to 90.0, 1L to 95.0), 0, 2000)
        assertEquals(2, s.durationS)
        assertEquals(0.015, s.distanceKm, 0.0001)
        assertEquals(36.0, s.maxSpeed!!, 0.0)
        assertEquals(1500.0, s.avgRpm!!, 0.0)
        assertEquals(95.0, s.maxCoolant!!, 0.0)
    }
}
