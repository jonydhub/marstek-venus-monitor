package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.OffsetDateTime

data class SocPoint(val epochMillis: Long, val soc: Int)

/** Seconds since local midnight charging / discharging / without grid (backup output drawing); gaps count as nothing. */
data class TodayDurations(val chargeSeconds: Long, val dischargeSeconds: Long, val outageSeconds: Long)

data class TimeSpan(val startMillis: Long, val endMillis: Long)

/** light_lost / light_returned / charging_started / charged_full markers on the 24 h chart. */
data class ChartEvent(val epochMillis: Long, val kind: String)

object TodayFormatter {
    /** «1г 4хв», «57хв». */
    fun short(seconds: Long?): String {
        seconds ?: return "—"
        val minutes = (seconds + 30) / 60
        val hours = minutes / 60
        return if (hours > 0) "${hours}г ${minutes % 60}хв" else "${minutes}хв"
    }

    /** Compact one-liner for tight widgets: «З 1г 4хв · Р 9г 57хв · Без світла 10г 3хв» (zero parts dropped). */
    fun compact(today: TodayDurations?): String? {
        today ?: return null
        val parts = listOfNotNull(
            today.chargeSeconds.takeIf { it >= 60 }?.let { "З ${short(it)}" },
            today.dischargeSeconds.takeIf { it >= 60 }?.let { "Р ${short(it)}" },
            today.outageSeconds.takeIf { it >= 60 }?.let { "Без світла ${short(it)}" },
        )
        return if (parts.isEmpty()) "Сьогодні без заряду, розряду і відключень" else parts.joinToString(" · ")
    }
}

/** Optional server-side `insights` block: backup-output statistics and a 24 h SOC history. */
data class BatteryInsights(
    val backupOutputWatts: Double?,
    val backupAverageWatts24h: Double?,
    val backupAverageCoverageHours: Double?,
    val backupEnergyTodayWh: Double?,
    val temperatureCelsius: Double?,
    val socHistory: List<SocPoint>,
    val today: TodayDurations? = null,
    val outagePeriods: List<TimeSpan> = emptyList(),
    val chartEvents: List<ChartEvent> = emptyList(),
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
            today = block.optJSONObject("today")?.let { t ->
                TodayDurations(t.optLong("charge_seconds"), t.optLong("discharge_seconds"), t.optLong("outage_seconds"))
            },
            outagePeriods = buildList {
                val arr = block.optJSONArray("outage_periods")
                if (arr != null) for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val a = parseTime(p.opt("start")) ?: continue
                    val b = parseTime(p.opt("end")) ?: continue
                    if (b > a) add(TimeSpan(a, b))
                }
            },
            chartEvents = buildList {
                val arr = block.optJSONArray("chart_events")
                if (arr != null) for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val t = parseTime(e.opt("t")) ?: continue
                    add(ChartEvent(t, e.optString("kind")))
                }
            },
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
