package com.doloh.marstekwidget.direct

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("available", available)
        .put("power_w", powerW ?: JSONObject.NULL)
        .put("direction", direction)
        .put("window_seconds", windowSeconds)
        .put("sample_count", sampleCount)
        .put("method", "battery_capacity_delta_regression")
        .put("reason", reason ?: JSONObject.NULL)
        .put("charge_eta_seconds", chargeEtaSeconds ?: JSONObject.NULL)
        .put("discharge_eta_seconds", dischargeEtaSeconds ?: JSONObject.NULL)
}

/** Port of server.py `calculate_battery_flow`: battery power from the measured Wh slope, never from grid sign. */
object BatteryFlowCalculator {
    const val WINDOW_SECONDS = 300.0
    const val MIN_SECONDS = 120.0
    const val DEADBAND_W = 10.0

    fun calculate(samples: List<CapacitySample>, batteryCapacityWh: Double?, ratedCapacityWh: Double?): BatteryFlow {
        val clean = samples.filter { it.t.isFinite() && it.wh.isFinite() }
        val empty = BatteryFlow(false, null, "unknown", 0, 0, "collecting_samples", null, null)
        if (clean.isEmpty()) return empty
        val newest = clean.last().t
        val recent = clean.filter { newest - it.t in 0.0..WINDOW_SECONDS }
        if (recent.size < 2) return empty.copy(sampleCount = recent.size)
        val duration = recent.last().t - recent.first().t
        val base = empty.copy(sampleCount = recent.size, windowSeconds = duration.roundToLong())
        if (duration < MIN_SECONDS) return base
        val origin = recent.first().t
        val xs = recent.map { it.t - origin }
        val ys = recent.map { it.wh }
        val meanX = xs.average()
        val meanY = ys.average()
        val variance = xs.sumOf { (it - meanX) * (it - meanX) }
        if (variance <= 0.0) return base.copy(reason = "insufficient_time_span")
        val slope = xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) } / variance
        var power = slope * 3600.0
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
            reason = null,
            chargeEtaSeconds = chargeEta,
            dischargeEtaSeconds = dischargeEta,
        )
    }
}
