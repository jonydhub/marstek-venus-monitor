package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.util.Locale

/** Read-only telemetry snapshot consumed by the dashboard and home-screen widgets. */
data class BatterySnapshot(
    val socPercent: Int?,
    val batteryCapacityWh: Double?,
    val ratedCapacityWh: Double?,
    val temperatureCelsius: Double?,
    val batteryPowerWatts: Double?,
    val direction: BatteryDirection,
    val etaSeconds: Long?,
    val collectedAt: String?,
    val solarPowerWatts: Double? = null,
    /** False when [batteryPowerWatts] is measured (ES ongrid/offgrid power); true = capacity-trend estimate. */
    val batteryPowerEstimated: Boolean = true,
    val gridPowerWatts: Double? = null,
    val offgridPowerWatts: Double? = null,
    val totalPvEnergyWh: Double? = null,
    val totalGridInputEnergyWh: Double? = null,
    val totalGridOutputEnergyWh: Double? = null,
    val totalLoadEnergyWh: Double? = null,
    val meterPhaseAPowerWatts: Double? = null,
    val meterPhaseBPowerWatts: Double? = null,
    val meterPhaseCPowerWatts: Double? = null,
    val meterTotalPowerWatts: Double? = null,
    val meterInputEnergyWh: Double? = null,
    val meterOutputEnergyWh: Double? = null,
    val operatingMode: String? = null,
    val ctState: Int? = null,
    val powerOutageJson: String? = null,
    val insightsJson: String? = null,
    /** The server/poller got no answer from the battery (ES status failed or `connection.lost`): light is unknown. */
    val batteryLinkLost: Boolean = false,
) {
    val powerOutage: PowerOutageInfo? by lazy {
        powerOutageJson?.let { raw -> runCatching { PowerOutageParser.parse(JSONObject(raw)) }.getOrNull() }
    }

    val insights: BatteryInsights? by lazy {
        insightsJson?.let { raw -> runCatching { BatteryInsightsParser.parse(JSONObject(raw)) }.getOrNull() }
    }
}

enum class BatteryDirection {
    CHARGING,
    DISCHARGING,
    IDLE,
    UNKNOWN;

    companion object {
        fun fromApi(value: String?): BatteryDirection = when (value?.lowercase()) {
            "charging" -> CHARGING
            "discharging" -> DISCHARGING
            "idle" -> IDLE
            else -> UNKNOWN
        }
    }
}

object BatterySnapshotParser {
    fun parse(root: JSONObject): BatterySnapshot {
        val methods = root.optJSONObject("methods") ?: JSONObject()
        val battery = methods.optJSONObject("Bat.GetStatus")?.optJSONObject("data")
            ?: throw IllegalArgumentException("Bat.GetStatus data is missing")
        val energy = methods.optJSONObject("ES.GetStatus")?.optJSONObject("data") ?: JSONObject()
        val mode = methods.optJSONObject("ES.GetMode")?.optJSONObject("data") ?: JSONObject()
        val meter = methods.optJSONObject("EM.GetStatus")?.optJSONObject("data") ?: JSONObject()
        val flow = root.optJSONObject("battery_flow") ?: JSONObject()

        val direction = if (flow.optBoolean("available", false)) {
            BatteryDirection.fromApi(flow.optString("direction").takeIf(String::isNotBlank))
        } else {
            BatteryDirection.UNKNOWN
        }

        val chargeEta = flow.optFiniteLong("charge_eta_seconds")
        val dischargeEta = flow.optFiniteLong("discharge_eta_seconds")
        val eta = when (direction) {
            BatteryDirection.CHARGING -> chargeEta
            BatteryDirection.DISCHARGING -> dischargeEta
            BatteryDirection.IDLE, BatteryDirection.UNKNOWN -> null
        }

        return BatterySnapshot(
            socPercent = battery.optFiniteDouble("soc")?.toInt()?.takeIf { it in 0..100 },
            batteryCapacityWh = battery.optFiniteDouble("bat_capacity"),
            ratedCapacityWh = battery.optFiniteDouble("rated_capacity") ?: energy.optFiniteDouble("bat_cap"),
            temperatureCelsius = battery.optFiniteDouble("bat_temp"),
            batteryPowerWatts = if (direction == BatteryDirection.UNKNOWN) null else flow.optFiniteDouble("power_w"),
            direction = direction,
            etaSeconds = eta,
            collectedAt = root.optString("collected_at").takeIf(String::isNotBlank),
            batteryPowerEstimated = flow.optBoolean("estimated", true),
            solarPowerWatts = PowerFields.read(energy, "pv_power"),
            gridPowerWatts = PowerFields.read(energy, "ongrid_power"),
            offgridPowerWatts = PowerFields.read(energy, "offgrid_power"),
            totalPvEnergyWh = energy.optFiniteDouble("total_pv_energy"),
            totalGridInputEnergyWh = energy.optFiniteDouble("total_grid_input_energy"),
            totalGridOutputEnergyWh = energy.optFiniteDouble("total_grid_output_energy"),
            totalLoadEnergyWh = energy.optFiniteDouble("total_load_energy"),
            meterPhaseAPowerWatts = meter.optFiniteDouble("a_power"),
            meterPhaseBPowerWatts = meter.optFiniteDouble("b_power"),
            meterPhaseCPowerWatts = meter.optFiniteDouble("c_power"),
            meterTotalPowerWatts = meter.optFiniteDouble("total_power"),
            meterInputEnergyWh = meter.optFiniteDouble("input_energy"),
            meterOutputEnergyWh = meter.optFiniteDouble("output_energy"),
            operatingMode = mode.optString("mode").takeIf(String::isNotBlank),
            ctState = (mode.optFiniteDouble("ct_state") ?: meter.optFiniteDouble("ct_state"))?.toInt()?.takeIf { it in 0..1 },
            powerOutageJson = root.optJSONObject("power_outage")?.toString(),
            insightsJson = root.optJSONObject("insights")?.toString(),
            // Server ≥ 1.2 reports 3 failed polls in a row as `connection.lost`; older servers only per-method `ok`.
            batteryLinkLost = root.optJSONObject("connection")?.optBoolean("lost", false)
                ?: (methods.optJSONObject("ES.GetStatus")?.let { it.has("ok") && !it.optBoolean("ok", true) } == true),
        )
    }

