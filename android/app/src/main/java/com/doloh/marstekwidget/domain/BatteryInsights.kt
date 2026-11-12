package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.OffsetDateTime

data class SocPoint(val epochMillis: Long, val soc: Int)

/**
 * Since local midnight: seconds charging / discharging / without light (confirmed timeline; gaps count as nothing) and
 * the battery energy in / out (Wh; `*Estimated` = partly the capacity-trend estimate → shown with «≈»).
 */
data class TodayDurations(
    val chargeSeconds: Long,
    val dischargeSeconds: Long,
    val outageSeconds: Long,
    val chargeWh: Double? = null,
    val dischargeWh: Double? = null,
    val chargeEstimated: Boolean = false,
    val dischargeEstimated: Boolean = false,
)

/** One span of today's confirmed light timeline («Фактично»): on / off / unknown. */
data class LightSpan(val startMillis: Long, val endMillis: Long, val state: String)

data class TimeSpan(val startMillis: Long, val endMillis: Long)

/** light_lost / light_returned / charging_started / charged_full markers on the 24 h chart. */
data class ChartEvent(val epochMillis: Long, val kind: String)

object TodayFormatter {
    private const val NBSP = '\u00A0'
    private val uk = java.util.Locale.forLanguageTag("uk-UA")

    /** «0,81 кВт·год», «≈ 0,15 кВт·год» (part of it estimated from the capacity trend); null without data. */
    fun energy(wh: Double?, estimated: Boolean = false): String? = wh?.takeIf(Double::isFinite)?.let {
        (if (estimated) "≈$NBSP" else "") + String.format(uk, "%.2f", it / 1000.0) + "${NBSP}кВт·год"
    }

    /** «Заряджено 0,81 кВт·год» / «Розряджено ≈ 0,15 кВт·год». */
    fun chargedText(today: TodayDurations?): String? = today?.let { energy(it.chargeWh, it.chargeEstimated) }?.let { "Заряджено $it" }
    fun dischargedText(today: TodayDurations?): String? = today?.let { energy(it.dischargeWh, it.dischargeEstimated) }?.let { "Розряджено $it" }

