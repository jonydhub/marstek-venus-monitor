package com.doloh.marstekwidget.domain

import java.time.Instant
import java.time.ZoneId

/** Today + tomorrow (0–24 | 0–24) as fractions of 48 h from local midnight; shared by the app view and widget bitmaps. */
data class ScheduleBarModel(val segments: List<Segment>, val nowFraction: Float, val hasSchedule: Boolean) {
    data class Segment(val start: Float, val end: Float, val maybe: Boolean)
}

object ScheduleBar {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private const val SPAN_MILLIS = 48 * 3600_000L

    fun midnightMillis(nowMillis: Long): Long =
        Instant.ofEpochMilli(nowMillis).atZone(kyiv).toLocalDate().atStartOfDay(kyiv).toInstant().toEpochMilli()

    fun model(windows: List<ScheduleWindow>, nowMillis: Long): ScheduleBarModel {
        val start = midnightMillis(nowMillis)
        fun frac(t: Long) = ((t - start).toDouble() / SPAN_MILLIS).coerceIn(0.0, 1.0).toFloat()
        val segments = windows.mapNotNull { w ->
            val a = frac(w.startMillis)
            val b = frac(w.endMillis)
            if (b > a) ScheduleBarModel.Segment(a, b, w.maybe) else null
        }.sortedWith(compareBy({ !it.maybe }, { it.start })) // definite drawn last = on top
        return ScheduleBarModel(segments, frac(nowMillis), windows.isNotEmpty())
    }
}
