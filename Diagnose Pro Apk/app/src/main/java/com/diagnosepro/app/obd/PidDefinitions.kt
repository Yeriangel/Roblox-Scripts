package com.diagnosepro.app.obd

/** Physical quantity of a PID value; drives unit conversion in the UI. */
enum class Quantity { SPEED, TEMPERATURE, PRESSURE, PERCENT, RPM, FLOW, VOLTAGE, ANGLE, TIME, DISTANCE, FUEL_RATE, RAW }

/** How often a PID is refreshed by the smart poller. */
enum class PollPriority(val everyNCycles: Int) { FAST(1), MEDIUM(3), SLOW(10) }

/**
 * Data-driven definition of a Mode 01 PID.
 *
 * @param key stable identifier, also the suffix of the string resource "pid_<key>".
 * @param bytes number of data bytes (A, B, C, D...).
 * @param formula converts the data bytes to a value in base (metric) units.
 * @param gaugeMin/gaugeMax gauge scale in base units.
 * @param warnLow/warnHigh normal range limits in base units; outside = warning color.
 */
data class PidDef(
    val pid: Int,
    val key: String,
    val bytes: Int,
    val quantity: Quantity,
    val gaugeMin: Double,
    val gaugeMax: Double,
    val warnLow: Double? = null,
    val warnHigh: Double? = null,
    val priority: PollPriority = PollPriority.MEDIUM,
    val decimals: Int = 0,
    val formula: (IntArray) -> Double,
) {
    val command: String get() = "01%02X".format(pid)
    fun isWarning(v: Double): Boolean = (warnLow != null && v < warnLow) || (warnHigh != null && v > warnHigh)
}

object Pids {
    private fun a(d: IntArray) = d[0].toDouble()
    private fun ab(d: IntArray) = (d[0] * 256 + d[1]).toDouble()
    private fun pct(d: IntArray) = d[0] * 100.0 / 255.0
    private fun trim(d: IntArray) = (d[0] - 128) * 100.0 / 128.0
    private fun temp(d: IntArray) = d[0] - 40.0

    /** O2 sensor voltage from PIDs 0x14-0x1B: A/200 volts (B = short term trim, unused). */
    private fun o2(pid: Int, key: String) =
        PidDef(pid, key, 2, Quantity.VOLTAGE, 0.0, 1.275, priority = PollPriority.MEDIUM, decimals = 3) { it[0] / 200.0 }

