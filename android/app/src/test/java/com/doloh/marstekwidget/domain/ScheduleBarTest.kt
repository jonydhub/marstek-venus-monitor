package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleBarTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, kyiv).toInstant().toEpochMilli()

    @Test
    fun todayAndTomorrowMapTo48HoursFromMidnight() {
        val model = ScheduleBar.model(listOf(ScheduleWindow(at(10, 12), at(10, 18), false), ScheduleWindow(at(11, 6), at(11, 12), true)), at(10, 12))
        assertEquals(0.25f, model.nowFraction, 0.001f)
        assertTrue(model.hasSchedule)
        val definite = model.segments.single { !it.maybe }
        assertEquals(0.25f, definite.start, 0.001f)
        assertEquals(0.375f, definite.end, 0.001f)
        assertEquals(0.625f, model.segments.single { it.maybe }.start, 0.001f)
        // definite windows drawn last (on top of «можливе»)
        assertFalse(model.segments.last().maybe)
    }

    @Test
    fun windowsOutsideTheTwoDaysAreClipped() {
        val model = ScheduleBar.model(listOf(ScheduleWindow(at(9, 20), at(10, 2), false), ScheduleWindow(at(12, 1), at(12, 3), false)), at(10, 1))
        assertEquals(1, model.segments.size)
        assertEquals(0f, model.segments[0].start, 0.0001f)
    }
}