    private fun JSONObject.optFiniteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf(Double::isFinite)
    }

    private fun JSONObject.optFiniteLong(key: String): Long? =
        optFiniteDouble(key)?.takeIf { it >= 0.0 }?.toLong()
}

object BatteryDisplayFormatter {
    private val uk = Locale.forLanguageTag("uk-UA")

    fun formatKwh(wattHours: Double?): String {
        if (wattHours == null || !wattHours.isFinite()) return "—"
        return String.format(uk, "%.2f\u00A0кВт·год", wattHours / 1000.0)
    }

    fun formatPowerKw(watts: Double?): String {
        if (watts == null || !watts.isFinite()) return "—"
        val sign = when {
            watts > 0.0 -> "+"
            watts < 0.0 -> "−"
            else -> ""
        }
        return "≈ $sign${String.format(uk, "%.2f", kotlin.math.abs(watts) / 1000.0)} кВт"
    }

    fun formatSignedWatts(watts: Double?): String {
        if (watts == null || !watts.isFinite()) return "—"
        val magnitude = String.format(uk, "%.0f", kotlin.math.abs(watts))
        val sign = when {
            watts > 0.0 -> "+"
            watts < 0.0 -> "−"
            else -> ""
        }
        return "$sign$magnitude\u00A0Вт"
    }

    fun formatCompactFlowPower(direction: BatteryDirection, watts: Double?): String {
        if (watts == null || !watts.isFinite() || direction == BatteryDirection.UNKNOWN) return "—"
        val value = String.format(uk, "%.2f", kotlin.math.abs(watts) / 1000.0)
        return when (direction) {
            BatteryDirection.CHARGING -> "↑≈$value кВт"
            BatteryDirection.DISCHARGING -> "↓≈$value кВт"
            BatteryDirection.IDLE -> "↔$value кВт"
            BatteryDirection.UNKNOWN -> "—"
        }
    }
}

object EtaTextFormatter {
    fun formatSeconds(seconds: Long?): String {
        if (seconds == null) return "—"
        if (seconds <= 0L) return "Готово"

        val totalMinutes = (seconds + 59L) / 60L
        val days = totalMinutes / (24L * 60L)
        val remainingHours = (totalMinutes % (24L * 60L)) / 60L
        val remainingMinutes = totalMinutes % 60L

        return when {
            days > 0L -> "$days д ${remainingHours} год"
            // «1 год», «2 год 5 хв» — never «1 год 0 хв».
            totalMinutes >= 60L -> if (remainingMinutes == 0L) "${totalMinutes / 60L} год" else "${totalMinutes / 60L} год ${remainingMinutes} хв"
            else -> "$totalMinutes хв"
        }
    }

}
