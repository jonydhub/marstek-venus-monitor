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
    fun setDays(days: List<DaySchedule>, actual: ActualDay? = null, scheduleNow: String? = null) {
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
            addView(DayScheduleView(context).apply {
                setDay(day)
                contentDescription = "${day.title}: ${day.windowsText}" + (day.maybeText?.let { "; $it" } ?: "")
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(barHeightDp) + timeLabelZonePx()).apply { topMargin = dp(3) })
            addView(text(day.windowsText, 12f, if (day.definite.isNotEmpty()) AMBER else MUTED, day.definite.isNotEmpty()), params(dp(2)))
            day.maybeText?.let { addView(text(it, 11f, AMBER_SOFT, false), params(dp(1))) }
            if (day.isToday && actual != null) addActual(actual)
        }
    }

    private fun addActual(actual: ActualDay) {
        val color = when (actual.state) { "on" -> GREEN; "off" -> CORAL; else -> MUTED }
        addView(text(actual.text, 10.5f, color, true), params(dp(8)))
        addView(ActualDayView(context).apply { setDay(actual) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ACTUAL_BAR_DP) + actualTickZonePx()).apply { topMargin = dp(3) })
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
