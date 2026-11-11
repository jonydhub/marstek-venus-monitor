package com.doloh.marstekwidget.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.domain.DaySchedule

/**
 * «Сьогодні» / «Завтра» rows, each 0–24 h with window start/end times and the current time above the bar, hour ticks
 * and labels below it, then the windows as text
 * («Відключення 07:00–13:00, 15:00–21:00», «Можливе …»). An unpublished tomorrow is a single text line.
 */
class ScheduleDaysView(context: Context, private val textScale: Float = 1f, private val barHeightDp: Int = 38) : LinearLayout(context) {
    init { orientation = VERTICAL }

    fun setDays(days: List<DaySchedule>) {
        removeAllViews()
        days.forEachIndexed { index, day ->
            val top = if (index == 0) 0 else dp(10)
            if (!day.isToday && !day.published) {
                addView(text("Завтра: графік ще не опубліковано", 12f, MUTED, false), params(top))
                return@forEachIndexed
            }
            addView(text(day.title, 12f, TEXT, true), params(top))
            addView(DayScheduleView(context).apply {
                setDay(day)
                contentDescription = "${day.title}: ${day.windowsText}" + (day.maybeText?.let { "; $it" } ?: "")
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(barHeightDp) + timeLabelZonePx()).apply { topMargin = dp(3) })
            addView(text(day.windowsText, 12f, if (day.definite.isNotEmpty()) AMBER else MUTED, day.definite.isNotEmpty()), params(dp(2)))
            day.maybeText?.let { addView(text(it, 11f, AMBER_SOFT, false), params(dp(1))) }
        }
    }

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
    }
}
