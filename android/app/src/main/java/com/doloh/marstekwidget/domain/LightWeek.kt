package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** One Kyiv day of the measured light timeline («Світло за 7 днів»): same shape as the server's `/api/light-week` days. */
data class WeekDay(
    val date: LocalDate,
    val isToday: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val offSeconds: Long,
    val onSeconds: Long,
    val unknownSeconds: Long,
    val timeline: List<LightSpan>,
)

/**
 * «Світло за 7 днів» (port of the server `week_light` + web `weekDayTotals` / `weekDayTitle`, keep in sync).
 * Schedules are published only for today and tomorrow, so the week view is what was measured on the backup output.
 */
object LightWeek {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val uk: Locale = Locale.forLanguageTag("uk-UA")
    const val DAYS = 7

    /** Parses `GET /api/light-week` (newest day first); empty list for an unexpected body. */
    fun fromJson(root: JSONObject): List<WeekDay> {
        val arr = root.optJSONArray("days") ?: return emptyList()
        fun ms(v: String?) = v?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
            val start = ms(o.optString("start")) ?: date.atStartOfDay(kyiv).toInstant().toEpochMilli()
            val end = ms(o.optString("end")) ?: date.plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli()
            val spans = o.optJSONArray("timeline")?.let { t ->
                (0 until t.length()).mapNotNull { j ->
                    val s = t.optJSONObject(j) ?: return@mapNotNull null
                    val a = ms(s.optString("start")) ?: return@mapNotNull null
                    val b = ms(s.optString("end")) ?: return@mapNotNull null
                    LightSpan(a, b, s.optString("state", "unknown"))
                }
            }.orEmpty()
            WeekDay(date, o.optBoolean("is_today"), start, end, o.optLong("off_seconds"), o.optLong("on_seconds"),
                o.optLong("unknown_seconds"), spans)
        }
    }

    /** Splits one light timeline (e.g. the phone's own 48 h history in direct mode) into Kyiv days, newest first. */
    fun fromTimeline(timeline: List<LightSpan>, nowMillis: Long, days: Int = DAYS): List<WeekDay> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(kyiv).toLocalDate()
        return (0 until days).map { k ->
            val date = today.minusDays(k.toLong())
            val start = date.atStartOfDay(kyiv).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli()
            val end = if (k == 0) minOf(nowMillis, dayEnd) else dayEnd
            val totals = mutableMapOf("on" to 0L, "off" to 0L, "unknown" to 0L)
            val spans = timeline.mapNotNull { s ->
                val a = maxOf(s.startMillis, start)
                val b = minOf(s.endMillis, end)
                if (b > a) LightSpan(a, b, s.state) else null
            }
            spans.forEach { s -> val key = if (s.state == "on" || s.state == "off") s.state else "unknown"; totals[key] = totals.getValue(key) + (s.endMillis - s.startMillis) }
            val covered = totals.values.sum()
            val unknown = totals.getValue("unknown") + (end - start - covered).coerceAtLeast(0)
            WeekDay(date, k == 0, start, dayEnd, (totals.getValue("off") + 500) / 1000, (totals.getValue("on") + 500) / 1000,
                (unknown + 500) / 1000, spans)
        }
    }

    private fun span(seconds: Long): String {
        val m = ((seconds + 30) / 60).coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        val nb = '\u00A0'
        return if (h > 0) (if (r > 0) "$h${nb}год $r${nb}хв" else "$h${nb}год") else "$r${nb}хв"
    }

    /** «Без світла 4 год 58 хв · зі світлом 16 год 46 хв» / «Без відключень» (+ «без даних …» when ≥ 30 min). */
    fun totalsText(day: WeekDay): String {
        if (day.offSeconds == 0L && day.onSeconds == 0L) return if (day.isToday) "Ще немає даних" else "Немає даних"
        val parts = if (day.offSeconds >= 60) mutableListOf("Без світла ${span(day.offSeconds)}", "зі світлом ${span(day.onSeconds)}")
        else mutableListOf("Без відключень")
        if (day.unknownSeconds >= 30 * 60) parts += "без даних ${span(day.unknownSeconds)}"
        return parts.joinToString(" · ")
    }

    /** «Сьогодні», «Вчора», «Пн, 16.11». */
    fun title(day: WeekDay, today: LocalDate): String = when (day.date) {
        today -> "Сьогодні"
        today.minusDays(1) -> "Вчора"
        else -> day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, uk).replaceFirstChar { it.titlecase(uk) } +
            ", %02d.%02d".format(day.date.dayOfMonth, day.date.monthValue)
    }

    /** «За 7 днів без світла 4 год 58 хв · днів з відключеннями: 1»; null without any measurement. */
    fun summary(days: List<WeekDay>): String? {
        if (days.none { it.offSeconds + it.onSeconds > 0 }) return null
        val off = days.sumOf { it.offSeconds }
        val withOutage = days.count { it.offSeconds >= 60 }
        return "За ${days.size} ${daysWord(days.size)} без світла ${span(off)}" + if (withOutage > 0) " · днів з відключеннями: $withOutage" else ""
    }

    private fun daysWord(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "день"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "дні"
        else -> "днів"
    }

    /** The day as an «Фактично» bar: fractions of the whole day; today stays grey after now. */
    fun bar(day: WeekDay, nowMillis: Long): ActualDay {
        val span = (day.endMillis - day.startMillis).toDouble().coerceAtLeast(1.0)
        fun f(t: Long) = ((t - day.startMillis) / span).coerceIn(0.0, 1.0).toFloat()
        val state = when { day.offSeconds >= 60 -> "off"; day.onSeconds > 0 -> "on"; else -> "unknown" }
        return ActualDay(day.timeline.map { ActualDay.Segment(f(it.startMillis), f(it.endMillis), it.state) },
            if (day.isToday) f(nowMillis) else 1f, state, totalsText(day))
    }
}
