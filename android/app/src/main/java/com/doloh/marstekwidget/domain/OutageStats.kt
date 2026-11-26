package com.doloh.marstekwidget.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One measured outage (insights.today.outages / /api/stats outages): energy from the station's backup output, SOC. */
data class OutageDetail(
    val startMillis: Long,
    val endMillis: Long,
    val seconds: Long,
    val energyWh: Double?,
    val avgW: Int?,
    val socStart: Int?,
    val socEnd: Int?,
    val ongoing: Boolean,
)

/** One battery charge session (/api/stats charges); [estimated] = mostly the capacity-trend estimate. */
data class ChargeSession(
    val startMillis: Long,
    val endMillis: Long,
    val seconds: Long,
    val energyWh: Double?,
    val avgW: Int?,
    val socStart: Int?,
    val socEnd: Int?,
    val estimated: Boolean,
    val ongoing: Boolean,
)

/** Per-day totals of «Статистика» (same fields as the server's /api/stats days). */
data class StatsDay(
    val date: LocalDate,
    val isToday: Boolean,
    val offSeconds: Long,
    val outages: Int,
    val outageEnergyWh: Double,
    val chargeWh: Double,
    val dischargeWh: Double,
) {
    val hasData: Boolean get() = isToday || offSeconds > 0 || outages > 0 || outageEnergyWh > 0 || chargeWh > 0 || dischargeWh > 0
}

/** «Статистика»: days newest first, outages and charges newest first. */
data class StatsReport(
    val days: List<StatsDay>,
    val outages: List<OutageDetail>,
    val charges: List<ChargeSession>,
    /** «Лічильники» from the server; null for an older server and in direct mode (see [Counters.fromDays]). */
    val counters: EnergyCounters? = null,
)

object StatsParser {
    fun time(raw: Any?): Long? = when (raw) {
        is Number -> raw.toDouble().takeIf(Double::isFinite)?.let { if (it < 1e12) (it * 1000).toLong() else it.toLong() }
        is String -> runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        else -> null
    }

    private fun JSONObject.num(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key, Double.NaN).takeIf(Double::isFinite)

    fun outage(o: JSONObject): OutageDetail? {
        val a = time(o.opt("start")) ?: return null
        val b = time(o.opt("end")) ?: return null
        if (b < a) return null
        return OutageDetail(a, b, o.num("seconds")?.toLong() ?: ((b - a) / 1000), o.num("energy_wh"), o.num("avg_w")?.toInt(),
            o.num("soc_start")?.toInt(), o.num("soc_end")?.toInt(), o.optBoolean("ongoing", false))
    }

    fun charge(o: JSONObject): ChargeSession? {
        val a = time(o.opt("start")) ?: return null
        val b = time(o.opt("end")) ?: return null
        if (b < a) return null
        return ChargeSession(a, b, o.num("seconds")?.toLong() ?: ((b - a) / 1000), o.num("energy_wh"), o.num("avg_w")?.toInt(),
            o.num("soc_start")?.toInt(), o.num("soc_end")?.toInt(), o.optBoolean("estimated", false), o.optBoolean("ongoing", false))
    }

    fun outages(arr: JSONArray?): List<OutageDetail>? = arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::outage) } }

    /** GET /api/stats body. */
    fun parse(root: JSONObject): StatsReport {
        val days = root.optJSONArray("days")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
                StatsDay(date, o.optBoolean("is_today"), o.optLong("off_seconds"), o.optInt("outages"), o.num("outage_energy_wh") ?: 0.0,
                    o.num("charge_wh") ?: 0.0, o.num("discharge_wh") ?: 0.0)
            }
        }.orEmpty()
        val charges = root.optJSONArray("charges")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::charge) } }.orEmpty()
        return StatsReport(days, outages(root.optJSONArray("outages")).orEmpty(), charges, Counters.parse(root.optJSONObject("counters")))
    }
}

/**
 * «Фактично» outage labels above the bar and the energy line under it (port of the web, keep in sync).
 * A span from 00:00 → «до HH:MM»; an ongoing one → «з HH:MM»; otherwise edge / merged labels like schedule windows.
 */
object ActualOutageLabels {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private const val NOW_SLACK_MILLIS = 300_000L

    fun segments(outages: List<OutageDetail>, nowMillis: Long): List<ScheduleBarModel.Segment> {
        val date = Instant.ofEpochMilli(nowMillis).atZone(kyiv).toLocalDate()
        val start = date.atStartOfDay(kyiv).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli()
        val span = (end - start).toDouble()
        fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(kyiv).format(clock)
        return outages.mapNotNull { o ->
            val a = maxOf(o.startMillis, start)
            val b = minOf(o.endMillis, nowMillis, end)
            if (b <= a) return@mapNotNull null
            val ongoing = o.ongoing || nowMillis - o.endMillis < NOW_SLACK_MILLIS
            val label = when {
                ongoing -> "з ${hm(a)}"
                a == start -> "до ${hm(b)}"
                else -> ""
            }
            ScheduleBarModel.Segment(((a - start) / span).toFloat(), ((b - start) / span).toFloat(), false, hm(a), hm(b), label)
        }
    }

    /** Off spans ≥ 60 s of the timeline, for servers without insights.today.outages (labels only, no energy). */
    fun fromTimeline(timeline: List<LightSpan>?, nowMillis: Long): List<OutageDetail> = timeline.orEmpty()
        .filter { it.state == "off" && it.endMillis - it.startMillis >= 60_000L }
        .map { OutageDetail(it.startMillis, it.endMillis, (it.endMillis - it.startMillis) / 1000, null, null, null, null,
            nowMillis - it.endMillis < NOW_SLACK_MILLIS) }

