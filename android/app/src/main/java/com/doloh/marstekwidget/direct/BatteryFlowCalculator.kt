package com.doloh.marstekwidget.direct

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One `bat_capacity` reading; [t] in seconds (any monotonic origin). */
data class CapacitySample(val t: Double, val wh: Double)

data class BatteryFlow(
    val available: Boolean,
    val powerW: Double?,
    val direction: String,
    val windowSeconds: Long,
    val sampleCount: Int,
    val reason: String?,
    val chargeEtaSeconds: Double?,
    val dischargeEtaSeconds: Double?,
    /** False = measured from ES ongrid/offgrid power; true = bat_capacity trend estimate. */
    val estimated: Boolean = true,
    val method: String = "battery_capacity_delta_regression",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("available", available)
        .put("power_w", powerW ?: JSONObject.NULL)
        .put("direction", direction)
        .put("window_seconds", windowSeconds)
        .put("sample_count", sampleCount)
        .put("method", method)
        .put("estimated", estimated)
        .put("reason", reason ?: JSONObject.NULL)
        .put("charge_eta_seconds", chargeEtaSeconds ?: JSONObject.NULL)
        .put("discharge_eta_seconds", dischargeEtaSeconds ?: JSONObject.NULL)
}

/**
 * Port of server.py `calculate_battery_flow`: measured AC power first, the stored-Wh trend only as an estimate.
 *
 * Sign convention of ES.GetStatus on a Venus E (verified on fw 148): `ongrid_power` < 0 = grid → battery
 * (charging; the 99→100 % standby top-up reads ≈ −1195 W), > 0 = battery output to the grid-side port;
 * `offgrid_power` = backup-output draw (battery output). Both ≈ 0 → estimate from bat_capacity, which a Venus E
 * reports in whole SOC steps (rated/100 ≈ 51 Wh): one step is not a trend — a quantized series needs ≥ 2 steps
 * in the same direction within [TREND_WINDOW_SECONDS], otherwise idle («soc_step_guard»).
 */
object BatteryFlowCalculator {
    const val WINDOW_SECONDS = 300.0
    const val TREND_WINDOW_SECONDS = 1800.0
    const val MIN_SECONDS = 120.0
    const val DEADBAND_W = 10.0
    const val MEASURED_MIN_W = 10.0

    /** + = charging, − = discharging; null when neither AC port shows a flow. */
    fun measuredPower(ongridW: Double?, offgridW: Double?): Double? {
        val ongrid = ongridW?.takeIf { it.isFinite() && abs(it) >= MEASURED_MIN_W } ?: 0.0
        val offgrid = offgridW?.takeIf { it.isFinite() && it >= MEASURED_MIN_W } ?: 0.0 // negative = noise, not a load
        if (ongrid == 0.0 && offgrid == 0.0) return null
        return -ongrid - offgrid
    }

    private fun quantizedSteps(samples: List<CapacitySample>, ratedWh: Double?): List<Pair<Double, Double>>? {
        val quantum = (ratedWh ?: return null) / 100.0
        if (!quantum.isFinite() || quantum <= 0) return null
        val changes = samples.zipWithNext().filter { (a, b) -> abs(b.wh - a.wh) > 0.5 }.map { (a, b) -> b.t to b.wh - a.wh }
        if (changes.isEmpty()) return null
        val whole = changes.all { (_, d) -> val n = abs(d) / quantum; n.roundToInt() >= 1 && abs(n - n.roundToInt()) <= 0.15 }
        return if (whole) changes else null
    }

    fun calculate(
        samples: List<CapacitySample>, batteryCapacityWh: Double?, ratedCapacityWh: Double?,
        ongridW: Double? = null, offgridW: Double? = null,
    ): BatteryFlow {
        val clean = samples.filter { it.t.isFinite() && it.wh.isFinite() }
        val empty = BatteryFlow(false, null, "unknown", 0, 0, "collecting_samples", null, null)
        val measured = measuredPower(ongridW, offgridW)
        var base: BatteryFlow
        var power: Double
        if (measured != null) {
            power = measured
            base = empty.copy(sampleCount = clean.size, estimated = false, method = "measured_ac_power")
        } else {
            if (clean.isEmpty()) return empty
            val newest = clean.last().t
            val trend = clean.filter { newest - it.t in 0.0..TREND_WINDOW_SECONDS }
            val recent = trend.filter { newest - it.t <= WINDOW_SECONDS }
            val steps = quantizedSteps(trend, ratedCapacityWh)
            if (steps != null) {
                base = empty.copy(sampleCount = trend.size, windowSeconds = (newest - trend.first().t).roundToLong(),
                    method = "battery_capacity_soc_steps")
                power = 0.0
                val sameSign = steps.all { it.second > 0 } || steps.all { it.second < 0 }
                if (steps.size >= 2 && sameSign) {
                    val span = steps.last().first - steps.first().first
                    val interval = span / (steps.size - 1)
                    val fresh = newest - steps.last().first <= max(2.0 * interval, WINDOW_SECONDS)
                    if (span > 0 && fresh) power = steps.drop(1).sumOf { it.second } / span * 3600.0
                }
                if (power == 0.0) base = base.copy(reason = "soc_step_guard")
            } else {
                if (recent.size < 2) return empty.copy(sampleCount = recent.size)
                val duration = recent.last().t - recent.first().t
                base = empty.copy(sampleCount = recent.size, windowSeconds = duration.roundToLong())
                if (duration < MIN_SECONDS) return base
                val origin = recent.first().t
                val xs = recent.map { it.t - origin }
                val ys = recent.map { it.wh }
                val meanX = xs.average()
                val meanY = ys.average()
                val variance = xs.sumOf { (it - meanX) * (it - meanX) }
                if (variance <= 0.0) return base.copy(reason = "insufficient_time_span")
                val slope = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) } / variance
                power = slope * 3600.0
            }
        }
        if (!power.isFinite()) return base.copy(reason = "invalid_rate")
        val direction = when {
            power >= DEADBAND_W -> "charging"
            power <= -DEADBAND_W -> "discharging"
            else -> { power = 0.0; "idle" }
        }
        var chargeEta: Double? = null
        var dischargeEta: Double? = null
        val capacity = batteryCapacityWh
        val rated = ratedCapacityWh
        if (capacity != null && rated != null && capacity.isFinite() && rated.isFinite() && rated > 0) {
            if (direction == "charging") {
                val remaining = max(0.0, rated - capacity)
                chargeEta = if (remaining <= 0.5) 0.0 else remaining / power * 3600.0
            } else if (direction == "discharging") {
                val available = max(0.0, capacity)
                dischargeEta = if (available <= 0.5) 0.0 else available / abs(power) * 3600.0
            }
        }
        return base.copy(
            available = true,
            powerW = Math.round(power * 10.0) / 10.0,
            direction = direction,
            reason = base.reason.takeIf { it == "soc_step_guard" },
            chargeEtaSeconds = chargeEta,
            dischargeEtaSeconds = dischargeEta,
        )
    }
}
