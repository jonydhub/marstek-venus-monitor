package com.doloh.marstekwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.data.ExtraScheduleRepository
import com.doloh.marstekwidget.domain.ExtraRow
import com.doloh.marstekwidget.domain.ExtraSchedule
import com.doloh.marstekwidget.domain.ExtraScheduleData
import com.doloh.marstekwidget.domain.ExtraSchedules
import com.doloh.marstekwidget.domain.PowerOutageInfo
import com.doloh.marstekwidget.ui.DayScheduleBarPainter
import com.doloh.marstekwidget.ui.ExtraSchedulesView
import kotlin.math.roundToInt

/** How many «Графіки відключень» rows fit a cell and whether their bars carry time labels (pure, unit-tested). */
object ScheduleListPlan {
    const val PAD_V = 20f
    const val ROW_GAP = 6f
    /** Bar bitmap height without labels; the painter keeps 2.5 dp above and below for the now-marker (7 dp bar). */
    const val BAR_DP = 12f
    const val LABEL_DP = 9f

    /** Header line (12 sp name, no font padding ≈ 13.8 dp — measured on the emulator) + 2 dp gap above the bar. */
    fun headerDp(fontScale: Float) = 12f * 1.15f * fontScale + 2f

    /** A row without a bar («графік недоступний», «…») is just its header line. */
    fun rowDp(labels: Boolean, fontScale: Float, bar: Boolean = true) =
        headerDp(fontScale) + if (bar) BAR_DP + (if (labels) LABEL_DP * fontScale + 3f else 0f) else 0f

    data class Plan(val rows: Int, val labels: Boolean)

    /** How many of the rows ([bars]: whether each has a bar) fit [heightDp], in order. */
    private fun fitting(heightDp: Float, bars: List<Boolean>, labels: Boolean, fontScale: Float): Int {
        var used = PAD_V - ROW_GAP
        bars.forEachIndexed { i, bar ->
            used += rowDp(labels, fontScale, bar) + ROW_GAP
            if (used > heightDp) return i
        }
        return bars.size
    }

    /** Labelled bars while at least one row fits (the bars carry the times); a 1-cell-high strip falls back to plain bars. */
    fun plan(heightDp: Float, bars: List<Boolean>, fontScale: Float = 1f): Plan {
        if (bars.isEmpty()) return Plan(0, true)
        val labelled = fitting(heightDp, bars, true, fontScale)
        if (labelled >= 1) return Plan(labelled, true)
        return Plan(maxOf(1, fitting(heightDp, bars, false, fontScale)), false)
    }
}

/**
 * «Графіки відключень» widget: the own schedule first («Дім · черга 1.1»), then the «Інші графіки» chosen in the app,
 * each with its state and today's bar (window times above it, white now-marker). Renders only cached data (snapshot +
 * [ExtraScheduleRepository] cache), redrawn by [WidgetRenderer.updateAll] from the shared worker; tap → «Графік відключень».
 */
