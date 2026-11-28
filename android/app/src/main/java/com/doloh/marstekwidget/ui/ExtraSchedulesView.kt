package com.doloh.marstekwidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.domain.DaySchedule
import com.doloh.marstekwidget.domain.ExtraRow
import com.doloh.marstekwidget.domain.ExtraState

/**
 * Today's bar of one «Інші графіки» row: window times above the bar (same label rules), the white now-marker on it,
 * no «now» time label and no 0–24 axis — the compact row stays two lines high.
 */
class CompactDayView(context: Context, private val labelSp: Float = 9.5f) : android.view.View(context) {
    private var day: DaySchedule? = null

    fun setDay(value: DaySchedule) {
        val next = value.copy(nowText = null)
        if (next == day) return
        day = next
        contentDescription = "${next.title}: ${next.windowsText}" + (next.maybeText?.let { "; $it" } ?: "")
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = day ?: return
        DayScheduleBarPainter.draw(canvas, width.toFloat(), height.toFloat(), d, resources.displayMetrics.density,
            timeLabelsPx = labelPx(resources.displayMetrics, labelSp))
    }

    companion object {
        fun labelPx(metrics: android.util.DisplayMetrics, sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)

        /** View height for a bar of [barDp] plus the label row above it. */
        fun heightPx(context: Context, barDp: Float, labelSp: Float = 9.5f): Int {
            val m = context.resources.displayMetrics
            return kotlin.math.ceil(barDp * m.density + DayScheduleBarPainter.timeLabelZone(labelPx(m, labelSp), m.density)).toInt()
        }
    }
}

/**
 * Main screen «Інші графіки»: small heading, then per schedule one header line (name · region/queue · state) and
 * today's bar. Rows are updated in place — the 15-s refresh only moves the now-marker, nothing is rebuilt
 * (a rebuild made the screen jump: «апка сіпається»). Hidden when the list is empty.
 */
class ExtraSchedulesView(context: Context, private val textScale: Float = 1f) : LinearLayout(context) {
    private class RowViews(val box: LinearLayout, val name: TextView, val sub: TextView, val status: TextView, val bar: CompactDayView)

    private val heading = text("Інші графіки", 11f, MUTED, true)
    private val rows = mutableListOf<RowViews>()

    init {
        orientation = VERTICAL
        addView(heading, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        visibility = GONE
    }

    fun setRows(items: List<ExtraRow>) {
        visibility = if (items.isEmpty()) GONE else VISIBLE
        while (rows.size > items.size) removeView(rows.removeAt(rows.lastIndex).box)
        while (rows.size < items.size) rows += newRow().also { addView(it.box, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }) }
        items.forEachIndexed { i, row ->
            val v = rows[i]
            v.name.setIfChanged(row.name)
            v.sub.setIfChanged(row.sub)
            v.status.setIfChanged(row.status)
            val color = stateColor(row.state)
            if (v.status.currentTextColor != color) v.status.setTextColor(color)
            val today = row.days?.firstOrNull()
            val vis = if (today == null) GONE else VISIBLE
            if (v.bar.visibility != vis) v.bar.visibility = vis
            today?.let(v.bar::setDay)
            v.box.contentDescription = "${row.name}, ${row.sub}: ${row.status}"
        }
    }

    private fun newRow(): RowViews {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // the name never gives way: the muted region/queue is ellipsized first
        val name = text("", 12f, TEXT, true).apply { maxLines = 1 }
        val sub = text("", 10f, MUTED, false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val status = text("", 11f, MUTED, true).apply { maxLines = 1; gravity = Gravity.END }
        head.addView(name, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(sub, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        head.addView(status, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        box.addView(head)
        val bar = CompactDayView(context)
        box.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CompactDayView.heightPx(context, BAR_DP)).apply { topMargin = dp(2) })
        return RowViews(box, name, sub, status, bar)
    }

    private fun TextView.setIfChanged(value: String) { if (text.toString() != value) text = value }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = value
        textSize = sp * textScale
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val BAR_DP = 14f
        val TEXT = Color.rgb(243, 247, 250)
        val MUTED = Color.rgb(160, 173, 184)
        val AMBER = Color.rgb(245, 183, 46)
        val GREEN = Color.rgb(127, 227, 178)

        /** «без світла» amber, «світло є» green, the rest muted. */
        fun stateColor(state: ExtraState) = when (state) {
            ExtraState.OFF -> AMBER
            ExtraState.ON -> GREEN
            else -> MUTED
        }
    }
}
