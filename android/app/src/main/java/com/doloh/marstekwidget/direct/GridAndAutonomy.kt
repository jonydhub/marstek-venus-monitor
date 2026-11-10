package com.doloh.marstekwidget.direct

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/** Loads sit on the battery's backup output: any draw above the threshold means the grid is absent. */
object GridStateDetector {
    const val DEFAULT_THRESHOLD_W = 5.0

    fun detect(offgridPowerW: Double?, deviceOk: Boolean, thresholdW: Double = DEFAULT_THRESHOLD_W): String {
        if (!deviceOk || offgridPowerW == null || !offgridPowerW.isFinite()) return "unknown"
        return if (abs(offgridPowerW) >= thresholdW) "off" else "on"
    }
}

data class Autonomy(
    val loadW: Double?,
    val loadBasis: String?,
    val storedWh: Long?,
    val runtimeSeconds: Long?,
    val outageSeconds: Long?,
    val coversNextOutage: Boolean?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("load_w", loadW ?: JSONObject.NULL)
        .put("load_basis", loadBasis ?: JSONObject.NULL)
        .put("stored_wh", storedWh ?: JSONObject.NULL)
        .put("runtime_seconds", runtimeSeconds ?: JSONObject.NULL)
        .put("outage_seconds", outageSeconds ?: JSONObject.NULL)
        .put("covers_next_outage", coversNextOutage ?: JSONObject.NULL)
}

/** Port of outage.py `_autonomy`. */
object AutonomyCalculator {
    fun calculate(
        gridState: String,
        storedWh: Double?,
        flow: BatteryFlow?,
        expectedLoadW: Double?,
        measuredLoadW: Double?,
        outageRemainingSeconds: Long?,
    ): Autonomy {
        val none = Autonomy(null, null, null, null, null, null)
        if (storedWh == null || !storedWh.isFinite() || storedWh < 0) return none
        val stored = Math.round(storedWh)
        val measured = flow?.powerW
        val (load, basis) = when {
            gridState == "off" && flow?.direction == "discharging" && measured != null -> abs(measured) to "measured_discharge"
            expectedLoadW != null && expectedLoadW > 0 -> expectedLoadW to "configured_average"
            measuredLoadW != null && measuredLoadW > 0 -> measuredLoadW to "measured_average_24h"
            else -> null to null
        }
        if (load == null || !load.isFinite() || load <= 0.0) {
            return none.copy(storedWh = stored, outageSeconds = outageRemainingSeconds)
        }
        val runtime = (storedWh / load * 3600.0).roundToLong()
        return Autonomy(
            loadW = Math.round(load * 10.0) / 10.0,
            loadBasis = basis,
            storedWh = stored,
            runtimeSeconds = runtime,
            outageSeconds = outageRemainingSeconds,
            coversNextOutage = outageRemainingSeconds?.let { runtime >= it },
        )
    }
}