object ScheduleListWidgetRenderer {
    const val SLOTS = 7
    private val rowIds = intArrayOf(R.id.sched_row_0, R.id.sched_row_1, R.id.sched_row_2, R.id.sched_row_3, R.id.sched_row_4, R.id.sched_row_5, R.id.sched_row_6)
    private val nameIds = intArrayOf(R.id.sched_name_0, R.id.sched_name_1, R.id.sched_name_2, R.id.sched_name_3, R.id.sched_name_4, R.id.sched_name_5, R.id.sched_name_6)
    private val moreIds = intArrayOf(R.id.sched_more_0, R.id.sched_more_1, R.id.sched_more_2, R.id.sched_more_3, R.id.sched_more_4, R.id.sched_more_5, R.id.sched_more_6)
    private val statusIds = intArrayOf(R.id.sched_status_0, R.id.sched_status_1, R.id.sched_status_2, R.id.sched_status_3, R.id.sched_status_4, R.id.sched_status_5, R.id.sched_status_6)
    private val barIds = intArrayOf(R.id.sched_bar_0, R.id.sched_bar_1, R.id.sched_bar_2, R.id.sched_bar_3, R.id.sched_bar_4, R.id.sched_bar_5, R.id.sched_bar_6)

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MarstekSchedulesWidgetProvider::class.java))

    fun updateAll(context: Context) = widgetIds(context).forEach { update(context, it) }

    /** Own schedule row (null when no region/queue is set), then the extras — all from cache. */
    fun rows(info: PowerOutageInfo?, extras: List<Pair<ExtraSchedule, ExtraScheduleData?>>, nowMillis: Long): List<ExtraRow> {
        val own = ExtraSchedules.ownData(info)?.let { data ->
            val name = listOfNotNull("Дім", info?.group?.let { "черга $it" }).joinToString(" · ")
            ExtraSchedules.row(ExtraSchedule(name, info?.regionId ?: "", info?.group ?: ""), data, nowMillis)
        }
        return listOfNotNull(own) + extras.map { (entry, data) -> ExtraSchedules.row(entry, data, nowMillis) }
    }

    fun update(context: Context, widgetId: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val now = System.currentTimeMillis()
        val rows = rows(WidgetPreferences(context).snapshot()?.powerOutage, ExtraScheduleRepository(context).cachedAll(), now)
        val sizes = WidgetRenderer.reportedSizes(manager, widgetId)
        val views = when {
            sizes.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                RemoteViews(sizes.associate { (w, h) -> SizeF(w, h) to build(context, rows, widgetId, w to h) })
            sizes.isNotEmpty() -> {
                val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                build(context, rows, widgetId, if (portrait) sizes.first() else sizes.last())
            }
            else -> build(context, rows, widgetId, DEFAULT_SIZE)
        }
        manager.updateAppWidget(widgetId, views)
    }

    /** One size-specific RemoteViews for a cell of [sizeDp] (dp). */
    fun build(context: Context, rows: List<ExtraRow>, widgetId: Int, sizeDp: Pair<Float, Float>): RemoteViews {
        val (w, h) = sizeDp
        val views = RemoteViews(context.packageName, R.layout.widget_schedules)
        val fontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
        val plan = ScheduleListPlan.plan(h, rows.map { it.days != null }, fontScale)
        val density = context.resources.displayMetrics.density
        val barW = ((w - 24f).coerceAtLeast(60f) * density).roundToInt()
        val labelPx = if (plan.labels) ScheduleListPlan.LABEL_DP * fontScale * density else 0f
        val barH = (ScheduleListPlan.BAR_DP * density + DayScheduleBarPainter.timeLabelZone(labelPx, density)).roundToInt()
        views.setViewVisibility(R.id.sched_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
        val hidden = rows.size - plan.rows
        for (i in 0 until SLOTS) {
            val row = rows.getOrNull(i)?.takeIf { i < plan.rows }
            views.setViewVisibility(rowIds[i], if (row == null) View.GONE else View.VISIBLE)
            row ?: continue
            views.setTextViewText(nameIds[i], row.name)
            views.setTextViewText(statusIds[i], row.status)
            views.setTextColor(statusIds[i], ExtraSchedulesView.stateColor(row.state))
            // rows that did not fit: «+2» beside the last visible name
            val more = if (i == plan.rows - 1 && hidden > 0) "+$hidden" else ""
            views.setTextViewText(moreIds[i], more)
            val today = row.days?.firstOrNull()
            views.setViewVisibility(barIds[i], if (today == null) View.GONE else View.VISIBLE)
            if (today != null) {
                val bitmap = Bitmap.createBitmap(barW, barH, Bitmap.Config.ARGB_8888)
                // no «now» time label: the marker on the bar is enough in a list row
                DayScheduleBarPainter.draw(Canvas(bitmap), barW.toFloat(), barH.toFloat(), today.copy(nowText = null), density, timeLabelsPx = labelPx)
                views.setImageViewBitmap(barIds[i], bitmap)
                views.setContentDescription(barIds[i], today.summary)
            }
        }
        views.setContentDescription(R.id.sched_root, rows.take(plan.rows).joinToString(". ") { "${it.name}: ${it.status}" }
            .ifEmpty { context.getString(R.string.widget_schedules_empty) })
        views.setOnClickPendingIntent(R.id.sched_root, WidgetRenderer.schedulePendingIntent(context, widgetId))
        return views
    }

    private val DEFAULT_SIZE = 250f to 110f
}
