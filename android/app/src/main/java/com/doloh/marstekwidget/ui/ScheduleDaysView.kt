package com.doloh.marstekwidget.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.domain.ActualDay
import com.doloh.marstekwidget.domain.DaySchedule

/**
 * «Сьогодні» / «Завтра» rows, each 0–24 h with window start/end times and the current time above the bar, hour ticks
 * and labels below it, then the windows as text
 * («Відключення 07:00–13:00, 15:00–21:00», «Можливе …»). An unpublished tomorrow is a single text line.
 */
class ScheduleDaysView(context: Context, private val textScale: Float = 1f, private val barHeightDp: Int = 38) : LinearLayout(context) {
    init { orientation = VERTICAL }

    /**
     * [scheduleNow] «За графіком: …» goes above today's bar, [actual] (the «Фактично» row) under today's windows, with
     * the same width, hour ticks and now-marker as the schedule bar. Without a schedule pass [days] empty: only «Фактично».
     */
    private var lastKey: Any? = null
    private val dayViews = mutableListOf<Pair<DayScheduleView, DaySchedule>>()
    private var actualView: ActualDayView? = null
    /** Last «Відключення …» / «Можливе …» lines per day: a rebuilt row starts with them, so it does not blink. */
    private val lastLines = mutableMapOf<String, Pair<String?, String?>>()

    /**
     * Rebuilds only when what is shown changes. The 15-s refresh used to rebuild every row: the «Відключення …» line
     * under a bar is shown first and hidden after the bar has laid out its labels (onUnlabeled), so the screen below
     * jumped on every refresh. With the same windows only the «now» marker moves (setDay → invalidate, no relayout).
     */
    fun setDays(days: List<DaySchedule>, actual: ActualDay? = null, scheduleNow: String? = null) {
        // «Фактично» segments grow with the clock — they are pushed into the existing view, not part of the key
        val key = Triple(days.map { it.copy(nowFraction = null, nowText = null) }, actual?.copy(nowFraction = 0f, segments = emptyList()), scheduleNow)
        if (key == lastKey && dayViews.size == days.count { it.isToday || it.published }) {
            dayViews.forEachIndexed { i, (view, _) -> days.filter { it.isToday || it.published }.getOrNull(i)?.let(view::setDay) }
            actual?.let { a -> actualView?.setDay(a) }
            return
        }
        lastKey = key
        dayViews.clear()
        actualView = null
        removeAllViews()
        if (days.isEmpty() && actual != null) {
            addView(text("Сьогодні", 12f, TEXT, true), params(0))
            addActual(actual)
            return
        }
        days.forEachIndexed { index, day ->
            val top = if (index == 0) 0 else dp(10)
            if (!day.isToday && !day.published) {
                addView(text("Завтра: графік ще не опубліковано", 12f, MUTED, false), params(top))
                return@forEachIndexed
            }
            addView(text(day.title, 12f, TEXT, true), params(top))
            if (day.isToday) scheduleNow?.let { addView(text(it, 10.5f, MUTED, false), params(dp(1))) }
            val offLine = text(day.windowsText, 12f, if (day.definite.isNotEmpty()) AMBER else MUTED, day.definite.isNotEmpty())
            val maybeLine = day.maybeText?.let { text(it, 11f, AMBER_SOFT, false) }
            val dayKey = day.date.toString()
            lastLines[dayKey]?.let { (off, maybe) ->
                if (day.definite.isNotEmpty()) { offLine.visibility = if (off == null) GONE else VISIBLE; off?.let { offLine.text = it } }
                maybeLine?.let { line -> line.visibility = if (maybe == null) GONE else VISIBLE; maybe?.let { line.text = it } }
            }
            addView(DayScheduleView(context).apply {
                setDay(day)
                dayViews += this to day
                contentDescription = "${day.title}: ${day.windowsText}" + (day.maybeText?.let { "; $it" } ?: "")
                // The bar already shows each window's times above it: the lines below list only windows without a
                // label there (same rule as the web renderScheduleTimes), so nothing is said twice.
                onUnlabeled = { rest ->
                    fun list(maybe: Boolean) = rest.filter { it.maybe == maybe }.map { "${it.from}–${it.to}" }
                    if (day.definite.isNotEmpty()) {
                        val r = list(false)
                        offLine.visibility = if (r.isEmpty()) GONE else VISIBLE
                        offLine.text = "Відключення ${r.joinToString(", ")}"
                    }
                    maybeLine?.let { line ->
                        val r = list(true)
                        line.visibility = if (r.isEmpty()) GONE else VISIBLE
                        line.text = "Можливе ${r.joinToString(", ")}"
                    }
                    lastLines[dayKey] = (if (offLine.visibility == VISIBLE) offLine.text.toString() else null) to
                        maybeLine?.takeIf { it.visibility == VISIBLE }?.text?.toString()
                }
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(barHeightDp) + timeLabelZonePx()).apply { topMargin = dp(3) })
            addView(offLine, params(dp(2)))
            maybeLine?.let { addView(it, params(dp(1))) }
            day.totalsText?.let { addView(text(it, 10.5f, MUTED, false), params(dp(2))) }
            if (day.isToday && actual != null) addActual(actual)
        }
    }

    private fun addActual(actual: ActualDay) {
        val color = when (actual.state) { "on" -> GREEN; "off" -> CORAL; else -> MUTED }
        addView(text(actual.text, 10.5f, color, true), params(dp(8)))
        val view = ActualDayView(context, timeLabels = true).apply { setDay(actual) }
        actualView = view
        val zone = kotlin.math.ceil(ActualBarPainter.topZone(actual, view.timeLabelPx(), resources.displayMetrics.density)).toInt()
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ACTUAL_BAR_DP) + actualTickZonePx() + zone).apply { topMargin = dp(3) })
        // «Без світла 37 хв · зі станції 0,35 кВт·год» under the axis
        actual.energyLine?.let { addView(text(it, 10.5f, MUTED, false), params(dp(2))) }
    }

    /** Hour ticks + «0 6 12 18 24» under the «Фактично» bar (the same zone the schedule bar has). */
    private fun actualTickZonePx(): Int = kotlin.math.ceil(resources.displayMetrics.density * (6f + 10f + 2f)).toInt()

    private fun params(top: Int) = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = value
        textSize = sp * textScale
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Room for the «07:00 … 13:00» / «10:42» row that [DayScheduleView] draws above the bar. */
    private fun timeLabelZonePx(): Int {
        val px = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, DayScheduleView.TIME_LABEL_SP, resources.displayMetrics)
        return kotlin.math.ceil(DayScheduleBarPainter.timeLabelZone(px, resources.displayMetrics.density)).toInt()
    }

    private companion object {
        val TEXT = Color.rgb(243, 247, 250)
        val MUTED = Color.rgb(160, 173, 184)
        val AMBER = Color.rgb(245, 183, 46)
        val AMBER_SOFT = Color.rgb(230, 196, 120)
        val GREEN = Color.rgb(127, 227, 178)
        val CORAL = Color.rgb(255, 128, 111)
        const val ACTUAL_BAR_DP = 18
    }
}
