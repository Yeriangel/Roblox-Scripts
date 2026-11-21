package com.diagnosepro.app.domain

import com.diagnosepro.app.obd.Quantity
import java.util.Locale

enum class SpeedUnit { KMH, MPH }
enum class TempUnit { C, F }
enum class PressureUnit { KPA, BAR, PSI }
enum class EconomyUnit { L_PER_100KM, MPG_US, KM_PER_L }

data class UnitPrefs(
    val speed: SpeedUnit = SpeedUnit.KMH,
    val temp: TempUnit = TempUnit.C,
    val pressure: PressureUnit = PressureUnit.KPA,
    val economy: EconomyUnit = EconomyUnit.L_PER_100KM,
)

/** Converts base metric values (km/h, °C, kPa, km, L/h) to the user's units. */
object Units {
    fun convert(q: Quantity, v: Double, p: UnitPrefs): Double = when (q) {
        Quantity.SPEED -> if (p.speed == SpeedUnit.MPH) v * 0.621371 else v
        Quantity.DISTANCE -> if (p.speed == SpeedUnit.MPH) v * 0.621371 else v
        Quantity.TEMPERATURE -> if (p.temp == TempUnit.F) v * 9 / 5 + 32 else v
        Quantity.PRESSURE -> when (p.pressure) { PressureUnit.KPA -> v; PressureUnit.BAR -> v / 100.0; PressureUnit.PSI -> v * 0.145038 }
        Quantity.FUEL_RATE -> if (p.economy == EconomyUnit.MPG_US) v * 0.264172 else v
        else -> v
    }

    fun unitLabel(q: Quantity, p: UnitPrefs): String = when (q) {
        Quantity.SPEED -> if (p.speed == SpeedUnit.MPH) "mph" else "km/h"
        Quantity.DISTANCE -> if (p.speed == SpeedUnit.MPH) "mi" else "km"
        Quantity.TEMPERATURE -> if (p.temp == TempUnit.F) "°F" else "°C"
        Quantity.PRESSURE -> when (p.pressure) { PressureUnit.KPA -> "kPa"; PressureUnit.BAR -> "bar"; PressureUnit.PSI -> "psi" }
        Quantity.PERCENT -> "%"
        Quantity.RPM -> "rpm"
        Quantity.FLOW -> "g/s"
        Quantity.VOLTAGE -> "V"
        Quantity.ANGLE -> "°"
        Quantity.TIME -> ""
        Quantity.FUEL_RATE -> if (p.economy == EconomyUnit.MPG_US) "gal/h" else "L/h"
        Quantity.RAW -> ""
    }

    /** Extra decimals for bar (small numbers). */
    fun decimalsFor(q: Quantity, base: Int, p: UnitPrefs): Int =
        if (q == Quantity.PRESSURE && p.pressure == PressureUnit.BAR) 2 else base

    /** Always uses Western digits with grouping, e.g. "2,450". */
    fun format(v: Double, decimals: Int): String =
        if (decimals == 0) String.format(Locale.US, "%,d", Math.round(v)) else String.format(Locale.US, "%,.${decimals}f", v)

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}

/**
 * Fuel consumption estimate from MAF and speed (gasoline, stoichiometric).
 * fuel L/h = MAF[g/s] * 3600 / (AFR 14.7 * density 740 g/L). This is an ESTIMATE:
 * it ignores enrichment, diesel lean operation and fuel cut on overrun.
 */
object FuelEstimator {
    private const val AFR = 14.7
    private const val DENSITY_G_PER_L = 740.0

    fun litersPerHour(mafGs: Double): Double = mafGs * 3600.0 / (AFR * DENSITY_G_PER_L)

    /** Returns null below 3 km/h (economy is undefined when stopped). */
    fun litersPer100Km(mafGs: Double, speedKmh: Double): Double? =
        if (speedKmh < 3) null else litersPerHour(mafGs) / speedKmh * 100.0

    fun toUnit(lPer100: Double, unit: EconomyUnit): Double = when (unit) {
        EconomyUnit.L_PER_100KM -> lPer100
        EconomyUnit.MPG_US -> if (lPer100 <= 0) 0.0 else 235.215 / lPer100
        EconomyUnit.KM_PER_L -> if (lPer100 <= 0) 0.0 else 100.0 / lPer100
    }

    fun label(unit: EconomyUnit) = when (unit) {
        EconomyUnit.L_PER_100KM -> "L/100km"; EconomyUnit.MPG_US -> "MPG"; EconomyUnit.KM_PER_L -> "km/L"
    }
}

/** Trip summary from a series of (timestampMs, value) samples. */
object TripStats {
    data class Summary(
        val durationS: Long,
        val distanceKm: Double,
        val maxSpeed: Double?,
        val avgRpm: Double?,
        val maxCoolant: Double?,
    )

    fun summarize(speed: List<Pair<Long, Double>>, rpm: List<Pair<Long, Double>>, coolant: List<Pair<Long, Double>>, startMs: Long, endMs: Long): Summary {
        var dist = 0.0
        val s = speed.sortedBy { it.first }
        for (i in 1 until s.size) {
            val dtH = (s[i].first - s[i - 1].first) / 3_600_000.0
            if (dtH in 0.0..(30.0 / 3600)) dist += (s[i].second + s[i - 1].second) / 2 * dtH // trapezoid, skip gaps > 30 s
        }
        return Summary(
            durationS = ((endMs - startMs) / 1000).coerceAtLeast(0),
            distanceKm = dist,
            maxSpeed = s.maxOfOrNull { it.second },
            avgRpm = rpm.map { it.second }.takeIf { it.isNotEmpty() }?.average(),
            maxCoolant = coolant.maxOfOrNull { it.second },
        )
    }
}
