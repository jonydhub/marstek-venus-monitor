package com.doloh.marstekwidget.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Today + tomorrow (0–24 | 0–24) as fractions of 48 h from local midnight; shared by the app view and widget bitmaps. */
data class ScheduleBarModel(val segments: List<Segment>, val nowFraction: Float, val hasSchedule: Boolean) {
    /** [from]/[to] = «HH:mm» of the (day-clipped) window edges, «24:00» for the end of the day; empty when unknown. */
    data class Segment(val start: Float, val end: Float, val maybe: Boolean, val from: String = "", val to: String = "")
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

/** One day (0–24 h, Kyiv) of the schedule: bar segments as fractions of that day, its windows and whether it is published. */
data class DaySchedule(
    val date: LocalDate,
    val isToday: Boolean,
    val segments: List<ScheduleBarModel.Segment>,
    /** Fraction of the day for the «now» marker (today only). */
    val nowFraction: Float?,
    val published: Boolean,
    /** Clipped to the day, «HH:mm–HH:mm» (24:00 for midnight at the end). */
    val definite: List<String>,
    val maybe: List<String>,
    /** «10:42» for the now marker (today only). */
    val nowText: String? = null,
) {
    val title: String get() = if (isToday) "Сьогодні" else "Завтра"

    /** «Відключення 07:00–13:00, 15:00–21:00» / «Без відключень» / «графік ще не опубліковано». */
    val windowsText: String
        get() = when {
            definite.isNotEmpty() -> "Відключення ${definite.joinToString(", ")}"
            !published -> if (isToday) "Графік не опубліковано" else "Графік ще не опубліковано"
            maybe.isEmpty() -> "Без відключень"
            else -> "Без точних відключень"
        }

    val maybeText: String? get() = maybe.takeIf { it.isNotEmpty() }?.let { "Можливе ${it.joinToString(", ")}" }

    /** One-line summary for compact places: «Завтра: графік ще не опубліковано». */
    val summary: String get() = "$title: " + windowsText.replaceFirstChar { it.lowercase() }
}

object ScheduleDays {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun days(info: PowerOutageInfo?, nowMillis: Long): List<DaySchedule> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(kyiv).toLocalDate()
        return listOf(day(info, today, today, nowMillis), day(info, today.plusDays(1), today, nowMillis))
    }

    fun day(info: PowerOutageInfo?, date: LocalDate, today: LocalDate, nowMillis: Long): DaySchedule {
        val start = date.atStartOfDay(kyiv).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli()
        val span = (end - start).toDouble()
        val clipped = info?.windows.orEmpty().mapNotNull { w ->
            val a = maxOf(w.startMillis, start)
            val b = minOf(w.endMillis, end)
            if (b > a) Triple(a, b, w.maybe) else null
        }
        fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(kyiv).format(clock)
        fun toText(b: Long) = if (b >= end) "24:00" else hm(b)
        val segments = clipped.map { (a, b, maybe) ->
            ScheduleBarModel.Segment(((a - start) / span).toFloat(), ((b - start) / span).toFloat(), maybe, hm(a), toText(b))
        }.sortedWith(compareBy({ !it.maybe }, { it.start }))
        fun label(a: Long, b: Long): String = "${hm(a)}–${toText(b)}"
        val published = info != null && (date.toString() in info.scheduleDates || clipped.isNotEmpty() ||
            (info.scheduleDates.isEmpty() && info.schedulePublished && date == today))
        val isToday = date == today
        return DaySchedule(
            date = date,
            isToday = isToday,
            segments = segments,
            nowFraction = if (isToday) ((nowMillis - start) / span).coerceIn(0.0, 1.0).toFloat() else null,
            published = published,
            definite = clipped.filter { !it.third }.sortedBy { it.first }.map { label(it.first, it.second) },
            maybe = clipped.filter { it.third }.sortedBy { it.first }.map { label(it.first, it.second) },
            nowText = if (isToday) hm(nowMillis) else null,
        )
    }
}
