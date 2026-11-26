package com.doloh.marstekwidget.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Статистика» as compact tables: column titles once, numbers in the cells, outages / charges grouped by day.
 * Port of the web `statsDayCells` / `statsGroups` (keep in sync, same cases in server/tests/test_stats_table.py).
 */
object StatsTable {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private const val NBSP = '\u00A0'
    const val DASH = "—"

    val DAY_HEADER = listOf("", "Без світла", "Станція", "Заряд")
    val SESSION_HEADER = listOf("Час", "Тривалість", "кВт·год", "Заряд, %")

    data class Line(val cells: List<String>, val highlight: Boolean = false)
    data class Group(val title: String, val lines: List<Line>)

    private fun hm(millis: Long) = Instant.ofEpochMilli(millis).atZone(kyiv).format(clock)
    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(kyiv).toLocalDate()

    /** «0,72», «+0,84» or «—» when there is no energy. */
    fun kwh(wh: Double?, plus: Boolean = false): String =
        wh?.takeIf { it.isFinite() && it >= 5 }?.let { (if (plus) "+" else "") + StatsTexts.kwh(it) } ?: DASH

    /** «100→97» or «—». */
    fun soc(a: Int?, b: Int?): String = if (a != null && b != null) "$a→$b" else DASH

    /** «09:23–09:40», «з 17:06» (still running). */
    fun span(startMillis: Long, endMillis: Long, ongoing: Boolean): String =
        if (ongoing) "з ${hm(startMillis)}" else "${hm(startMillis)}–${hm(endMillis)}"

    /** One row per day with data: «Сьогодні | 1 год 16 хв | 0,72 | 0,84»; zero cells are «—». */
    fun dayLines(days: List<StatsDay>, today: LocalDate): List<Line> = days.filter { it.hasData }.map { d ->
        Line(listOf(
            StatsTexts.dayTitle(d, today),
            if (d.offSeconds >= 60) TodayFormatter.short(d.offSeconds) else DASH,
            if (d.offSeconds >= 60) kwh(d.outageEnergyWh) else DASH,
            kwh(d.chargeWh),
        ), highlight = d.offSeconds >= 60)
    }

    private fun <T> grouped(items: List<T>, today: LocalDate, start: (T) -> Long, line: (T) -> Line): List<Group> =
        items.groupBy { dateOf(start(it)) }.map { (date, list) ->
            Group(LightWeek.title(WeekDay(date, date == today, 0, 0, 0, 0, 0, emptyList()), today), list.map(line))
        }

    /** Outages by start day, newest first as delivered: «09:23–09:40 | 17 хв | 0,17 | 100→97». */
    fun outageGroups(outages: List<OutageDetail>, today: LocalDate): List<Group> =
        grouped(outages, today, { it.startMillis }) { o ->
            Line(listOf(span(o.startMillis, o.endMillis, o.ongoing), TodayFormatter.short(o.seconds), kwh(o.energyWh),
                soc(o.socStart, o.socEnd)), highlight = o.ongoing)
        }

    /** Charges by start day: «09:40–09:53 | 14 хв | +0,23 | 97→100» («≈» before an estimated energy). */
    fun chargeGroups(charges: List<ChargeSession>, today: LocalDate): List<Group> =
        grouped(charges, today, { it.startMillis }) { c ->
            val energy = kwh(c.energyWh, plus = true).let { if (c.estimated && it != DASH) "≈$NBSP$it" else it }
            Line(listOf(span(c.startMillis, c.endMillis, c.ongoing), TodayFormatter.short(c.seconds), energy,
                soc(c.socStart, c.socEnd)), highlight = c.ongoing)
        }
}
