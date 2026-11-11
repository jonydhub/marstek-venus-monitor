package com.doloh.marstekwidget.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.data.TelemetryRepository
import com.doloh.marstekwidget.domain.LightKind
import com.doloh.marstekwidget.domain.LightStatusResolver
import com.doloh.marstekwidget.domain.OutageSourceLinks
import com.doloh.marstekwidget.domain.PowerOutageFormatter
import com.doloh.marstekwidget.domain.ScheduleDays
import com.doloh.marstekwidget.outage.OutageRegions
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.widget.WidgetFetchState
import com.doloh.marstekwidget.widget.WidgetPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** «Графік відключень»: region, queue, source, today + tomorrow with hour scale, windows, refresh, official site. */
class ScheduleActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private lateinit var refreshButton: TextView
    private var refreshing = false

    private val showcase: Boolean
        get() = intent.getBooleanExtra("showcase", false) &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val page = ui.page()
        page.addView(ui.label("Графік відключень", 20f, Ui.TEXT, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(content, ui.matchWrap())
        val scroll = ui.scrollCentered(page, fillViewport = true)
        SystemBarsInsetHelper.apply(scroll, 0, 0, 0, 0)
        setContentView(scroll)
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        val preferences = WidgetPreferences(this)
        val snapshot = preferences.snapshot()
        val info = snapshot?.powerOutage
        val settings = SettingsStore(this).load()
        val now = ZonedDateTime.now(KYIV)

        val head = ui.card()
        if (info == null || !info.scheduleEnabled) {
            head.addView(ui.label("Графік не налаштовано", 15f, Ui.TEXT, true))
            head.addView(ui.hint("Оберіть область і чергу в Налаштуваннях → «Графік відключень». Світло є чи ні застосунок все одно визначає за резервним виходом батареї."))
            head.addView(ui.button("Відкрити налаштування") { startActivity(Intent(this, SettingsActivity::class.java)) }, ui.matchWrap(8))
            content.addView(head, ui.matchWrap(8))
            return
        }
        val region = OutageRegions.byId(settings.outageRegionId ?: info.regionId)?.name
        head.addView(ui.label(listOfNotNull(region, info.group?.let { "черга $it" }).joinToString(" · ").ifEmpty { "Черга —" }, 16f, Ui.TEXT, true))
        info.source?.let { head.addView(ui.label("Джерело: $it", 12f, Ui.MUTED)) }
        listOfNotNull(
            info.scheduleUpdatedAt?.let { "Графік оновлено джерелом: ${stamp(it)}" },
            info.fetchedAt?.let { "Перевірено: ${stamp(it)}" },
        ).forEach { head.addView(ui.label(it, 11f, Ui.DIM)) }
        val light = LightStatusResolver.resolve(snapshot, preferences.state() == WidgetFetchState.ERROR, now)
        val dayRows = ScheduleDays.days(info, now.toInstant().toEpochMilli())
        // «не опубліковано» / «без відключень» is stated by the «Сьогодні» row below — not repeated here.
        val detail = PowerOutageFormatter.detailBesideHeadline(info, now, light.gridState)
            .takeUnless { it in setOf("Графік не опубліковано", "За графіком без відключень", dayRows[0].windowsText) }
        head.addView(ui.label(listOfNotNull(light.headline, detail).joinToString(" · "), 13f, when (light.kind) {
            LightKind.ON -> Ui.GOOD
            LightKind.OFF -> Ui.AMBER
            else -> Ui.MUTED
        }, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        content.addView(head, ui.matchWrap(8))

        if (info.emergency || info.scheduleUnavailable || info.sourceError != null) {
            val notice = ui.card().apply { background = ui.rounded(android.graphics.Color.rgb(48, 38, 18), ui.dp(18), android.graphics.Color.rgb(120, 92, 32)) }
            notice.addView(ui.label(when {
                info.emergency -> "Аварійні відключення: графік зараз не діє, світло можуть вимкнути будь-коли."
                info.scheduleUnavailable -> PowerOutageFormatter.SCHEDULE_UNAVAILABLE
                else -> "Джерело графіка зараз не відповідає — показано останні збережені дані."
            }, 12f, Ui.AMBER, true))
            content.addView(notice, ui.matchWrap(8))
        }

        val days = ui.card()
        days.addView(ui.title("Сьогодні і завтра"))
        days.addView(ScheduleDaysView(this, barHeightDp = 44).apply { setDays(dayRows) })
        days.addView(legend(), ui.matchWrap(10))
        content.addView(days, ui.matchWrap(8))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = ui.button("Оновити графік", filled = true) { refresh() }
        actions.addView(refreshButton, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        OutageSourceLinks.url(info.sourceId, info.source)?.let { url ->
            actions.addView(ui.button("Сайт джерела") { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { marginStart = ui.dp(8) })
        }
        content.addView(actions, ui.matchWrap(10))
        content.addView(ui.hint("Графік — лише прогноз джерела. Світло є чи нема застосунок визначає за резервним виходом батареї."), ui.matchWrap(6))
    }

    private fun legend() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(swatch(Ui.AMBER)); addView(ui.label("відключення", 10f, Ui.MUTED).apply { setPadding(ui.dp(4), 0, ui.dp(12), 0) })
        addView(swatch(android.graphics.Color.argb(150, 245, 183, 46))); addView(ui.label("можливе", 10f, Ui.MUTED).apply { setPadding(ui.dp(4), 0, ui.dp(12), 0) })
        addView(swatch(android.graphics.Color.WHITE)); addView(ui.label("зараз", 10f, Ui.MUTED).apply { setPadding(ui.dp(4), 0, 0, 0) })
    }

    private fun swatch(color: Int) = View(this).apply {
        background = ui.rounded(color, ui.dp(2), color)
        layoutParams = LinearLayout.LayoutParams(ui.dp(12), ui.dp(8)).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
    }

    private fun refresh() {
        if (refreshing || showcase) return
        refreshing = true
        refreshButton.alpha = 0.5f
        refreshButton.text = "Оновлюю…"
        scope.launch {
            try { TelemetryRepository.refreshAndStore(this@ScheduleActivity) } finally {
                refreshing = false
                render()
            }
        }
    }

    private fun stamp(value: String): String = runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(KYIV).format(DateTimeFormatter.ofPattern("HH:mm, dd.MM"))
    }.getOrDefault(value)

    private companion object {
        val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")
    }
}