    /** «1 год», «2 год 5 хв», «57 хв», «0 хв» — full unit words (never «1г 0хв»), value and unit never split. */
    fun short(seconds: Long?): String {
        seconds ?: return "—"
        val minutes = ((seconds + 30) / 60).coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$minutes${NBSP}хв"
            rest == 0L -> "$hours${NBSP}год"
            else -> "$hours${NBSP}год $rest${NBSP}хв"
        }
    }

    /** Whole hours once ≥ 1 h («2 год»), else minutes — for the shortest candidates. */
    fun rounded(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return if (minutes >= 60) "${Math.round(minutes / 60.0)}${NBSP}год" else "$minutes${NBSP}хв"
    }

    /** One line for 4×2 / 4×4: «Заряд 1 год · Розряд 2 год · Без світла 1 хв» (zero parts dropped, full words). */
    fun compact(today: TodayDurations?): String? = candidates(today).firstOrNull()?.removePrefix(LABEL)

    private const val LABEL = "Сьогодні: "

    /**
     * Labelled «Сьогодні: Заряд 1 год · Розряд 2 год 5 хв · Без світла 1 хв», longest first: exact, rounded hours,
     * then only the no-light total. Never single-letter abbreviations.
     */
    fun candidates(today: TodayDurations?): List<String> {
        today ?: return emptyList()
        fun parts(fmt: (Long) -> String) = listOfNotNull(
            today.chargeSeconds.takeIf { it >= 60 }?.let { "Заряд ${fmt(it)}" },
            today.dischargeSeconds.takeIf { it >= 60 }?.let { "Розряд ${fmt(it)}" },
            today.outageSeconds.takeIf { it >= 60 }?.let { "Без світла ${fmt(it)}" },
        )
        val exact = parts(::short)
        if (exact.isEmpty()) return listOf("Сьогодні без заряду, розряду і відключень", "Сьогодні без змін")
        // 4×2 / 4×4: the energy next to the durations when it fits — «Заряд 1 год (0,81 кВт·год)».
        val withEnergy = listOfNotNull(
            today.chargeSeconds.takeIf { it >= 60 }?.let { s -> "Заряд ${short(s)}" + (energy(today.chargeWh, today.chargeEstimated)?.let { " ($it)" } ?: "") },
            today.dischargeSeconds.takeIf { it >= 60 }?.let { s -> "Розряд ${short(s)}" + (energy(today.dischargeWh, today.dischargeEstimated)?.let { " ($it)" } ?: "") },
            today.outageSeconds.takeIf { it >= 60 }?.let { "Без світла ${short(it)}" },
        )
        val roundedEnergy = listOfNotNull(
            today.chargeSeconds.takeIf { it >= 60 }?.let { s -> "Заряд ${rounded(s)}" + (energy(today.chargeWh, today.chargeEstimated)?.let { " ($it)" } ?: "") },
            today.dischargeSeconds.takeIf { it >= 60 }?.let { s -> "Розряд ${rounded(s)}" + (energy(today.dischargeWh, today.dischargeEstimated)?.let { " ($it)" } ?: "") },
            today.outageSeconds.takeIf { it >= 60 }?.let { "Без світла ${rounded(it)}" },
        )
        return listOf(
            LABEL + withEnergy.joinToString(" · "),
            LABEL + roundedEnergy.joinToString(" · "),
            LABEL + exact.joinToString(" · "),
            LABEL + parts(::rounded).joinToString(" · "),
        ).plus(outageCandidates(today)).distinct()
    }

    /** 2×2: only the no-light total — «Сьогодні без світла 1 хв» / «Сьогодні без відключень». */
    fun outageCandidates(today: TodayDurations?): List<String> {
        today ?: return emptyList()
        val o = today.outageSeconds
        if (o < 60) return listOf("Сьогодні без відключень", "Без відключень")
        return listOf("Сьогодні без світла ${short(o)}", "Сьогодні без світла ${rounded(o)}", "Без світла ${rounded(o)}").distinct()
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
    /** Standby self-consumption at 100 % from the top-up cadence (W), null until one full cycle was seen. */
    val standbyWatts: Double? = null,
    /** Today's confirmed light on/off/unknown spans (insights.today.grid_timeline); null = server older than 1.3.7. */
    val lightTimeline: List<LightSpan>? = null,
    /** Last poll that charged from the grid (UPS top-up): watts and when. */
    val lastGridChargeWatts: Double? = null,
    val lastGridChargeMillis: Long? = null,
    /** 7-day mean backup draw while drawing (zero-load excluded) and how much real load it is based on. */
    val backupLoadMean7dWatts: Double? = null,
    val backupLoadCoverage7dSeconds: Double = 0.0,
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
            standbyWatts = block.finite("standby_w"),
            socHistory = points,
            today = block.optJSONObject("today")?.let { t ->
                TodayDurations(t.optLong("charge_seconds"), t.optLong("discharge_seconds"), t.optLong("outage_seconds"),
                    t.finite("charge_wh"), t.finite("discharge_wh"), t.optBoolean("charge_estimated", false),
                    t.optBoolean("discharge_estimated", false))
            },
            lightTimeline = block.optJSONObject("today")?.optJSONArray("grid_timeline")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val a = parseTime(s.opt("start")) ?: return@mapNotNull null
                    val b = parseTime(s.opt("end")) ?: return@mapNotNull null
                    if (b > a) LightSpan(a, b, s.optString("state", "unknown")) else null
                }
            },
            lastGridChargeWatts = block.optJSONObject("last_grid_charge")?.finite("w"),
            lastGridChargeMillis = block.optJSONObject("last_grid_charge")?.let { parseTime(it.opt("t")) },
            backupLoadMean7dWatts = block.finite("backup_load_mean_w_7d"),
            backupLoadCoverage7dSeconds = block.finite("backup_load_coverage_seconds_7d") ?: 0.0,
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