    /** «Без світла 37 хв · зі станції 0,35 кВт·год»; null without outages; energy part dropped when 0 / unknown. */
    fun energyLine(outages: List<OutageDetail>, energyWh: Double?): String? {
        if (outages.isEmpty()) return null
        val seconds = outages.sumOf { it.seconds }
        val energy = energyWh?.takeIf { it > 0 }?.let { TodayFormatter.energy(it) }
        return "Без світла ${TodayFormatter.short(seconds)}" + (energy?.let { " · зі станції $it" } ?: "")
    }
}

/** Texts of the «Статистика» screen (port of the web, keep in sync). */
object StatsTexts {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val uk: Locale = Locale.forLanguageTag("uk-UA")
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private const val NBSP = '\u00A0'

    data class Tile(val title: String, val value: String, val sub: String)

    /** «N відключення» (n%10 in 1..4, not 11–14) / «N відключень» (0, 5–20, 25…). */
    fun outagesCount(n: Int): String = if (n % 10 in 1..4 && n % 100 !in 11..14) "$n відключення" else "$n відключень"

    /** «0,60» — kWh without the unit. */
    fun kwh(wh: Double): String = String.format(uk, "%.2f", wh / 1000.0)

    /** «Пт 20.11». */
    fun dayLabel(millis: Long): String {
        val d = Instant.ofEpochMilli(millis).atZone(kyiv).toLocalDate()
        return d.dayOfWeek.getDisplayName(TextStyle.SHORT, uk).replaceFirstChar { it.titlecase(uk) } +
            " %02d.%02d".format(d.dayOfMonth, d.monthValue)
    }

    private fun hm(millis: Long) = Instant.ofEpochMilli(millis).atZone(kyiv).format(clock)

    fun tiles(report: StatsReport): List<Tile> {
        val off = report.days.sumOf { it.offSeconds }
        val energy = report.days.sumOf { it.outageEnergyWh }
        val charge = report.days.sumOf { it.chargeWh }
        val longest = report.outages.maxByOrNull { it.seconds }
        return listOf(
            Tile("Без світла", TodayFormatter.short(off), outagesCount(report.outages.size)),
            Tile("Зі станції", TodayFormatter.energy(energy) ?: "—", "за відключення"),
            Tile("Заряджено", TodayFormatter.energy(charge) ?: "—", "у батарею"),
            Tile("Найдовше", longest?.let { TodayFormatter.short(it.seconds) } ?: "—",
                longest?.let { "${dayLabel(it.startMillis)}, ${hm(it.startMillis)}" } ?: "—"),
        )
    }

    /** «без світла 3 год 9 хв · зі станції 0,60 · заряд 1,63 кВт·год» / «без відключень · заряд 1,63 кВт·год». */
    fun dayLine(day: StatsDay): String {
        val charge = "заряд ${kwh(day.chargeWh)}${NBSP}кВт·год"
        return if (day.offSeconds == 0L) "без відключень · $charge"
        else "без світла ${TodayFormatter.short(day.offSeconds)} · зі станції ${kwh(day.outageEnergyWh)} · $charge"
    }

    private fun socPart(a: Int?, b: Int?) = if (a != null && b != null) "$a → $b$NBSP%" else null

    /** («Пт 20.11 · 21:06–00:15 · 3 год 9 хв», «0,60 кВт·год зі станції · ≈190 Вт · заряд 100 → 86 %»). */
    fun outageLines(o: OutageDetail): Pair<String, String> {
        val head = if (o.ongoing) "${dayLabel(o.startMillis)} · з ${hm(o.startMillis)} · ${TodayFormatter.short(o.seconds)} · триває"
        else "${dayLabel(o.startMillis)} · ${hm(o.startMillis)}–${hm(o.endMillis)} · ${TodayFormatter.short(o.seconds)}"
        val detail = listOfNotNull(
            o.energyWh?.let { TodayFormatter.energy(it) }?.let { "$it зі станції" },
            o.avgW?.let { "≈$it${NBSP}Вт" },
            socPart(o.socStart, o.socEnd)?.let { "заряд $it" },
        ).joinToString(" · ")
        return head to detail
    }

    /** («Сб 21.11 · 16:35–17:01 · 26 хв», «+0,48 кВт·год · ≈1095 Вт · 92 → 100 %»). */
    fun chargeLines(c: ChargeSession): Pair<String, String> {
        val head = "${dayLabel(c.startMillis)} · ${hm(c.startMillis)}–${hm(c.endMillis)} · ${TodayFormatter.short(c.seconds)}"
        val detail = listOfNotNull(
            c.energyWh?.let { (if (c.estimated) "≈$NBSP" else "") + "+" + TodayFormatter.energy(it) },
            c.avgW?.let { "≈$it${NBSP}Вт" },
            socPart(c.socStart, c.socEnd),
        ).joinToString(" · ")
        return head to detail
    }

    /** «Сьогодні» / «Вчора» / «Пт, 20.11» (same as the week view). */
    fun dayTitle(day: StatsDay, today: LocalDate): String =
        LightWeek.title(WeekDay(day.date, day.isToday, 0, 0, 0, 0, 0, emptyList()), today)
}