    val all: List<PidDef> = listOf(
        PidDef(0x04, "engine_load", 1, Quantity.PERCENT, 0.0, 100.0, warnHigh = 95.0, priority = PollPriority.FAST) { pct(it) },
        PidDef(0x05, "coolant_temp", 1, Quantity.TEMPERATURE, -40.0, 130.0, warnHigh = 105.0, priority = PollPriority.SLOW) { temp(it) },
        PidDef(0x06, "stft_b1", 1, Quantity.PERCENT, -25.0, 25.0, -20.0, 20.0, PollPriority.MEDIUM, 1) { trim(it) },
        PidDef(0x07, "ltft_b1", 1, Quantity.PERCENT, -25.0, 25.0, -15.0, 15.0, PollPriority.SLOW, 1) { trim(it) },
        PidDef(0x08, "stft_b2", 1, Quantity.PERCENT, -25.0, 25.0, -20.0, 20.0, PollPriority.MEDIUM, 1) { trim(it) },
        PidDef(0x09, "ltft_b2", 1, Quantity.PERCENT, -25.0, 25.0, -15.0, 15.0, PollPriority.SLOW, 1) { trim(it) },
        PidDef(0x0A, "fuel_pressure", 1, Quantity.PRESSURE, 0.0, 765.0, priority = PollPriority.MEDIUM) { a(it) * 3 },
        PidDef(0x0B, "map", 1, Quantity.PRESSURE, 0.0, 255.0, priority = PollPriority.FAST) { a(it) },
        PidDef(0x0C, "rpm", 2, Quantity.RPM, 0.0, 8000.0, warnHigh = 6500.0, priority = PollPriority.FAST) { ab(it) / 4.0 },
        PidDef(0x0D, "speed", 1, Quantity.SPEED, 0.0, 240.0, priority = PollPriority.FAST) { a(it) },
        PidDef(0x0E, "timing_advance", 1, Quantity.ANGLE, -64.0, 64.0, priority = PollPriority.MEDIUM, decimals = 1) { a(it) / 2.0 - 64.0 },
        PidDef(0x0F, "intake_temp", 1, Quantity.TEMPERATURE, -40.0, 100.0, warnHigh = 70.0, priority = PollPriority.SLOW) { temp(it) },
        PidDef(0x10, "maf", 2, Quantity.FLOW, 0.0, 300.0, priority = PollPriority.FAST, decimals = 2) { ab(it) / 100.0 },
        PidDef(0x11, "throttle", 1, Quantity.PERCENT, 0.0, 100.0, priority = PollPriority.FAST) { pct(it) },
        o2(0x14, "o2_b1s1"), o2(0x15, "o2_b1s2"), o2(0x16, "o2_b1s3"), o2(0x17, "o2_b1s4"),
        o2(0x18, "o2_b2s1"), o2(0x19, "o2_b2s2"), o2(0x1A, "o2_b2s3"), o2(0x1B, "o2_b2s4"),
        PidDef(0x1F, "run_time", 2, Quantity.TIME, 0.0, 65535.0, priority = PollPriority.SLOW) { ab(it) },
        PidDef(0x21, "distance_mil", 2, Quantity.DISTANCE, 0.0, 65535.0, priority = PollPriority.SLOW) { ab(it) },
        PidDef(0x2F, "fuel_level", 1, Quantity.PERCENT, 0.0, 100.0, warnLow = 10.0, priority = PollPriority.SLOW) { pct(it) },
        PidDef(0x31, "distance_cleared", 2, Quantity.DISTANCE, 0.0, 65535.0, priority = PollPriority.SLOW) { ab(it) },
        PidDef(0x33, "baro", 1, Quantity.PRESSURE, 0.0, 255.0, priority = PollPriority.SLOW) { a(it) },
        PidDef(0x42, "module_voltage", 2, Quantity.VOLTAGE, 8.0, 16.0, 11.8, 15.0, PollPriority.SLOW, 2) { ab(it) / 1000.0 },
        PidDef(0x46, "ambient_temp", 1, Quantity.TEMPERATURE, -40.0, 60.0, priority = PollPriority.SLOW) { temp(it) },
        PidDef(0x5C, "oil_temp", 1, Quantity.TEMPERATURE, -40.0, 160.0, warnHigh = 130.0, priority = PollPriority.SLOW) { temp(it) },
        PidDef(0x5E, "fuel_rate", 2, Quantity.FUEL_RATE, 0.0, 50.0, priority = PollPriority.MEDIUM, decimals = 2) { ab(it) / 20.0 },
        PidDef(0xA6, "odometer", 4, Quantity.DISTANCE, 0.0, 1_000_000.0, priority = PollPriority.SLOW, decimals = 1) {
            ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
        },
    )

    /** Pseudo PID: adapter battery voltage via ATRV. Always available. */
    const val KEY_ADAPTER_VOLTAGE = "adapter_voltage"
    val adapterVoltage = PidDef(-1, KEY_ADAPTER_VOLTAGE, 0, Quantity.VOLTAGE, 8.0, 16.0, 11.8, 15.0, PollPriority.SLOW, 1) { 0.0 }

    private val byPid = all.associateBy { it.pid }
    private val byKey = (all + adapterVoltage).associateBy { it.key }

    fun byPid(pid: Int): PidDef? = byPid[pid]
    fun byKey(key: String): PidDef? = byKey[key]

    val defaultDashboard = listOf("rpm", "speed", "coolant_temp", "engine_load", "throttle", "intake_temp",
        "maf", "fuel_level", "stft_b1", "ltft_b1", "timing_advance", "module_voltage", KEY_ADAPTER_VOLTAGE, "run_time")

    /** Decodes a Mode 01 (or Mode 02 after removing the frame byte) value. */
    fun decode(def: PidDef, data: IntArray): Double? =
        if (data.size < def.bytes) null else runCatching { def.formula(data) }.getOrNull()
}
