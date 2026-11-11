package com.doloh.marstekwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.domain.Alert
import com.doloh.marstekwidget.domain.AlertEvaluator
import com.doloh.marstekwidget.domain.AlertSeverity
import com.doloh.marstekwidget.domain.BatteryDisplayFormatter
import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.BatteryWarning
import com.doloh.marstekwidget.domain.BatteryWarningResolver
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.LightKind
import com.doloh.marstekwidget.domain.LightStatusResolver
import com.doloh.marstekwidget.domain.ScheduleDays
import com.doloh.marstekwidget.domain.TodayFormatter
import com.doloh.marstekwidget.domain.CompactPlanner
import com.doloh.marstekwidget.domain.CompactWidgetTexts
import com.doloh.marstekwidget.domain.SocGradient
import com.doloh.marstekwidget.domain.TextFit
import com.doloh.marstekwidget.ui.DayScheduleBarPainter
import com.doloh.marstekwidget.ui.ScheduleActivity
import com.doloh.marstekwidget.ui.SocBarPainter
import com.doloh.marstekwidget.ui.SocChartPainter
import com.doloh.marstekwidget.domain.OperatingModeFormatter
import com.doloh.marstekwidget.domain.PowerOutageFormatter
import com.doloh.marstekwidget.domain.SummaryLine
import com.doloh.marstekwidget.domain.SummaryLineKind
import com.doloh.marstekwidget.domain.SummaryMode
import com.doloh.marstekwidget.domain.SummaryTone
import com.doloh.marstekwidget.domain.WidgetSummary
import com.doloh.marstekwidget.domain.WidgetSummaryResolver
import com.doloh.marstekwidget.notify.AlertNotifier
import com.doloh.marstekwidget.ui.MainActivity
import com.doloh.marstekwidget.ui.WidgetConfigActivity
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class WidgetVariant { MINI, STRIP, MEDIUM, LARGE }

/** Everything a widget needs to render, decoupled from SharedPreferences so the debug preview can reuse it. */
data class WidgetRenderInput(
    val snapshot: BatterySnapshot?,
    val state: WidgetFetchState,
    val options: WidgetDisplayOptions,
    val lastFetchedAtMillis: Long,
    val now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")),
    /** Highest-severity active alert, shown as a warning line in the 4×2+ shapes. */
    val alert: Alert? = null,
)

object WidgetRenderer {
    private const val SEP = " · "
    private val LINE_IDS = intArrayOf(R.id.widget_line_1, R.id.widget_line_2, R.id.widget_line_3, R.id.widget_line_4, R.id.widget_line_5)

    fun update(context: Context, widgetId: Int) {
        update(context, widgetId, variantOf(context, widgetId))
    }

    fun variantOf(context: Context, widgetId: Int): WidgetVariant {
        val providerName = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        return when {
            providerName.endsWith("MarstekCompactWidgetProvider") -> WidgetVariant.MINI
            providerName.endsWith("MarstekStripWidgetProvider") -> WidgetVariant.STRIP
            providerName.endsWith("MarstekEnergyWidgetProvider") -> WidgetVariant.LARGE
            else -> WidgetVariant.MEDIUM
        }
    }

