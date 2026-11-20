package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Same cases as server/tests/test_week_totals.py and test_light_week.py. */
class LightWeekTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val h = 3600L
    private fun ms(text: String) = java.time.LocalDateTime.parse(text).atZone(kyiv).toInstant().toEpochMilli()
    private fun day(off: Long, on: Long, unknown: Long, today: Boolean = false) =
        WeekDay(LocalDate.of(2026, 11, 20), today, 0, 0, off, on, unknown, emptyList())
    private fun t(d: WeekDay) = LightWeek.totalsText(d).replace('\u00A0', ' ')

    @Test fun dayWithOutage() = assertEquals("Без світла 4 год 58 хв · зі світлом 16 год 46 хв", t(day(4 * h + 58 * 60, 16 * h + 46 * 60, 0, today = true)))
    @Test fun dayWithoutOutage() = assertEquals("Без відключень", t(day(0, 24 * h, 0)))
    @Test fun shortGapIsNotShown() = assertEquals("Без світла 2 год · зі світлом 21 год 50 хв", t(day(2 * h, 21 * h + 50 * 60, 10 * 60)))
    @Test fun longGapIsShown() = assertEquals("Без світла 1 год · зі світлом 20 год · без даних 3 год", t(day(h, 20 * h, 3 * h)))
    @Test fun noData() {
        assertEquals("Немає даних", t(day(0, 0, 24 * h)))
        assertEquals("Ще немає даних", t(day(0, 0, 5 * h, today = true)))
    }

    @Test fun outageAcrossMidnightIsSplit() {
        val timeline = listOf(
            LightSpan(ms("2026-11-19T20:00:00"), ms("2026-11-19T23:00:00"), "on"),
            LightSpan(ms("2026-11-19T23:00:00"), ms("2026-11-20T01:00:00"), "off"),
            LightSpan(ms("2026-11-20T01:00:00"), ms("2026-11-20T12:00:00"), "on"),
        )
        val days = LightWeek.fromTimeline(timeline, ms("2026-11-20T12:00:00"))
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 11, 20), days[0].date)
        assertTrue(days[0].isToday)
        assertEquals(3600L, days[0].offSeconds)
        assertEquals(11 * h, days[0].onSeconds)
        assertEquals(0L, days[0].unknownSeconds)  // today only up to now
        assertEquals(3600L, days[1].offSeconds)
        assertEquals(20 * h, days[1].unknownSeconds)  // no data before 20:00
        assertEquals(24 * h, days[3].unknownSeconds)
    }

    @Test fun parsesServerJson() {
        val body = JSONObject("""{"days":[{"date":"2026-11-20","is_today":true,"start":"2026-11-20T00:00:00+02:00",
            "end":"2026-11-21T00:00:00+02:00","off_seconds":3600,"on_seconds":39600,"unknown_seconds":0,
            "timeline":[{"start":"2026-11-20T00:00:00+02:00","end":"2026-11-20T01:00:00+02:00","state":"off"}]}]}""")
        val d = LightWeek.fromJson(body).single()
        assertEquals(LocalDate.of(2026, 11, 20), d.date)
        assertEquals(3600L, d.offSeconds)
        assertEquals("off", d.timeline.single().state)
        val bar = LightWeek.bar(d, ms("2026-11-20T12:00:00"))
        assertEquals(0.5f, bar.nowFraction, 0.001f)
        assertEquals(1f / 24f, bar.segments.single().end, 0.001f)
    }

    @Test fun titlesAndSummary() {
        val today = LocalDate.of(2026, 11, 20)
        assertEquals("Сьогодні", LightWeek.title(day(0, 0, 0).copy(date = today), today))
        assertEquals("Вчора", LightWeek.title(day(0, 0, 0).copy(date = today.minusDays(1)), today))
        assertEquals("Пн, 16.11", LightWeek.title(day(0, 0, 0).copy(date = LocalDate.of(2026, 11, 16)), today))
        val week = listOf(day(4 * h + 58 * 60, 16 * h, 0)) + List(6) { day(0, 24 * h, 0) }
        assertEquals("За 7 днів без світла 4 год 58 хв · днів з відключеннями: 1", LightWeek.summary(week)?.replace('\u00A0', ' '))
        assertNull(LightWeek.summary(listOf(day(0, 0, 24 * h))))
    }
}
