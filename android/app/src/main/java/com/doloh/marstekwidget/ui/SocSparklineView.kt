package com.doloh.marstekwidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import com.doloh.marstekwidget.domain.SocPoint

/** Minimal 24 h SOC sparkline: time on X (last 24 h), 0–100 % on Y. */
class SocSparklineView(context: Context) : View(context) {
    private var points: List<SocPoint> = emptyList()
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(54, 198, 213)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = Color.rgb(42, 61, 75)
    }

    fun setPoints(value: List<SocPoint>) {
        points = value.sortedBy(SocPoint::epochMillis)
        contentDescription = if (points.isEmpty()) null else "SOC: ${points.first().soc}% → ${points.last().soc}%"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 3f * density
        listOf(0f, 0.5f, 1f).forEach { f ->
            val y = pad + (h - 2 * pad) * f
            canvas.drawLine(0f, y, w, y, grid)
        }
        if (points.size < 2) return
        val end = points.last().epochMillis
        val start = minOf(points.first().epochMillis, end - 24L * 3600_000L)
        val span = (end - start).coerceAtLeast(1L).toFloat()
        fun x(p: SocPoint) = (p.epochMillis - start) / span * w
        fun y(p: SocPoint) = pad + (h - 2 * pad) * (1f - p.soc / 100f)
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
        val area = Path(path).apply {
            lineTo(x(points.last()), h)
            lineTo(x(points.first()), h)
            close()
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(90, 54, 198, 213), Color.argb(0, 54, 198, 213), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
    }
}
