package com.doloh.marstekwidget.domain

import kotlin.math.abs

/**
 * The big power next to the SOC on the main screen (port of the web `powerBadge`, keep in sync):
 * «↓ 445 Вт» while the battery discharges, «↑ 1,1 кВт» while it charges, «≈» in front when the power is the
 * capacity-trend estimate. Null when idle / unknown / without a power reading or below [MIN_WATTS].
 */
data class PowerBadge(val text: String, val charging: Boolean)

object PowerBadges {
    const val MIN_WATTS = 10.0

    fun of(snapshot: BatterySnapshot?, linkLost: Boolean = false): PowerBadge? {
        snapshot ?: return null
        if (linkLost) return null
        val charging = when (snapshot.direction) {
            BatteryDirection.CHARGING -> true
            BatteryDirection.DISCHARGING -> false
            else -> return null
        }
        val watts = snapshot.batteryPowerWatts?.takeIf { it.isFinite() && abs(it) >= MIN_WATTS } ?: return null
        val approx = if (snapshot.batteryPowerEstimated) "≈\u00A0" else ""
        return PowerBadge("${if (charging) "↑" else "↓"}\u00A0$approx${WidgetSummaryResolver.formatKw(watts)}", charging)
    }
}
