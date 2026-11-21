package com.diagnosepro.app.domain

/** Result of one performance run. Times in seconds, distance in metres, speeds in km/h. */
data class PerfResults(
    val zeroTo100: Double? = null,
    val zeroTo60: Double? = null,       // 0-60 km/h
    val eightyTo120: Double? = null,
    val quarterMileTime: Double? = null,
    val quarterMileSpeed: Double? = null,
    val braking100to0Time: Double? = null,
    val braking100to0Dist: Double? = null,
    val maxSpeed: Double = 0.0,
)

/**
 * Computes acceleration / braking figures from a stream of (timestamp ms, speed km/h) samples.
 * Crossing times are linearly interpolated between samples, because OBD speed only updates a
 * few times per second. Distance is integrated with the trapezoid rule.
 *
 * A run starts when the car leaves standstill; it resets when the car stops again.
 */
class PerformanceTracker(private val target100: Double = 100.0, private val target60: Double = 60.0) {
    var results = PerfResults(); private set
    var running = false; private set

    private var lastT = 0L
    private var lastV = 0.0
    private var hasLast = false
    private var startT = 0.0            // interpolated launch time (ms)
    private var dist = 0.0              // metres since launch
    private var t80: Double? = null
    private var brakeStartT: Double? = null
    private var brakeDist = 0.0
    private var wasAbove100 = false

    fun reset() { results = PerfResults(); running = false; hasLast = false; t80 = null; brakeStartT = null; wasAbove100 = false }

    /** Time (ms) at which speed crossed [level] between the previous and current sample. */
    private fun cross(level: Double, t: Long, v: Double): Double =
        if (v == lastV) t.toDouble() else lastT + (level - lastV) / (v - lastV) * (t - lastT)

    fun add(t: Long, v: Double) {
        if (!hasLast) { lastT = t; lastV = v; hasLast = true; return }
        val dtS = (t - lastT) / 1000.0
        if (dtS <= 0 || dtS > 5) { lastT = t; lastV = v; running = false; return } // gap: discard
        val segment = (lastV + v) / 2 / 3.6 * dtS // metres

        // --- launch ---
        if (!running && lastV < 1.0 && v >= 1.0) {
            running = true; dist = 0.0; t80 = null
            startT = cross(1.0, t, v)
            results = results.copy(zeroTo100 = null, zeroTo60 = null, quarterMileTime = null, quarterMileSpeed = null)
        }
        if (running) {
            if (results.zeroTo60 == null && lastV < target60 && v >= target60) results = results.copy(zeroTo60 = (cross(target60, t, v) - startT) / 1000)
            if (results.zeroTo100 == null && lastV < target100 && v >= target100) results = results.copy(zeroTo100 = (cross(target100, t, v) - startT) / 1000)
            val before = dist
            dist += segment
            if (results.quarterMileTime == null && before < QUARTER_MILE_M && dist >= QUARTER_MILE_M) {
                val f = (QUARTER_MILE_M - before) / segment
                results = results.copy(quarterMileTime = (lastT + f * (t - lastT) - startT) / 1000, quarterMileSpeed = lastV + f * (v - lastV))
            }
            if (v < 1.0) running = false
        }

        // --- 80-120 km/h (in-gear passing) ---
        if (lastV < 80 && v >= 80) t80 = cross(80.0, t, v)
        if (v < 78) t80 = null
        val s80 = t80
        if (s80 != null && lastV < 120 && v >= 120) { results = results.copy(eightyTo120 = (cross(120.0, t, v) - s80) / 1000); t80 = null }

        // --- braking 100-0 ---
        if (v >= 100) wasAbove100 = true
        if (wasAbove100 && lastV >= 100 && v < 100) {
            val tc = cross(100.0, t, v)
            brakeStartT = tc
            brakeDist = (100 + v) / 2 / 3.6 * (t - tc) / 1000.0
            wasAbove100 = false
        } else if (brakeStartT != null) {
            if (v > lastV + 2) brakeStartT = null // accelerating again: not a braking test
            else {
                brakeDist += segment
                if (v < 1.0) {
                    results = results.copy(braking100to0Time = (t - brakeStartT!!) / 1000, braking100to0Dist = brakeDist)
                    brakeStartT = null
                }
            }
        }
        if (v > results.maxSpeed) results = results.copy(maxSpeed = v)
        lastT = t; lastV = v
    }

    companion object { const val QUARTER_MILE_M = 402.336 }
}

/** Battery / charging system check from three voltage measurements. */
object BatteryTest {
    enum class Verdict { GOOD, WEAK, BAD }
    data class Result(val resting: Verdict?, val cranking: Verdict?, val charging: Verdict?) {
        val overall: Verdict get() = listOfNotNull(resting, cranking, charging).maxByOrNull { it.ordinal } ?: Verdict.GOOD
    }

    /** 12 V lead-acid thresholds: resting 12.4 V+ healthy, <12.0 V discharged. */
    fun resting(v: Double) = when { v >= 12.4 -> Verdict.GOOD; v >= 12.0 -> Verdict.WEAK; else -> Verdict.BAD }
    /** Lowest voltage while the starter runs: 9.6 V+ is healthy. */
    fun cranking(v: Double) = when { v >= 9.6 -> Verdict.GOOD; v >= 9.0 -> Verdict.WEAK; else -> Verdict.BAD }
    /** Alternator output at idle: 13.5-14.8 V is normal. */
    fun charging(v: Double) = when { v in 13.5..14.8 -> Verdict.GOOD; v in 13.0..15.2 -> Verdict.WEAK; else -> Verdict.BAD }

    fun evaluate(resting: Double?, crankMin: Double?, charging: Double?) =
        Result(resting?.let(::resting), crankMin?.let(::cranking), charging?.let(::charging))
}

/** User alarm: fire when a live value goes above/below a limit (in base metric units). */
data class AlertRule(val key: String, val above: Boolean, val threshold: Double) {
    fun matches(v: Double) = if (above) v > threshold else v < threshold
    fun encode() = "$key|${if (above) ">" else "<"}|$threshold"

    companion object {
        fun decode(s: String): AlertRule? {
            val p = s.split('|')
            if (p.size != 3) return null
            return AlertRule(p[0], p[1] == ">", p[2].toDoubleOrNull() ?: return null)
        }
        val defaults = listOf(AlertRule("coolant_temp", true, 110.0))
    }
}

data class ActiveAlert(val rule: AlertRule, val value: Double, val time: Long)
