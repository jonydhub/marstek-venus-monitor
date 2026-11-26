package com.doloh.marstekwidget.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import com.doloh.marstekwidget.data.DirectEngine
import com.doloh.marstekwidget.data.MarstekApiClient
import com.doloh.marstekwidget.domain.StatsReport
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val settings = SettingsStore(this).load()
        val direct = settings.mode != ConnectionMode.SERVER
        val page = ui.page()
        page.addView(ui.topBar("Статистика", if (direct) "2 дні · з пам'яті телефона" else "7 днів"), ui.matchWrap())
        if (direct) page.addView(ui.hint("Повну статистику за тиждень видно в режимі сервера."))
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
