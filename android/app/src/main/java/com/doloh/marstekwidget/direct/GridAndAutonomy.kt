package com.doloh.marstekwidget.direct

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Loads sit on the battery's backup output: a draw above the threshold means the grid is absent.
 * Same sign convention as `ongrid_power` (power out of the device > 0): a negative `offgrid_power` (e.g. the
 * −14 W a Venus E reports as uint16 65522 in UPS mode, see [com.doloh.marstekwidget.domain.PowerFields]) is
 * not a load draw and never means «світло зникло».
 */
object GridStateDetector {
    const val DEFAULT_THRESHOLD_W = 5.0

    fun detect(offgridPowerW: Double?, deviceOk: Boolean, thresholdW: Double = DEFAULT_THRESHOLD_W): String {
        if (!deviceOk || offgridPowerW == null || !offgridPowerW.isFinite()) return "unknown"
        return if (offgridPowerW >= thresholdW) "off" else "on"
    }
}

data class Autonomy(
    val loadW: Double?,
    val loadBasis: String?,
    val storedWh: Long?,
    val runtimeSeconds: Long?,
    val outageSeconds: Long?,
    val coversNextOutage: Boolean?,
    val usableWh: Long? = null,
    val floorPct: Double? = null,
    val floorWh: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("load_w", loadW ?: JSONObject.NULL)
        .put("load_basis", loadBasis ?: JSONObject.NULL)
        .put("stored_wh", storedWh ?: JSONObject.NULL)
        .put("usable_wh", usableWh ?: JSONObject.NULL)
        .put("floor_pct", floorPct ?: JSONObject.NULL)
        .put("floor_wh", floorWh ?: JSONObject.NULL)
        .put("runtime_seconds", runtimeSeconds ?: JSONObject.NULL)
        .put("outage_seconds", outageSeconds ?: JSONObject.NULL)
        .put("covers_next_outage", coversNextOutage ?: JSONObject.NULL)
}

/**
 * Port of outage.py `_autonomy`: runtime = usable energy above the discharge floor / load.
 * Load: (1) during an outage the measured backup output; (2) the capacity-trend discharge; (3) configured average;
 * (4) the 24 h measured backup average.
 */
object AutonomyCalculator {
    fun calculate(
        gridState: String,
        storedWh: Double?,
        flow: BatteryFlow?,
        expectedLoadW: Double?,
        measuredLoadW: Double?,
        outageRemainingSeconds: Long?,
        backupOutputW: Double? = null,
        ratedWh: Double? = null,
        socPercent: Double? = null,
        floorPct: Double? = null,
        backupThresholdW: Double = GridStateDetector.DEFAULT_THRESHOLD_W,
    ): Autonomy {
        val none = Autonomy(null, null, null, null, null, null)
        if (storedWh == null || !storedWh.isFinite() || storedWh < 0) return none
        val stored = Math.round(storedWh)
        val rated = ratedWh?.takeIf { it.isFinite() && it > 0 }
            ?: socPercent?.takeIf { it.isFinite() && it > 0 }?.let { storedWh / it * 100.0 }
        val floorWh = if (floorPct != null && floorPct > 0 && rated != null) rated * floorPct / 100.0 else null
        val usable = (storedWh - (floorWh ?: 0.0)).coerceAtLeast(0.0)
        val base = none.copy(storedWh = stored, outageSeconds = outageRemainingSeconds, usableWh = Math.round(usable),
            floorPct = floorWh?.let { floorPct }, floorWh = floorWh?.let { Math.round(it) })
        val measured = flow?.powerW
        val backup = backupOutputW?.takeIf(Double::isFinite)
        val (load, basis) = when {
            gridState == "off" && backup != null && abs(backup) >= backupThresholdW -> abs(backup) to "measured_backup_output"
            gridState == "off" && flow?.direction == "discharging" && measured != null -> abs(measured) to "measured_discharge"
            expectedLoadW != null && expectedLoadW > 0 -> expectedLoadW to "configured_average"
            measuredLoadW != null && measuredLoadW > 0 -> measuredLoadW to "measured_average_24h"
            else -> null to null
        }
        if (load == null || !load.isFinite() || load <= 0.0) return base
        val runtime = (usable / load * 3600.0).roundToLong()
        return base.copy(
            loadW = Math.round(load * 10.0) / 10.0,
            loadBasis = basis,
            runtimeSeconds = runtime,
            coversNextOutage = outageRemainingSeconds?.let { runtime >= it },
        )
    }
}
