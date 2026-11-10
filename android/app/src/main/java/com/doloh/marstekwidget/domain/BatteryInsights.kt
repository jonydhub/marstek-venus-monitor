package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.OffsetDateTime

data class SocPoint(val epochMillis: Long, val soc: Int)

/** Optional server-side `insights` block: backup-output statistics and a 24 h SOC history. */
data class BatteryInsights(
    val backupOutputWatts: Double?,
    val backupAverageWatts24h: Double?,
    val backupAverageCoverageHours: Double?,
    val backupEnergyTodayWh: Double?,
    val temperatureCelsius: Double?,
    val socHistory: List<SocPoint>,
)

object BatteryInsightsParser {
    fun parse(block: JSONObject?): BatteryInsights? {
        block ?: return null
        val history = block.optJSONArray("soc_history")
        val points = buildList {
            if (history != null) for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                val soc = item.finite("soc")?.toInt()?.takeIf { it in 0..100 } ?: continue
                val millis = parseTime(item.opt("t")) ?: continue
                add(SocPoint(millis, soc))
            }
        }.sortedBy(SocPoint::epochMillis)
        return BatteryInsights(
            backupOutputWatts = block.finite("backup_output_w"),
            backupAverageWatts24h = block.finite("backup_avg_w_24h"),
            backupAverageCoverageHours = block.finite("backup_avg_coverage_hours"),
            backupEnergyTodayWh = block.finite("backup_energy_today_wh"),
            temperatureCelsius = block.finite("temperature_c"),
            socHistory = points,
        )
    }

    private fun parseTime(raw: Any?): Long? = when (raw) {
        is Number -> raw.toDouble().takeIf(Double::isFinite)?.let { if (it < 1e12) (it * 1000).toLong() else it.toLong() }
        is String -> runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        else -> null
    }

    private fun JSONObject.finite(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf(Double::isFinite)
    }
}
