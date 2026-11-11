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
import com.doloh.marstekwidget.domain.DaySchedule
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

/** SOC bar like the web dashboard: dim track, filled part = red → amber → green → teal gradient spanning the full track. */
object SocBarPainter {
    private val COLORS = intArrayOf(Color.rgb(239, 99, 77), Color.rgb(245, 190, 40), Color.rgb(143, 209, 138), Color.rgb(53, 201, 218))
    private val STOPS = floatArrayOf(0f, 0.42f, 0.72f, 1f)

    fun draw(canvas: Canvas, width: Float, height: Float, socPercent: Int?) {
        val r = height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(31, 255, 255, 255)
        val track = RectF(0f, 0f, width, height)
        canvas.drawRoundRect(track, r, r, paint)
        val soc = (socPercent ?: 0).coerceIn(0, 100)
        if (soc <= 0) return
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, 0f, width, 0f, COLORS, STOPS, Shader.TileMode.CLAMP)
        val fillW = maxOf(height, width * soc / 100f)
        canvas.drawRoundRect(RectF(0f, 0f, fillW, height), r, r, paint)
    }
}

class SocBarView(context: Context) : View(context) {
    private var soc: Int? = null
    fun setSoc(value: Int?) { soc = value; invalidate() }
    override fun onDraw(canvas: Canvas) = SocBarPainter.draw(canvas, width.toFloat(), height.toFloat(), soc)
}

/**
 * One day 0–24 h: amber = definite outage, hatched lighter amber = «можливе», white marker = now (today only).
 * [labelsPx] > 0 adds hour ticks (every hour, longer every 6 h) and «0 6 12 18 24» labels under the bar.
 */
object DayScheduleBarPainter {
    fun draw(canvas: Canvas, width: Float, height: Float, day: DaySchedule, density: Float, labelsPx: Float = 0f) {
        val marker = if (day.nowFraction != null) 2.5f * density else 0f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartColors.LABEL; textSize = labelsPx }
        val tickZone = if (labelsPx > 0f) 6f * density + labelsPx + 2f * density else 0f
        val inset = if (labelsPx > 0f) labelPaint.measureText("24") / 2f else 0f
        val left = maxOf(inset, marker)
        val right = width - maxOf(inset, marker)
        val bar = RectF(left, marker, right, height - tickZone - marker)
        val r = minOf(bar.height() / 2f, 4f * density)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (day.published) ChartColors.TRACK else Color.argb(90, 52, 66, 80)
        canvas.drawRoundRect(bar, r, r, paint)
        if (!day.published) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Color.rgb(70, 88, 104)
            paint.pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
            canvas.drawRoundRect(bar, r, r, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
        }
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bar, r, r, Path.Direction.CW) })
        fun x(f: Float) = bar.left + f * bar.width()
        day.segments.forEach { s ->
            val rect = RectF(x(s.start), bar.top, x(s.end), bar.bottom)
            if (s.maybe) {
                paint.color = Color.argb(90, 245, 183, 46)
                canvas.drawRect(rect, paint)
                paint.color = Color.argb(220, 245, 183, 46)
                paint.strokeWidth = 1.5f * density
                canvas.save()
                canvas.clipRect(rect)
                var hx = rect.left - bar.height()
                while (hx < rect.right) {
                    canvas.drawLine(hx, bar.bottom, hx + bar.height(), bar.top, paint)
                    hx += 4 * density
                }
                canvas.restore()
            } else {
                paint.color = ChartColors.AMBER
                canvas.drawRect(rect, paint)
            }
        }
        // thin separators every 6 h inside the bar keep it readable without labels (widgets)
        paint.color = Color.argb(150, 7, 21, 34)
        for (h in listOf(6, 12, 18)) canvas.drawRect(x(h / 24f) - 0.5f * density, bar.top, x(h / 24f) + 0.5f * density, bar.bottom, paint)
        canvas.restore()
        if (labelsPx > 0f) {
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(84, 102, 118); strokeWidth = density }
            val top = bar.bottom + 2f * density
            for (h in 0..24) {
                val major = h % 6 == 0
                tick.color = if (major) ChartColors.LABEL else Color.rgb(70, 88, 104)
                val len = (if (major) 5f else 2.5f) * density
                canvas.drawLine(x(h / 24f), top, x(h / 24f), top + len, tick)
            }
            val y = height - 1f
            for (h in listOf(0, 6, 12, 18, 24)) {
                val label = h.toString()
                val w = labelPaint.measureText(label)
                canvas.drawText(label, (x(h / 24f) - w / 2f).coerceIn(0f, width - w), y, labelPaint)
            }
        }
        day.nowFraction?.let { f ->
            val nx = x(f)
            paint.color = Color.BLACK
            canvas.drawRoundRect(RectF(nx - 2.5f * density, 0f, nx + 2.5f * density, bar.bottom + marker), density, density, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(nx - 1.5f * density, 0f, nx + 1.5f * density, bar.bottom + marker), density, density, paint)
        }
    }
}

class DayScheduleView(context: Context) : View(context) {
    private var day: DaySchedule? = null
    fun setDay(value: DaySchedule) { day = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        val d = day ?: return
        val density = resources.displayMetrics.density
        DayScheduleBarPainter.draw(canvas, width.toFloat(), height.toFloat(), d, density, labelsPx = 10f * density)
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
