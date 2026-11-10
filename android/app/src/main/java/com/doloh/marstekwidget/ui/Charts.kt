package com.doloh.marstekwidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.doloh.marstekwidget.domain.ChartEvent
import com.doloh.marstekwidget.domain.ScheduleBarModel
import com.doloh.marstekwidget.domain.SocPoint
import com.doloh.marstekwidget.domain.TimeSpan

/** Shared drawing for the app views and the widget bitmaps (same look everywhere). */
object ChartColors {
    val AMBER = Color.rgb(245, 183, 46)
    val TRACK = Color.rgb(52, 66, 80)
    val LINE = Color.rgb(54, 198, 213)
    val CORAL = Color.rgb(255, 128, 111)
    val GRID = Color.rgb(42, 61, 75)
    val LABEL = Color.rgb(139, 155, 167)
    val POWER_ON = Color.rgb(127, 227, 178)
    val BLUE = Color.rgb(120, 184, 255)
}

object ScheduleBarPainter {
    /** Bar only (widgets: no text, fitXY-safe); [labelsPx] > 0 adds «0 12 0 12 24» under it (app view). */
    fun draw(canvas: Canvas, width: Float, height: Float, model: ScheduleBarModel, density: Float, labelsPx: Float = 0f) {
        val barH = if (labelsPx > 0f) height - labelsPx - 3 * density else height
        val top = if (labelsPx > 0f) 2 * density else 0f
        val r = barH / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ChartColors.TRACK
        val bar = RectF(0f, top, width, top + barH - (if (labelsPx > 0f) 2 * density else 0f))
        canvas.drawRoundRect(bar, r, r, paint)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bar, r, r, Path.Direction.CW) })
        model.segments.forEach { s ->
            val rect = RectF(s.start * width, bar.top, s.end * width, bar.bottom)
            if (s.maybe) {
                paint.color = Color.argb(110, 245, 183, 46)
                canvas.drawRect(rect, paint)
                paint.color = ChartColors.AMBER
                paint.strokeWidth = 1.5f * density
                var x = rect.left - bar.height()
                while (x < rect.right) {
                    canvas.drawLine(x, bar.bottom, x + bar.height(), bar.top, paint)
                    x += 4 * density
                }
            } else {
                paint.color = ChartColors.AMBER
                canvas.drawRect(rect, paint)
            }
        }
        paint.color = Color.rgb(7, 21, 34)
        canvas.drawRect(width / 2f - density, bar.top, width / 2f + density, bar.bottom, paint)
        canvas.restore()
        val nx = (model.nowFraction * width).coerceIn(1.5f * density, width - 1.5f * density)
        paint.color = Color.BLACK
        canvas.drawRoundRect(RectF(nx - 2.5f * density, 0f, nx + 2.5f * density, bar.bottom + top), density, density, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(nx - 1.5f * density, 0f, nx + 1.5f * density, bar.bottom + top), density, density, paint)
        if (labelsPx > 0f) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.LABEL; textSize = labelsPx }
            val y = height - 1f
            listOf("0", "12", "0", "12", "24").forEachIndexed { i, label ->
                val w = text.measureText(label)
                val x = (width * i / 4f - if (i == 0) 0f else if (i == 4) w else w / 2f)
                canvas.drawText(label, x, y, text)
            }
        }
    }
}

object SocChartPainter {
    data class Input(
        val points: List<SocPoint>,
        val outages: List<TimeSpan>,
        val events: List<ChartEvent>,
        val floorPct: Double?,
        val nowMillis: Long,
    )

    private fun eventColor(kind: String) = when (kind) {
        "light_lost" -> ChartColors.AMBER
        "light_returned" -> ChartColors.POWER_ON
        "charging_started" -> ChartColors.LINE
        else -> ChartColors.BLUE
    }

    private fun eventGlyph(kind: String) = when (kind) {
        "light_lost" -> "●"
        "light_returned" -> "○"
        "charging_started" -> "⚡"
        else -> "✓"
    }

