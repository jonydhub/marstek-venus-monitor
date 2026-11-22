package com.doloh.marstekwidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import com.doloh.marstekwidget.domain.ScheduleTimeLabels
import com.doloh.marstekwidget.domain.WeekOutageLabels

/**
 * «Світло за 7 днів»: the row under a day's bar with the battery used per outage («0,42 кВт·год · 80→62 %»), muted,
 * tabular figures, placed by [WeekOutageLabels.weekEnergyLabels] over the same x range as [ActualBarPainter]'s bar.
 * [onMissing] gets (posted, only on change) the outages missing their time label above the bar (same geometry and
 * text size as [ActualBarPainter]) or their energy label here, for the muted line under the row.
 */
class WeekEnergyRowView(context: Context) : View(context) {
    private var outages: List<WeekOutageLabels.DayOutage> = emptyList()
    private var labels: List<ScheduleTimeLabels.Label> = emptyList()
    private var lastMissing: List<WeekOutageLabels.DayOutage>? = null
    var onMissing: ((List<WeekOutageLabels.DayOutage>) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics)
        fontFeatureSettings = "tnum"
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DayScheduleView.TIME_LABEL_SP, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val insetPaint = Paint().apply { textSize = 10f * density }

    fun setOutages(value: List<WeekOutageLabels.DayOutage>) {
        outages = value
        lastMissing = null
        if (width > 0) relayout(width)
        invalidate()
    }

    /** Row height: one line of [TEXT_SP] text + a little air; 1 px when no outage has an energy text. */
    fun rowHeightPx(): Int = if (outages.none { WeekOutageLabels.weekEnergyTexts(it).isNotEmpty() }) 1
    else kotlin.math.ceil(paint.fontMetrics.let { it.descent - it.ascent } + 2f * density).toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) relayout(w)
    }

    private fun relayout(w: Int) {
        // same side insets as ActualBarPainter with alignPx = 10 dp (marker 2.5 dp, half of «24»)
        val left = maxOf(insetPaint.measureText("24") / 2f, 2.5f * density)
        val barWidth = w - 2 * left
        val energy = WeekOutageLabels.weekEnergyLabels(outages, left, barWidth, w.toFloat(), 4f * density) { paint.measureText(it) }
        labels = energy.labels
        val unlabeled = ScheduleTimeLabels.unlabeled(outages.map { it.segment }, null, null, left, barWidth, w.toFloat(), 3f * density) {
            timePaint.measureText(it)
        }
        val missing = WeekOutageLabels.weekMissing(outages, unlabeled, energy.skipped)
        if (missing != lastMissing) {
            lastMissing = missing
            post { onMissing?.invoke(missing) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = -paint.fontMetrics.ascent + 1f * density
        labels.forEach { canvas.drawText(it.text, it.left, baseline, paint) }
    }

    companion object { const val TEXT_SP = 10.5f }
}
