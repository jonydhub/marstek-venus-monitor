package com.doloh.marstekwidget.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.doloh.marstekwidget.data.DirectEngine
import com.doloh.marstekwidget.data.MarstekApiClient
import com.doloh.marstekwidget.domain.StatsReport
import com.doloh.marstekwidget.domain.ReportTable
import com.doloh.marstekwidget.domain.StatsTable
import com.doloh.marstekwidget.domain.StatsTexts
import com.doloh.marstekwidget.settings.ConnectionMode
import com.doloh.marstekwidget.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** «Статистика»: off time, energy from the station, charges — tiles, per day, outages and charge sessions. */
class StatsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private lateinit var reportTitle: android.widget.TextView
    private lateinit var reportBody: LinearLayout
    private val reportTabs = mutableMapOf<String, android.widget.TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val settings = SettingsStore(this).load()
        val direct = settings.mode != ConnectionMode.SERVER
        val page = ui.page()
        page.addView(ui.topBar("Статистика", if (direct) "2 дні · з пам'яті телефона" else "7 днів"), ui.matchWrap())
        if (direct) page.addView(ui.hint("Повну статистику за тиждень видно в режимі сервера."))
        // «Звіт» (server only: built from the server's day totals) — week / month with the previous period
        if (!direct) page.addView(reportCard(settings), ui.matchWrap(8))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content, ui.matchWrap())
        val scroll = ui.scrollCentered(page, fillViewport = true)
        SystemBarsInsetHelper.apply(scroll, 0, 0, 0, 0)
        setContentView(scroll)
        message("Завантажую…")
        scope.launch {
            val report: StatsReport = if (direct) {
                runCatching { withContext(Dispatchers.IO) { DirectEngine.stats(this@StatsActivity, settings) } }
                    .getOrElse { message("Не вдалося порахувати (${it.javaClass.simpleName})"); return@launch }
            } else when (val r = MarstekApiClient(this@StatsActivity, settings, SettingsStore(this@StatsActivity).token()).fetchStats()) {
                is MarstekApiClient.StatsFetch.Ok -> r.report
                MarstekApiClient.StatsFetch.NotSupported -> { message("Оновіть сервер, щоб бачити статистику"); return@launch }
                is MarstekApiClient.StatsFetch.Failed -> { message("Не вдалося завантажити (${r.message})"); return@launch }
            }
            render(report)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun reportCard(settings: com.doloh.marstekwidget.settings.AppSettings) = ui.card().apply {
        val head = LinearLayout(this@StatsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this@StatsActivity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.label("ЗВІТ", 9f, Ui.DIM, true))
        reportTitle = ui.label("—", 14f, Ui.TEXT, true)
        texts.addView(reportTitle)
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf("week" to "Тиждень", "month" to "Місяць").forEach { (key, title) ->
            val tab = ui.label(title, 12f, Ui.MUTED, false).apply {
                setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { loadReport(settings, key) }
            }
            reportTabs[key] = tab
            head.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(4) })
        }
        addView(head)
        reportBody = LinearLayout(this@StatsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(8), 0, 0) }
        addView(reportBody)
        addView(ui.hint("Вартість — за тарифом 4,32 грн/кВт·год."))
        loadReport(settings, "week")
    }

    private fun loadReport(settings: com.doloh.marstekwidget.settings.AppSettings, period: String) {
        reportTabs.forEach { (key, tab) ->
            val on = key == period
            tab.setTextColor(if (on) Ui.TEXT else Ui.MUTED)
            tab.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tab.background = if (on) ui.rounded(Ui.SURFACE_RAISED, ui.dp(10), Ui.STROKE) else null
        }
        reportBody.removeAllViews()
        reportBody.addView(ui.label("Завантажую…", 12f, Ui.MUTED))
        scope.launch {
            val report = MarstekApiClient(this@StatsActivity, settings, SettingsStore(this@StatsActivity).token()).fetchReport(period)
            reportBody.removeAllViews()
            val table = report?.let(ReportTable::table)
            reportTitle.text = report?.title?.takeIf { it.isNotBlank() } ?: "—"
            if (table == null) {
                reportBody.addView(ui.label(if (report == null) "Оновіть сервер, щоб бачити звіт" else "Ще немає вимірів", 12f, Ui.MUTED))
                return@launch
            }
            val weights = if (table.head.size == 3) listOf(2.2f, 1.2f, 1.2f) else listOf(2.2f, 1.2f)
            reportBody.addView(ui.tableRow(table.head, weights, header = true))
            table.rows.forEach { row ->
                reportBody.addView(ui.tableRow(row, weights).also { line ->
                    (line.getChildAt(1) as? android.widget.TextView)?.let { c -> if (c.text != "—") { c.setTextColor(Ui.TEXT); c.setTypeface(c.typeface, android.graphics.Typeface.BOLD) } }
                })
            }
        }
    }

    private fun message(text: String) {
        content.removeAllViews()
        content.addView(ui.card().apply { addView(ui.label(text, 12f, Ui.MUTED, true)) }, ui.matchWrap(8))
    }

    private fun render(report: StatsReport) {
        content.removeAllViews()
        // 1. 2×2 tiles
        val tiles = StatsTexts.tiles(report)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tiles.chunked(2).forEachIndexed { row, pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, t ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    background = ui.rounded(Ui.SURFACE_RAISED, ui.dp(14), Ui.STROKE)
                    addView(ui.label(t.title, 11f, Ui.MUTED))
                    addView(ui.label(t.value, 17f, if (t.title == "Без світла" || t.title == "Найдовше") ChartColors.ACTUAL_OFF else Ui.TEXT, true))
                    addView(ui.label(t.sub, 10f, Ui.DIM))
                    contentDescription = "${t.title}: ${t.value}, ${t.sub}"
                }
                line.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 1) marginStart = ui.dp(8) })
            }
            grid.addView(line, ui.matchWrap(if (row == 0) 0 else 8))
        }
        content.addView(grid, ui.matchWrap(8))

        // 2. per day — one table, column titles once
        val today = LocalDate.now(KYIV)
        val days = ui.card()
        days.addView(ui.title("По днях · кВт·год"))
        val dayLines = StatsTable.dayLines(report.days, today)
        if (dayLines.isEmpty()) days.addView(ui.label("Ще немає вимірів", 12f, Ui.MUTED))
        else {
            days.addView(ui.tableRow(StatsTable.DAY_HEADER, DAY_WEIGHTS, header = true))
            dayLines.forEach { days.addView(ui.tableRow(it.cells, DAY_WEIGHTS, highlight = false, firstBold = true).also { row -> if (it.highlight) row.offCells() }) }
        }
        content.addView(days, ui.matchWrap(8))

        // 3–4. outages / charges — grouped by day
        content.addView(sessionCard("Відключення", StatsTable.outageGroups(report.outages, today), "Відключень не було"), ui.matchWrap(8))
        content.addView(sessionCard("Заряджання", StatsTable.chargeGroups(report.charges, today), "Заряджань не було"), ui.matchWrap(8))
        content.addView(ui.hint("Станція — енергія з резервного виходу, поки світла не було. Дрібні підзаряди 99→100 % не показано."), ui.matchWrap(6))
    }

    /** Coral «без світла» cell (the 2nd) of a day row with an outage. */
    private fun LinearLayout.offCells() {
        (getChildAt(1) as? android.widget.TextView)?.setTextColor(ChartColors.ACTUAL_OFF)
    }

    private fun sessionCard(title: String, groups: List<StatsTable.Group>, empty: String) = ui.card().apply {
        addView(ui.title(title))
        if (groups.isEmpty()) { addView(ui.label(empty, 12f, Ui.MUTED)); return@apply }
        addView(ui.tableRow(StatsTable.SESSION_HEADER, SESSION_WEIGHTS, header = true))
        groups.forEachIndexed { gi, g ->
            addView(ui.divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = ui.dp(if (gi == 0) 0 else 4) })
            addView(ui.label(g.title, 11f, Ui.TEXT, true).apply { setPadding(0, ui.dp(7), 0, ui.dp(1)) })
            g.lines.forEach { addView(ui.tableRow(it.cells, SESSION_WEIGHTS, highlight = it.highlight)) }
        }
    }

    private companion object {
        val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")
        val DAY_WEIGHTS = listOf(1.15f, 1.25f, 0.8f, 0.8f)
        val SESSION_WEIGHTS = listOf(1.35f, 1.15f, 0.85f, 0.85f)
    }
}