    internal fun update(context: Context, widgetId: Int, variant: WidgetVariant) {
        val manager = AppWidgetManager.getInstance(context)
        val preferences = WidgetPreferences(context)
        val input = WidgetRenderInput(
            snapshot = preferences.snapshot(),
            state = preferences.state(),
            options = preferences.options(widgetId),
            lastFetchedAtMillis = preferences.lastFetchedAtMillis(),
            alert = AlertEvaluator.top(AlertNotifier.activeAlerts(context)),
        )
        val sizes = reportedSizes(manager, widgetId)
        val views = when {
            // Real launcher sizes (portrait + landscape, or the exact list on API 31+): one entry per size, each laid
            // out and its bitmaps drawn for that size, so the content fills the cell instead of leaving empty bands.
            sizes.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> RemoteViews(sizes.associate { (w, h) ->
                SizeF(w, h) to build(context, WidgetShapeSelector.select(w, h), variant, input, widgetId, w to h)
            })
            sizes.isNotEmpty() -> {
                val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val (w, h) = if (portrait) sizes.first() else sizes.last()
                build(context, WidgetShapeSelector.select(w, h), variant, input, widgetId, w to h)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> RemoteViews(WidgetShape.entries.associate { shape ->
                SizeF(shape.minWidthDp, shape.minHeightDp) to build(context, shape, variant, input, widgetId)
            })
            else -> build(context, WidgetShapeSelector.defaultFor(variant), variant, input, widgetId)
        }
        manager.updateAppWidget(widgetId, views)
    }

    /**
     * Cell sizes in dp from AppWidgetManager options: OPTION_APPWIDGET_SIZES (API 31+) when present, else
     * portrait (minWidth × maxHeight) and landscape (maxWidth × minHeight). Empty when the launcher reports nothing.
     */
    private fun reportedSizes(manager: AppWidgetManager, widgetId: Int): List<Pair<Float, Float>> {
        val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull() ?: return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val list = runCatching { options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) }.getOrNull()
            val exact = list.orEmpty().filter { it.width > 0f && it.height > 0f }.map { it.width to it.height }.distinct()
            if (exact.isNotEmpty()) return exact.take(8)
        }
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return listOfNotNull(
            WidgetShapeSelector.portraitSize(minW, maxW, minH, maxH),
            WidgetShapeSelector.landscapeSize(minW, maxW, minH, maxH),
        ).distinct()
    }

    fun layoutFor(shape: WidgetShape): Int = when (shape) {
        WidgetShape.FLAT_NARROW, WidgetShape.FLAT -> R.layout.widget_shape_flat
        WidgetShape.SMALL, WidgetShape.SQUARE, WidgetShape.SQUARE_TALL -> R.layout.widget_shape_compact
        WidgetShape.WIDE -> R.layout.widget_shape_wide
        WidgetShape.TALL -> R.layout.widget_shape_tall
        WidgetShape.LARGE -> R.layout.widget_shape_large
        WidgetShape.XLARGE -> R.layout.widget_shape_xlarge
    }

    private fun lineCount(shape: WidgetShape): Int = when (shape) {
        WidgetShape.WIDE, WidgetShape.LARGE -> 3
        WidgetShape.XLARGE -> 4
        WidgetShape.TALL -> 5
        else -> 0
    }

    /** Builds one size-specific RemoteViews; only touches view IDs that exist in that shape's layout. */
    fun build(
        context: Context,
        shape: WidgetShape,
        variant: WidgetVariant,
        input: WidgetRenderInput,
        widgetId: Int,
        /** Real cell size in dp (bitmaps, ring and type are scaled to it); shape minimum when unknown. */
        sizeDp: Pair<Float, Float>? = null,
    ): RemoteViews {
        val size = sizeDp ?: (shape.minWidthDp to shape.minHeightDp)
        val views = RemoteViews(context.packageName, layoutFor(shape))
        val snapshot = input.snapshot
        val options = input.options
        // Light only from the backup output: a failed fetch (past the freshness window) is «немає зв'язку», never «нема».
        val linkLost = input.state == WidgetFetchState.ERROR
        val summary = snapshot?.let { WidgetSummaryResolver.resolve(it, input.now, linkLost) }
        val warning = snapshot?.let(BatteryWarningResolver::resolve)

        // SOC + light-state dot (all shapes).
        views.setTextViewText(R.id.widget_soc_text, socText(context, snapshot))
        views.setViewVisibility(R.id.widget_soc_text, visibility(options.showSoc))
        val light = summary?.lightState ?: GridState.UNKNOWN
        views.setTextColor(R.id.widget_light_dot, lightColor(light))
        views.setContentDescription(R.id.widget_light_dot, LightStatusResolver.resolve(snapshot, linkLost, input.now).headline)

        // Primary state line.
        // 4×4 has its own light block («Світло є · черга 1.1»), so its headline names only the battery state.
        val primary = summary?.let { if (shape.isBlock && snapshot?.powerOutage != null && !linkLost && !snapshot.batteryLinkLost) it.batteryState else it.primary }
            ?: noDataText(context, input.state)
        views.setTextViewText(R.id.widget_primary, primary)
        views.setTextColor(R.id.widget_primary, summary?.let { modeColor(it.mode) } ?: noDataColor(context, input.state))

        // Freshness.
        val compactUpdated = shape == WidgetShape.FLAT || shape == WidgetShape.FLAT_NARROW || shape == WidgetShape.SMALL ||
            shape == WidgetShape.SQUARE || shape == WidgetShape.SQUARE_TALL || shape == WidgetShape.WIDE
        views.setTextViewText(
            R.id.widget_updated,
            if (compactUpdated) compactUpdatedText(context, snapshot, input) else fullUpdatedText(context, snapshot, input, variant),
        )
        views.setTextColor(R.id.widget_updated, if (input.state == WidgetFetchState.OK) Color.rgb(139, 155, 167) else healthColor(context, input.state))
        views.setContentDescription(R.id.widget_updated, fullUpdatedText(context, snapshot, input, variant))
        views.setViewVisibility(R.id.widget_updated, visibility(options.showLastUpdated || input.state != WidgetFetchState.OK))

        when (shape) {
            WidgetShape.FLAT_NARROW, WidgetShape.FLAT -> renderFlat(views, context, shape, summary, warning, input)
            WidgetShape.SMALL, WidgetShape.SQUARE, WidgetShape.SQUARE_TALL -> renderCompact(views, context, summary, warning, input, size)
            WidgetShape.WIDE, WidgetShape.TALL -> {
                renderCapacity(views, context, snapshot, options, full = true, widthDp = size.first)
                renderLines(views, context, shape, summary, warning, input)
            }
            WidgetShape.LARGE, WidgetShape.XLARGE -> {
                renderCapacity(views, context, snapshot, options, full = true, widthDp = size.first)
                renderLines(views, context, shape, summary, warning, input)
                renderPowerBlock(views, context, snapshot, summary, input)
                renderFlows(views, context, snapshot, options)
            }
        }
        if (shape.showsToday) renderToday(views, shape, snapshot)
        if (shape.showsScheduleBar) renderSchedule(views, context, shape, snapshot, input, size.first)
        if (shape.isBlock) renderChart(views, context, shape, snapshot, input, size)
        bindActions(context, views, widgetId, shape)
        return views
    }

    private fun renderFlat(views: RemoteViews, context: Context, shape: WidgetShape, summary: WidgetSummary?, warning: BatteryWarning?, input: WidgetRenderInput) {
        val alert = warning?.takeIf { it != BatteryWarning.HIGH_POWER }
        val narrow = shape == WidgetShape.FLAT_NARROW
        if (narrow && summary != null) views.setTextViewText(R.id.widget_primary, summary.primary.substringBefore(SEP))
        // 4×1 strip: «Світла нема · 121 W» — the trailing column needs the room.
        else if (summary?.mode == SummaryMode.OUTAGE) views.setTextViewText(R.id.widget_primary, summary.primary.replace("$SEP" + "вихід ", SEP))
        val secondary = when {
            alert != null -> warningText(context, alert)
            narrow && summary != null -> summary.trailingPrimary
            summary != null -> summary.secondary?.let { if (summary.primary.startsWith("Світло є")) it.removeSuffix(PowerOutageFormatter.LIGHT_ON_SUFFIX) else it }
            else -> noDataHint(context, input.state)
        }
        views.setTextViewText(R.id.widget_secondary, secondary.orEmpty())
        views.setTextColor(R.id.widget_secondary, when {
            alert != null -> warningColor(context, alert)
            summary?.mode == SummaryMode.OUTAGE -> toneColor(reserveTone(summary), Color.rgb(201, 213, 220))
            else -> Color.rgb(168, 184, 194)
        })
        views.setViewVisibility(R.id.widget_secondary, visibility(!secondary.isNullOrBlank()))

        // 2x1: no room for the refresh button; tapping the card opens the app instead.
        views.setViewVisibility(R.id.widget_refresh, visibility(!narrow))
        val showTrailing = shape == WidgetShape.FLAT && summary != null
        views.setViewVisibility(R.id.widget_trailing_group, visibility(showTrailing))
        if (showTrailing && summary != null) setTrailing(views, summary, input.options)
    }

    private fun renderToday(views: RemoteViews, shape: WidgetShape, snapshot: BatterySnapshot?) {
        val today = snapshot?.insights?.today
        val text = TodayFormatter.compact(today)?.let { "Сьогодні: $it" }
        views.setViewVisibility(R.id.widget_today, visibility(text != null))
        text?.let { views.setTextViewText(R.id.widget_today, it) }
    }

    private fun renderSchedule(views: RemoteViews, context: Context, shape: WidgetShape, snapshot: BatterySnapshot?, input: WidgetRenderInput, widthDp: Float) {
        val info = snapshot?.powerOutage
        views.setViewVisibility(R.id.widget_schedule_group, visibility(info != null && info.scheduleEnabled && !info.scheduleUnavailable))
        info ?: return
        val days = ScheduleDays.days(info, input.now.toInstant().toEpochMilli())
        val today = days[0]
        val tomorrow = days[1]
        val density = context.resources.displayMetrics.density
        val barWidthDp = (widthDp - 28f).coerceAtLeast(80f)
        // Today | tomorrow halves, each 0–24 h; the 4×4 light chip already names the queue.
        views.setTextViewText(R.id.widget_schedule_caption, listOfNotNull("Сьогодні", info.group?.takeIf { !shape.isBlock }?.let { "черга $it" },
            today.windowsText.takeIf { today.definite.isEmpty() }?.lowercase()).joinToString(" · "))
        views.setTextViewText(R.id.widget_schedule_caption_tomorrow, if (tomorrow.published) "Завтра" + (if (tomorrow.definite.isEmpty()) " · без відключень" else "") else "Завтра · ще не опубліковано")
        val heightDp = if (shape == WidgetShape.XLARGE) 12f else 9f
        val w = (barWidthDp * density).roundToInt()
        val h = (heightDp * density).roundToInt()
        val gap = 6f * density
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val half = (w - gap) / 2f
        DayScheduleBarPainter.draw(canvas, half, h.toFloat(), today, density)
        canvas.save()
        canvas.translate(half + gap, 0f)
        DayScheduleBarPainter.draw(canvas, half, h.toFloat(), tomorrow, density)
        canvas.restore()
        views.setImageViewBitmap(R.id.widget_schedule_bar, bitmap)
        views.setContentDescription(R.id.widget_schedule_bar, "${today.summary}; ${tomorrow.summary}")
    }

    /** 4×4+: 24 h SOC line with no-light periods shaded and the reserve floor — fills the card's free height. */
    private fun renderChart(views: RemoteViews, context: Context, shape: WidgetShape, snapshot: BatterySnapshot?, input: WidgetRenderInput, size: Pair<Float, Float>) {
        val history = snapshot?.insights?.socHistory.orEmpty()
        views.setViewVisibility(R.id.widget_chart, visibility(history.size >= 2))
        if (history.size < 2) return
        val density = context.resources.displayMetrics.density
        val w = (size.first - 28f).coerceAtLeast(200f)
        val h = if (shape == WidgetShape.XLARGE) 150f else 110f
        val bitmap = Bitmap.createBitmap((w * density).roundToInt(), (h * density).roundToInt(), Bitmap.Config.ARGB_8888)
        SocChartPainter.draw(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), SocChartPainter.Input(
            history, snapshot?.insights?.outagePeriods.orEmpty(), snapshot?.insights?.chartEvents.orEmpty(),
            snapshot?.powerOutage?.floorPct, input.now.toInstant().toEpochMilli()), density, labels = false)
        views.setImageViewBitmap(R.id.widget_chart, bitmap)
    }

    /**
     * 2×2 cells: header «● Світло є  27 °C  08:30 ↻», the gradient SOC ring (the only SOC indicator), then one-line rows —
     * state + runtime, today's durations, today's schedule. Type and ring follow [CompactPlanner] for the real cell size;
     * each row picks the longest candidate that fits the measured width so numbers are never hidden behind «…».
     */
    private fun renderCompact(views: RemoteViews, context: Context, summary: WidgetSummary?, warning: BatteryWarning?, input: WidgetRenderInput, size: Pair<Float, Float>) {
        val snapshot = input.snapshot
        val (w, h) = size
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val days = snapshot?.powerOutage?.let { ScheduleDays.days(it, input.now.toInstant().toEpochMilli()) }
        val scheduleRow = CompactWidgetTexts.scheduleRow(snapshot?.powerOutage, days?.get(0), input.now)
        val todayCandidates = CompactWidgetTexts.todayCandidates(snapshot?.insights?.today)
        val plan = CompactPlanner.plan(w, h, todayCandidates.isNotEmpty(), scheduleRow != null, scheduleRow?.showBar ?: true, fontScale)
        val k = plan.headerSp / 13f
        val sp = android.util.TypedValue.COMPLEX_UNIT_SP
        val textWidthDp = w - CompactPlanner.PAD_H
        val measure = measurer(context)

        // Ring + SOC inside it.
        val soc = snapshot?.socPercent?.coerceIn(0, 100)
        views.setImageViewBitmap(R.id.widget_soc_ring_image, socRingBitmap(context, soc, plan.ringDp))
        views.setTextViewTextSize(R.id.widget_soc_text, sp, (plan.ringDp * 0.25f / fontScale).coerceIn(14f, 40f))
        views.setTextViewTextSize(R.id.widget_capacity_text, sp, (plan.ringDp * 0.105f / fontScale).coerceIn(8.5f, 15f))
        views.setViewVisibility(R.id.widget_soc_ring_container, visibility(input.options.showSoc))
        views.setTextViewText(R.id.widget_capacity_text, compactEnergyText(context, snapshot))
        views.setViewVisibility(R.id.widget_capacity_text, visibility(input.options.showCapacity))
        views.setContentDescription(
            R.id.widget_soc_ring_container,
            context.getString(R.string.widget_soc_energy_description, socText(context, snapshot), compactEnergyText(context, snapshot)),
        )

        // Header: light state, temperature, freshness, refresh — one line, never wrapped.
        val tempSp = (11f * k).coerceIn(9.5f, 14f)
        val updatedSp = (10f * k).coerceIn(9f, 13f)
        views.setTextViewTextSize(R.id.widget_primary, sp, plan.headerSp)
        views.setTextViewTextSize(R.id.widget_light_dot, sp, plan.headerSp)
        views.setTextViewTextSize(R.id.widget_temperature, sp, tempSp)
        views.setTextViewTextSize(R.id.widget_updated, sp, updatedSp)
        val light = LightStatusResolver.resolve(snapshot, input.state == WidgetFetchState.ERROR, input.now)
        val headline = if (summary != null) light.headline else noDataText(context, input.state)
        val updatedText = compactUpdatedText(context, snapshot, input)
        val wantTime = input.options.showLastUpdated || input.state != WidgetFetchState.OK
        val temperature = (snapshot?.temperatureCelsius ?: snapshot?.insights?.temperatureCelsius)
            ?.takeIf { input.options.showTemperature }?.let { CompactWidgetTexts.temperature(it, compact = true) }
        val header = CompactWidgetTexts.header(
            headlines = CompactWidgetTexts.headlineCandidates(headline),
            hasTemperature = temperature != null,
            hasTime = wantTime,
            timeImportant = input.state != WidgetFetchState.OK,
            roomDp = textWidthDp - measure("●", plan.headerSp, false) - 4f,
            headlineDp = { measure(it, plan.headerSp, true) },
            temperatureDp = temperature?.let { measure(it, tempSp, false) + 6f } ?: 0f,
            timeDp = measure(updatedText, updatedSp, false) + 6f,
            glyphDp = measure(" ↻", updatedSp, false),
            refreshButtonDp = 22f,
        )
        val tempInHeader = header.showTemperature
        views.setTextViewText(R.id.widget_primary, header.headline)
        if (summary != null) views.setTextColor(R.id.widget_primary, if (light.kind == LightKind.ON) Color.rgb(243, 247, 250) else modeColor(summary.mode))
        views.setContentDescription(R.id.widget_primary, headline)
        views.setViewVisibility(R.id.widget_temperature, visibility(tempInHeader))
        if (tempInHeader) views.setTextViewText(R.id.widget_temperature, temperature)
        // No room for the 22 dp refresh button: the time itself carries a cyan «↻» and refreshes on tap.
        views.setViewVisibility(R.id.widget_refresh, visibility(header.refreshButton))
        val timeText = if (header.showTime) updatedText else ""
        val updated: CharSequence = if (header.refreshButton) timeText else android.text.SpannableString("$timeText ↻".trimStart()).apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.rgb(54, 198, 213)), length - 1, length, 0)
        }
        views.setTextViewText(R.id.widget_updated, updated)
        views.setViewVisibility(R.id.widget_updated, visibility(updated.isNotEmpty()))
        if (!header.refreshButton) views.setContentDescription(R.id.widget_updated, context.getString(R.string.widget_refresh_description))

        // Row 1: battery alert, else state + runtime/ETA (temperature joins it only when the header had no room).
        val alert = warning?.takeIf { it != BatteryWarning.HIGH_POWER }
        var stateCandidates = when {
            alert != null -> listOf(warningText(context, alert))
            summary != null && snapshot != null && (input.state == WidgetFetchState.ERROR || snapshot.batteryLinkLost) ->
                listOfNotNull(summary.secondary, "Показано останні дані")
            summary != null && snapshot != null -> CompactWidgetTexts.stateCandidates(summary, snapshot, input.now)
            else -> listOfNotNull(noDataHint(context, input.state))
        }
        if (!tempInHeader && temperature != null && alert == null) stateCandidates = stateCandidates.flatMap { listOf(it + SEP + temperature, it) }
        val stateText = TextFit.pick(stateCandidates, textWidthDp) { measure(it, plan.stateSp, false) }
        views.setTextViewTextSize(R.id.widget_secondary, sp, plan.stateSp)
        views.setTextViewText(R.id.widget_secondary, stateText.orEmpty())
        views.setViewVisibility(R.id.widget_secondary, visibility(!stateText.isNullOrBlank()))
        val stateTone = summary?.let { compactStateTone(it, snapshot) }
        views.setTextColor(R.id.widget_secondary, alert?.let { warningColor(context, it) } ?: toneColor(stateTone ?: SummaryTone.NEUTRAL, Color.rgb(201, 213, 220)))

        // Row 2: today «З 31 хв · Р 36 хв · Без світла 0».
        val todayText = if (plan.showToday) TextFit.pick(todayCandidates, textWidthDp) { measure(it, plan.todaySp, false) } else null
        views.setTextViewTextSize(R.id.widget_today, sp, plan.todaySp)
        views.setViewVisibility(R.id.widget_today, visibility(todayText != null))
        todayText?.let {
            views.setTextViewText(R.id.widget_today, it)
            views.setContentDescription(R.id.widget_today, "Сьогодні: $it")
        }

        // Row 3: today's schedule bar + short caption, or one short full-width line («Графік: немає»).
        val row = scheduleRow?.takeIf { plan.showSchedule }
        val today = days?.get(0)
        val barRow = row?.takeIf { it.showBar && today != null }
        views.setViewVisibility(R.id.widget_schedule_group, visibility(barRow != null))
        views.setViewVisibility(R.id.widget_schedule_note, visibility(row != null && barRow == null && row.caption != null))
        if (row != null && barRow == null) row.caption?.let {
            views.setTextViewTextSize(R.id.widget_schedule_note, sp, plan.captionSp)
            views.setTextViewText(R.id.widget_schedule_note, it)
        }
        if (barRow != null && today != null) {
            views.setTextViewTextSize(R.id.widget_schedule_caption, sp, plan.captionSp)
            views.setViewVisibility(R.id.widget_schedule_caption, visibility(barRow.caption != null))
            barRow.caption?.let { views.setTextViewText(R.id.widget_schedule_caption, it) }
            val density = context.resources.displayMetrics.density
            val captionDp = barRow.caption?.let { measure(it, plan.captionSp, false) + 6f } ?: 0f
            val barWidthDp = (textWidthDp - captionDp).coerceAtLeast(40f)
            val bitmap = Bitmap.createBitmap((barWidthDp * density).roundToInt(), (9f * density).roundToInt(), Bitmap.Config.ARGB_8888)
            DayScheduleBarPainter.draw(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), today, density)
            views.setImageViewBitmap(R.id.widget_schedule_bar, bitmap)
            views.setContentDescription(R.id.widget_schedule_bar, listOfNotNull(today.summary, today.maybeText).joinToString("; "))
        }
    }

    /** Tone of the compact state row: the outage verdict («вистачить» green / «не вистачить» red), else neutral. */
    private fun compactStateTone(summary: WidgetSummary, snapshot: BatterySnapshot?): SummaryTone = when (summary.mode) {
        SummaryMode.OUTAGE -> reserveTone(summary)
        SummaryMode.CHARGING -> SummaryTone.GOOD
        else -> if (summary.mode == SummaryMode.IDLE && (snapshot?.socPercent ?: 0) >= 99) SummaryTone.GOOD else SummaryTone.NEUTRAL
    }

    /**
     * Width of [text] in dp at [sp] (user font scale included), padded 5 % because launchers such as One UI draw
     * RemoteViews text in their own, slightly wider system font.
     */
    private fun measurer(context: Context): (String, Float, Boolean) -> Float {
        val metrics = context.resources.displayMetrics
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val regular = Paint(Paint.ANTI_ALIAS_FLAG)
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        return { text, sp, isBold ->
            val paint = if (isBold) bold else regular
            paint.textSize = sp * fontScale * metrics.density
            paint.measureText(text) / metrics.density * 1.05f
        }
    }

    private fun setTrailing(views: RemoteViews, summary: WidgetSummary, options: WidgetDisplayOptions) {
        val etaDriven = summary.mode == SummaryMode.CHARGING || summary.mode == SummaryMode.DISCHARGING
        val showPrimary = !etaDriven || options.showEta
        // «≈ 3 д 15 год» wraps as «≈ 3 д» / «15 год», never «15» / «год».
        views.setTextViewText(R.id.widget_trailing_primary, keepUnits(summary.trailingPrimary))
        views.setTextColor(R.id.widget_trailing_primary, if (summary.mode == SummaryMode.OUTAGE) Color.rgb(255, 210, 100) else Color.WHITE)
        views.setViewVisibility(R.id.widget_trailing_primary, visibility(showPrimary))
        val secondary = summary.trailingSecondary
        views.setTextViewText(R.id.widget_trailing_secondary, secondary?.let(::keepUnits).orEmpty())
        views.setViewVisibility(R.id.widget_trailing_secondary, visibility(secondary != null && (summary.mode != SummaryMode.OUTAGE || options.showEta)))
    }

    private fun renderCapacity(views: RemoteViews, context: Context, snapshot: BatterySnapshot?, options: WidgetDisplayOptions, full: Boolean, widthDp: Float) {
        views.setImageViewBitmap(R.id.widget_soc_bar, socBarBitmap(context, snapshot?.socPercent, widthDp - 26f, 6f))
        views.setViewVisibility(R.id.widget_soc_bar, visibility(options.showSoc))
        views.setTextViewText(R.id.widget_capacity_text, if (full) capacityText(context, snapshot) else compactEnergyText(context, snapshot))
        views.setViewVisibility(R.id.widget_capacity_text, visibility(options.showCapacity))
    }

    private fun renderLines(views: RemoteViews, context: Context, shape: WidgetShape, summary: WidgetSummary?, warning: BatteryWarning?, input: WidgetRenderInput) {
        val options = input.options
        val excluded = if (shape.isBlock) setOf(SummaryLineKind.LIGHT, SummaryLineKind.RESERVE, SummaryLineKind.RETURN_TIME) else emptySet()
        val lines = buildList<SummaryLine> {
            val alert = input.alert
            if (alert != null) {
                add(SummaryLine(SummaryLineKind.TEMPERATURE, alert.title, if (alert.severity == AlertSeverity.CRITICAL) SummaryTone.CRITICAL else SummaryTone.WARNING))
            } else {
                warning?.takeIf { it != BatteryWarning.HIGH_POWER }?.let { add(SummaryLine(SummaryLineKind.TEMPERATURE, warningText(context, it), SummaryTone.WARNING)) }
            }
            summary?.lines?.filter { it.kind !in excluded && allowed(it.kind, options) }?.forEach { line ->
                add(if (line.kind == SummaryLineKind.TEMPERATURE) withBackup(line, summary, input) else line)
                // Idle at full charge: say so instead of leaving the ETA slot empty.
                if (line.kind == SummaryLineKind.LIGHT && summary.mode == SummaryMode.IDLE && options.showEta) {
                    summary.etaText?.let { add(SummaryLine(SummaryLineKind.ETA, it, SummaryTone.GOOD)) }
                }
            }
            if (summary != null && summary.mode == SummaryMode.IDLE && options.showEta && none { it.kind == SummaryLineKind.ETA }) {
                summary.etaText?.let { add(0, SummaryLine(SummaryLineKind.ETA, it, SummaryTone.GOOD)) }
            }
            if (summary == null) add(SummaryLine(SummaryLineKind.LIGHT, noDataHint(context, input.state).orEmpty()))
        }.filter { it.text.isNotBlank() }
        val count = lineCount(shape)
        for (index in 0 until count) {
            val id = LINE_IDS[index]
            val line = lines.getOrNull(index)
            views.setViewVisibility(id, visibility(line != null))
            if (line != null) {
                views.setTextViewText(id, line.text)
                views.setTextColor(id, toneColor(line.tone, Color.rgb(201, 213, 220)))
            }
        }
    }

    /** «Температура 27,0 °C · вихід 0 W» — the backup output joins the temperature row outside an outage. */
    private fun withBackup(line: SummaryLine, summary: WidgetSummary, input: WidgetRenderInput): SummaryLine {
        if (summary.mode == SummaryMode.OUTAGE || !input.options.showBackupPower) return line
        val backup = input.snapshot?.insights?.backupOutputWatts ?: input.snapshot?.offgridPowerWatts ?: return line
        return line.copy(text = line.text + SEP + "вихід " + WidgetSummaryResolver.formatWatts(backup))
    }

    private fun allowed(kind: SummaryLineKind, options: WidgetDisplayOptions): Boolean = when (kind) {
        SummaryLineKind.POWER -> options.showPower
        SummaryLineKind.ETA -> options.showEta
        SummaryLineKind.TEMPERATURE -> options.showTemperature
        SummaryLineKind.BACKUP_OUTPUT -> options.showBackupPower
        SummaryLineKind.LIGHT, SummaryLineKind.RESERVE, SummaryLineKind.RETURN_TIME -> true
    }

    private fun renderPowerBlock(views: RemoteViews, context: Context, snapshot: BatterySnapshot?, summary: WidgetSummary?, input: WidgetRenderInput) {
        val info = snapshot?.powerOutage
        views.setViewVisibility(R.id.widget_power_group, visibility(info != null))
        info ?: return
        val light = LightStatusResolver.resolve(snapshot, input.state == WidgetFetchState.ERROR, input.now)
        val headline = light.headline
        views.setTextViewText(R.id.widget_power_status, context.getString(R.string.widget_power_headline_group, headline, info.group ?: "—"))
        views.setTextColor(R.id.widget_power_status, lightColor(light.gridState))
        views.setTextViewText(R.id.widget_power_detail, listOfNotNull(light.hint?.replaceFirstChar { it.titlecase() }, PowerOutageFormatter.detailBesideHeadline(info, input.now, light.gridState)).joinToString(" · "))
        // Same single runtime + verdict as the summary (autonomy runtime, else battery ETA to 0%).
        val reserveLine = summary?.lines?.firstOrNull { it.kind == SummaryLineKind.RESERVE }
        val reserve = if (light.kind == LightKind.NO_LINK) null else reserveLine?.text ?: PowerOutageFormatter.reserve(info)
            ?: if (info.gridState == GridState.OFF) context.getString(R.string.widget_power_reserve_measuring) else null
        views.setViewVisibility(R.id.widget_power_reserve, visibility(reserve != null))
        reserve?.let { views.setTextViewText(R.id.widget_power_reserve, it) }
        views.setTextColor(R.id.widget_power_reserve, toneColor(reserveLine?.tone ?: SummaryTone.NEUTRAL, Color.rgb(201, 213, 220)))
    }

    private fun renderFlows(views: RemoteViews, context: Context, snapshot: BatterySnapshot?, options: WidgetDisplayOptions) {
        val gridText = context.getString(R.string.widget_grid_power, BatteryDisplayFormatter.formatSignedWatts(snapshot?.gridPowerWatts))
        views.setTextViewText(R.id.widget_grid_power, gridText)
        views.setContentDescription(R.id.widget_grid_power, context.getString(R.string.widget_grid_power_accessibility, gridText))
        val backup = snapshot?.insights?.backupOutputWatts ?: snapshot?.offgridPowerWatts
        views.setTextViewText(R.id.widget_backup_power, context.getString(R.string.widget_backup_power_short, BatteryDisplayFormatter.formatSignedWatts(backup)))
        views.setViewVisibility(R.id.widget_grid_power, visibility(options.showGridPower))
        views.setViewVisibility(R.id.widget_backup_power, visibility(options.showBackupPower))
        views.setViewVisibility(R.id.widget_flow_sources_group, visibility(options.showGridPower || options.showBackupPower))
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        widgetProviders().forEach { (provider, variant) ->
            manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id -> update(context, id, variant) }
        }
    }

    fun allWidgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return widgetProviders().flatMap { (provider, _) -> manager.getAppWidgetIds(ComponentName(context, provider)).toList() }.toIntArray()
    }

    fun hasWidgets(context: Context): Boolean = allWidgetIds(context).isNotEmpty()

    private fun widgetProviders(): List<Pair<Class<out AppWidgetProvider>, WidgetVariant>> = listOf(
        MarstekCompactWidgetProvider::class.java to WidgetVariant.MINI,
        MarstekStripWidgetProvider::class.java to WidgetVariant.STRIP,
        MarstekWidgetProvider::class.java to WidgetVariant.MEDIUM,
        MarstekEnergyWidgetProvider::class.java to WidgetVariant.LARGE,
    )

    private fun bindActions(context: Context, views: RemoteViews, widgetId: Int, shape: WidgetShape) {
        val refreshIntent = Intent(context, MarstekWidgetProvider::class.java).apply {
            action = BaseMarstekWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val refresh = PendingIntent.getBroadcast(context, widgetId * 10 + 1, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
        // 2×2: the freshness time doubles as the refresh control when the header has no room for the button.
        if (shape.isCompact) views.setOnClickPendingIntent(R.id.widget_updated, refresh)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(context, widgetId * 10 + 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        // Light state (dot + headline, the 4×4 light block) and the schedule bar → «Графік відключень».
        val schedule = PendingIntent.getActivity(context, widgetId * 10 + 4,
            Intent(context, ScheduleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_light_dot, schedule)
        if (shape.isBlock) views.setOnClickPendingIntent(R.id.widget_power_group, schedule)
        else views.setOnClickPendingIntent(R.id.widget_primary, schedule)
        if (shape.showsScheduleBar) views.setOnClickPendingIntent(R.id.widget_schedule_group, schedule)
        if (shape == WidgetShape.WIDE || shape == WidgetShape.TALL || shape.isBlock) {
            val configIntent = Intent(context, WidgetConfigActivity::class.java).apply { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) }
            views.setOnClickPendingIntent(
                R.id.widget_settings,
                PendingIntent.getActivity(context, widgetId * 10 + 3, configIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
    }

    private fun reserveTone(summary: WidgetSummary): SummaryTone =
        summary.lines.firstOrNull { it.kind == SummaryLineKind.RESERVE }?.tone ?: SummaryTone.NEUTRAL

    private fun noDataText(context: Context, state: WidgetFetchState): String = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN -> context.getString(R.string.widget_state_sign_in)
        WidgetFetchState.ERROR, WidgetFetchState.STALE -> context.getString(R.string.widget_state_offline)
        WidgetFetchState.NEVER -> context.getString(R.string.widget_state_waiting_data)
        WidgetFetchState.OK -> context.getString(R.string.widget_state_unknown)
    }

    private fun noDataHint(context: Context, state: WidgetFetchState): String? = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN -> context.getString(R.string.widget_hint_sign_in)
        WidgetFetchState.ERROR, WidgetFetchState.STALE -> context.getString(R.string.widget_update_problem)
        WidgetFetchState.NEVER, WidgetFetchState.OK -> null
    }

    private fun noDataColor(context: Context, state: WidgetFetchState): Int = when (state) {
        WidgetFetchState.ERROR -> context.getColor(R.color.widget_status_critical)
        WidgetFetchState.OK -> context.getColor(R.color.widget_state_unknown)
        else -> context.getColor(R.color.widget_status_warning)
    }

    private fun modeColor(mode: SummaryMode): Int = when (mode) {
        SummaryMode.OUTAGE -> Color.rgb(255, 184, 107)
        SummaryMode.CHARGING -> Color.rgb(127, 227, 178)
        SummaryMode.DISCHARGING -> Color.rgb(142, 230, 238)
        SummaryMode.IDLE -> Color.rgb(243, 247, 250)
        SummaryMode.UNKNOWN -> Color.rgb(160, 174, 184)
    }

    private fun toneColor(tone: SummaryTone, neutral: Int): Int = when (tone) {
        SummaryTone.NEUTRAL -> neutral
        SummaryTone.GOOD -> Color.rgb(127, 227, 178)
        SummaryTone.WARNING -> Color.rgb(255, 210, 100)
        SummaryTone.CRITICAL -> Color.rgb(255, 140, 128)
    }

    fun lightColor(state: GridState): Int = when (state) {
        GridState.ON -> Color.rgb(127, 227, 178)
        GridState.OFF -> Color.rgb(255, 159, 90)
        GridState.UNKNOWN -> Color.rgb(111, 130, 144)
    }

    private fun healthText(context: Context, state: WidgetFetchState): String = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN -> context.getString(R.string.widget_health_sign_in)
        WidgetFetchState.ERROR -> context.getString(R.string.widget_health_offline)
        WidgetFetchState.STALE -> context.getString(R.string.widget_health_stale)
        WidgetFetchState.NEVER -> context.getString(R.string.widget_health_waiting)
        WidgetFetchState.OK -> context.getString(R.string.widget_health_online)
    }

    private fun healthColor(context: Context, state: WidgetFetchState): Int = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN, WidgetFetchState.STALE, WidgetFetchState.NEVER -> context.getColor(R.color.widget_status_warning)
        WidgetFetchState.ERROR -> context.getColor(R.color.widget_status_critical)
        WidgetFetchState.OK -> context.getColor(R.color.widget_status_ok)
    }

    private fun warningText(context: Context, warning: BatteryWarning): String = when (warning) {
        BatteryWarning.CRITICAL_TEMPERATURE -> context.getString(R.string.widget_warning_critical_temp_short)
        BatteryWarning.HIGH_TEMPERATURE -> context.getString(R.string.widget_warning_high_temp_short)
        BatteryWarning.LOW_SOC -> context.getString(R.string.widget_warning_low_soc_short)
        BatteryWarning.HIGH_POWER -> context.getString(R.string.widget_warning_high_power_short)
    }

    private fun warningColor(context: Context, warning: BatteryWarning): Int = when (warning) {
        BatteryWarning.CRITICAL_TEMPERATURE -> context.getColor(R.color.widget_critical_text)
        else -> context.getColor(R.color.widget_warning_text)
    }

    private fun socText(context: Context, snapshot: BatterySnapshot?) =
        snapshot?.socPercent?.let { context.getString(R.string.widget_soc_compact, it) } ?: context.getString(R.string.widget_soc_compact_unknown)

    private fun capacityText(context: Context, snapshot: BatterySnapshot?): String {
        val stored = snapshot?.batteryCapacityWh ?: return context.getString(R.string.widget_kwh_unknown)
        val rated = snapshot.ratedCapacityWh?.let(BatteryDisplayFormatter::formatKwh) ?: "—"
        return context.getString(R.string.widget_capacity_short, BatteryDisplayFormatter.formatKwh(stored), rated)
    }

    private fun compactEnergyText(context: Context, snapshot: BatterySnapshot?): String =
        snapshot?.batteryCapacityWh?.takeIf(Double::isFinite)?.let(BatteryDisplayFormatter::formatKwh) ?: context.getString(R.string.widget_kwh_unknown)

    private fun timestamp(snapshot: BatterySnapshot?, input: WidgetRenderInput): Long =
        snapshot?.collectedAt?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: input.lastFetchedAtMillis

    private fun clock(millis: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    private fun compactUpdatedText(context: Context, snapshot: BatterySnapshot?, input: WidgetRenderInput): String {
        val ts = timestamp(snapshot, input)
        val time = if (ts > 0L) clock(ts) else "—"
        return when (input.state) {
            WidgetFetchState.OK -> time
            WidgetFetchState.NEVER -> context.getString(R.string.widget_health_waiting)
            else -> "${healthText(context, input.state)} $time"
        }
    }

    private fun fullUpdatedText(context: Context, snapshot: BatterySnapshot?, input: WidgetRenderInput, variant: WidgetVariant): String {
        val ts = timestamp(snapshot, input)
        val base = if (ts <= 0L) context.getString(R.string.widget_not_updated) else when (input.state) {
            WidgetFetchState.STALE -> context.getString(R.string.widget_stale_at, clock(ts))
            WidgetFetchState.NEEDS_SIGN_IN, WidgetFetchState.ERROR -> context.getString(R.string.widget_cached_at, clock(ts))
            WidgetFetchState.NEVER, WidgetFetchState.OK -> context.getString(R.string.widget_online_updated_at, healthText(context, input.state), clock(ts))
        }
        val mode = snapshot?.operatingMode?.takeIf { variant == WidgetVariant.LARGE }?.let(OperatingModeFormatter::format)
        return if (mode != null) "$base · $mode" else base
    }

    private fun socBarBitmap(context: Context, socPercent: Int?, widthDp: Float, heightDp: Float): Bitmap {
        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap((widthDp.coerceAtLeast(40f) * density).roundToInt(), (heightDp * density).roundToInt(), Bitmap.Config.ARGB_8888)
        SocBarPainter.draw(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), socPercent)
        return bitmap
    }

    private fun socRingBitmap(context: Context, socPercent: Int?, sizeDp: Float = 60f): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).roundToInt()
        val stroke = (sizeDp / 12f).coerceIn(5f, 11f) * density
        val inset = stroke / 2f + density
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val arc = RectF(inset, inset, size - inset, size - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = context.getColor(R.color.widget_ring_track)
        }
        canvas.drawArc(arc, -90f, 360f, false, paint)
        val progress = socPercent?.coerceIn(0, 100) ?: 0
        if (progress > 0) {
            // Same red → yellow → green → teal scale as the web/app bar, sampled at the SOC.
            paint.color = SocGradient.colorAt(progress)
            canvas.drawArc(arc, -90f, progress * 3.6f, false, paint)
        }
        return bitmap
    }

    internal fun keepUnits(text: String): String = text
        .replace(Regex("(\\d) (год|хв|д|с)(?!\\p{L})"), "$1\u00A0$2")
        .replace("≈ ", "≈\u00A0")

    private fun visibility(show: Boolean) = if (show) View.VISIBLE else View.GONE
}