    /** [labels]: y-axis 25/50/75/100 %, floor %, x «−24 год · −12 год · зараз» (app view); widgets pass false. */
    fun draw(canvas: Canvas, width: Float, height: Float, input: Input, density: Float, labels: Boolean) {
        val labelSize = 9.5f * density
        val right = if (labels) width - 30 * density else width
        val top = 6 * density
        val bottom = if (labels) height - 14 * density else height - 2 * density
        val end = input.nowMillis
        val start = end - 24L * 3600_000L
        fun x(t: Long) = ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f) * right
        fun y(soc: Double) = bottom - (soc.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(48, 245, 183, 46)
        input.outages.forEach { p -> canvas.drawRect(x(p.startMillis), top, x(p.endMillis), bottom, paint) }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.GRID; strokeWidth = density }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.LABEL; textSize = labelSize }
        listOf(25, 50, 75, 100).forEach { level ->
            val yy = y(level.toDouble())
            canvas.drawLine(0f, yy, right, yy, grid)
            if (labels) canvas.drawText("$level%", right + 4 * density, yy + labelSize / 3f, text)
        }
        input.floorPct?.let { floor ->
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ChartColors.CORAL; strokeWidth = 1.2f * density; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
            }
            val yy = y(floor)
            canvas.drawPath(Path().apply { moveTo(0f, yy); lineTo(right, yy) }, dash)
            if (labels) canvas.drawText("${floor.toInt()}%", right + 4 * density, yy + labelSize / 3f, text.apply { color = ChartColors.CORAL })
            text.color = ChartColors.LABEL
        }
        if (labels) {
            canvas.drawText("−24 год", 0f, height - 2 * density, text)
            val mid = "−12 год"
            canvas.drawText(mid, right / 2f - text.measureText(mid) / 2f, height - 2 * density, text)
            val now = "зараз"
            canvas.drawText(now, right - text.measureText(now), height - 2 * density, text)
        }
        val pts = input.points.filter { it.epochMillis >= start - 3600_000L }.sortedBy { it.epochMillis }
        if (pts.size < 2) return
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.epochMillis), y(p.soc.toDouble())) else path.lineTo(x(p.epochMillis), y(p.soc.toDouble())) }
        val area = Path(path).apply { lineTo(x(pts.last().epochMillis), bottom); lineTo(x(pts.first().epochMillis), bottom); close() }
        paint.shader = LinearGradient(0f, top, 0f, bottom, Color.argb(90, 54, 198, 213), Color.argb(0, 54, 198, 213), Shader.TileMode.CLAMP)
        canvas.drawPath(area, paint)
        paint.shader = null
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.2f * density; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            color = ChartColors.LINE
        }
        canvas.drawPath(path, line)
        val last = pts.last()
        paint.color = ChartColors.LINE
        canvas.drawCircle(x(last.epochMillis), y(last.soc.toDouble()), 3.5f * density, paint)
        fun socAt(t: Long) = pts.minBy { kotlin.math.abs(it.epochMillis - t) }.soc.toDouble()
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f * density; color = Color.rgb(16, 20, 28); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        input.events.filter { it.epochMillis in start..end }.forEach { e ->
            val ex = x(e.epochMillis)
            val color = eventColor(e.kind)
            grid.color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawLine(ex, top, ex, bottom, grid)
            if (!labels) return@forEach
            val cy = maxOf(top + 7 * density, y(socAt(e.epochMillis)) - 10 * density)
            paint.color = color
            canvas.drawCircle(ex, cy, 7 * density, paint)
            canvas.drawText(eventGlyph(e.kind), ex, cy + 3 * density, glyph)
        }
        grid.color = ChartColors.GRID
    }
}

class ScheduleBarView(context: Context) : View(context) {
    private var model: ScheduleBarModel? = null
    fun setModel(value: ScheduleBarModel) { model = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        model?.let { ScheduleBarPainter.draw(canvas, width.toFloat(), height.toFloat(), it, d, labelsPx = 10f * d) }
    }
}

class SocChartView(context: Context) : View(context) {
    private var input: SocChartPainter.Input? = null
    fun setInput(value: SocChartPainter.Input) {
        input = value
        contentDescription = value.points.takeIf { it.isNotEmpty() }?.let { "Заряд за 24 год: ${it.first().soc}% → ${it.last().soc}%" }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        input?.let { SocChartPainter.draw(canvas, width.toFloat(), height.toFloat(), it, resources.displayMetrics.density, labels = true) }
    }
}
