package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** One column of «Лічильники»: energy (Wh), time without light (s) and the outage count. */
data class CounterTotals(
    val chargeWh: Double,
    val dischargeWh: Double,
    val outageEnergyWh: Double,
    val offSeconds: Long,
    val outages: Int,
)

/**
 * «Лічильники»: today / the stats window / everything since the server keeps day totals (GET /api/stats `counters`).
 * Direct mode builds it from the phone's own days — no [total] then.
 */
data class EnergyCounters(val since: LocalDate?, val windowDays: Int, val today: CounterTotals, val window: CounterTotals, val total: CounterTotals?)

object Counters {
    private fun JSONObject.totals(): CounterTotals = CounterTotals(
        optDouble("charge_wh", 0.0).takeIf(Double::isFinite) ?: 0.0,
        optDouble("discharge_wh", 0.0).takeIf(Double::isFinite) ?: 0.0,
        optDouble("outage_energy_wh", 0.0).takeIf(Double::isFinite) ?: 0.0,
        optLong("off_seconds"),
        optInt("outages"),
    )

    fun parse(o: JSONObject?): EnergyCounters? {
        o ?: return null
        val today = o.optJSONObject("today") ?: return null
        val window = o.optJSONObject("window") ?: return null
        return EnergyCounters(
            since = runCatching { LocalDate.parse(o.optString("since")) }.getOrNull(),
            windowDays = o.optInt("window_days", 7).coerceAtLeast(1),
            today = today.totals(),
            window = window.totals(),
            total = o.optJSONObject("total")?.totals(),
        )
    }

    private fun sum(days: List<StatsDay>) = CounterTotals(
        days.sumOf { it.chargeWh }, days.sumOf { it.dischargeWh }, days.sumOf { it.outageEnergyWh },
        days.sumOf { it.offSeconds }, days.sumOf { it.outages },
    )

    /** Direct mode: from the phone's days (newest first, today first); null without today. */
    fun fromDays(days: List<StatsDay>): EnergyCounters? {
        val today = days.firstOrNull()?.takeIf { it.isToday } ?: return null
        val covered = days.filter { it.hasData }
        return EnergyCounters(null, covered.size.coerceAtLeast(1), sum(listOf(today)), sum(covered), null)
    }
}

/** «Лічильники» as a table — same rules as the web `countersRows` / `countersNote` (keep in sync). */
object CountersTable {
    private val uk = Locale.forLanguageTag("uk-UA")
    private const val DASH = "—"

    data class Table(val head: List<String>, val rows: List<List<String>>)

    private fun daysWord(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "день"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "дні"
        else -> "днів"
    }

    private fun kwh(wh: Double) = if (wh >= 5) String.format(uk, "%.2f", wh / 1000.0) else DASH
    private fun hours(s: Long) = if (s >= 60) String.format(uk, "%.1f", s / 3600.0) else DASH

    fun table(c: EnergyCounters): Table {
        val cols = listOfNotNull(c.today, c.window, c.total)
        val head = listOfNotNull("", "Сьогодні", "${c.windowDays}\u00A0${daysWord(c.windowDays)}", c.total?.let { "Усього" })
        return Table(head, listOf(
            listOf("Заряджено") + cols.map { kwh(it.chargeWh) },
            listOf("Розряджено") + cols.map { kwh(it.dischargeWh) },
            listOf("Спожито без світла") + cols.map { kwh(it.outageEnergyWh) },
            listOf("Без світла, год") + cols.map { hours(it.offSeconds) },
        ))
    }

    /** «Усього — з 20.11.2026. Рахує сервер з вимірів (лічильники станції неповні).» */
    fun note(c: EnergyCounters): String = c.since?.let {
        "Усього — з ${"%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year)}. Рахує сервер з вимірів (лічильники станції неповні)."
    } ?: "З пам'яті телефона. «Усього» видно в режимі сервера."
}
