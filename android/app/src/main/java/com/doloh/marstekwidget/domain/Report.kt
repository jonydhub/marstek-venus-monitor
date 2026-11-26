package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.util.Locale

/** One period of GET /api/report (server/report.py). */
data class ReportPeriod(
    val coveredDays: Int,
    val offSeconds: Long,
    val outages: Int,
    val outageEnergyWh: Double,
    val chargeWh: Double,
    val chargeCostUah: Double,
)

data class EnergyReport(val period: String, val title: String, val current: ReportPeriod, val previous: ReportPeriod?)

/** «Звіт» as a table — same rules as the web `reportRows` (keep in sync, server/tests/test_report_web.py). */
object ReportTable {
    private val uk = Locale.forLanguageTag("uk-UA")
    private const val DASH = "—"

    data class Table(val head: List<String>, val rows: List<List<String>>)

    private fun JSONObject.period(): ReportPeriod = ReportPeriod(
        optInt("covered_days"), optLong("off_seconds"), optInt("outages"),
        optDouble("outage_energy_wh", 0.0).takeIf(Double::isFinite) ?: 0.0,
        optDouble("charge_wh", 0.0).takeIf(Double::isFinite) ?: 0.0,
        optDouble("charge_cost_uah", 0.0).takeIf(Double::isFinite) ?: 0.0,
    )

    fun parse(root: JSONObject): EnergyReport? {
        val current = root.optJSONObject("current")?.period() ?: return null
        return EnergyReport(root.optString("period", "week"), root.optString("title"), current, root.optJSONObject("previous")?.period())
    }

    private fun kwh(wh: Double) = if (wh >= 5) String.format(uk, "%.2f", wh / 1000.0) else DASH

    fun table(r: EnergyReport): Table? {
        if (r.current.coveredDays <= 0) return null
        val cols = listOfNotNull(r.current, r.previous?.takeIf { it.coveredDays > 0 })
        val week = r.period == "week"
        val head = listOf("", if (week) "Цей тиждень" else "Цей місяць") + if (cols.size > 1) listOf("Минулий") else emptyList()
        return Table(head, listOf(
            listOf("Без світла") + cols.map { if (it.offSeconds >= 60) TodayFormatter.short(it.offSeconds).replace('\u00A0', ' ') else DASH },
            listOf("Відключень") + cols.map { if (it.outages > 0) it.outages.toString() else DASH },
            listOf("Станція віддала, кВт·год") + cols.map { kwh(it.outageEnergyWh) },
            listOf("Заряджено, кВт·год") + cols.map { kwh(it.chargeWh) },
            listOf("Вартість заряду, грн") + cols.map { if (it.chargeCostUah > 0) String.format(uk, "%.2f", it.chargeCostUah) else DASH },
        ))
    }
}
