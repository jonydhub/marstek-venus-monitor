package com.doloh.marstekwidget.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.doloh.marstekwidget.data.DirectEngine
import com.doloh.marstekwidget.data.MarstekApiClient
import com.doloh.marstekwidget.domain.StatsReport
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
        page.addView(ui.label(if (direct) "Статистика · 2 дні" else "Статистика · 7 днів", 20f, Ui.TEXT, true)
            .apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
        if (direct) page.addView(ui.hint("Напряму телефон зберігає виміри лише 48 год — повну статистику видно в режимі сервера."))
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

        // 2. per day
        val today = LocalDate.now(KYIV)
        val days = ui.card()
        days.addView(ui.title("По днях"))
        val withData = report.days.filter { it.hasData }
        if (withData.isEmpty()) days.addView(ui.label("Ще немає вимірів", 12f, Ui.MUTED))
        withData.forEachIndexed { i, d ->
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(ui.label(StatsTexts.dayTitle(d, today), 12f, Ui.TEXT, true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            head.addView(ui.label(StatsTexts.dayLine(d), 11f, if (d.offSeconds > 0) ChartColors.ACTUAL_OFF else Ui.MUTED).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(8) })
            days.addView(head, ui.matchWrap(if (i == 0) 0 else 8))
        }
        content.addView(days, ui.matchWrap(8))

        // 3. outages
        val outages = ui.card()
        outages.addView(ui.title("Відключення"))
        if (report.outages.isEmpty()) outages.addView(ui.label("Відключень не було", 12f, Ui.MUTED))
        report.outages.forEachIndexed { i, o ->
            val (head, detail) = StatsTexts.outageLines(o)
            outages.addView(ui.label(head, 12f, if (o.ongoing) ChartColors.ACTUAL_OFF else Ui.TEXT, true), ui.matchWrap(if (i == 0) 0 else 8))
            if (detail.isNotEmpty()) outages.addView(ui.label(detail, 11f, Ui.MUTED))
        }
        content.addView(outages, ui.matchWrap(8))

        // 4. charges
        val charges = ui.card()
        charges.addView(ui.title("Заряджання"))
        if (report.charges.isEmpty()) charges.addView(ui.label("Заряджань не було", 12f, Ui.MUTED))
        report.charges.forEachIndexed { i, c ->
            val (head, detail) = StatsTexts.chargeLines(c)
            charges.addView(ui.label(head, 12f, Ui.TEXT, true), ui.matchWrap(if (i == 0) 0 else 8))
            if (detail.isNotEmpty()) charges.addView(ui.label(detail, 11f, Ui.MUTED))
        }
        content.addView(charges, ui.matchWrap(8))
    }

    private companion object {
        val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")
    }
}
