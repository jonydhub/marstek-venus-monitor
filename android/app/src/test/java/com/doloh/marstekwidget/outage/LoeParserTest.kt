package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoeParserTest {
    private val html = "<div><p><b>Графік погодинних відключень на 17.10.2026</b></p>\n" +
        "<p><b>Інформація станом на 18:56 17.10.2026</b></p>\n" +
        "<p>Група 1.1. Електроенергії немає з 20:00 до 22:00.</p>\n" +
        "<p>Група 1.2. Електроенергії немає з 11:00 до 14:00.</p>\n" +
        "<p>Група 4.2. Електроенергії немає з 10:00 до 12:00, з 20:00 до 24:00.</p>\n" +
        "<p>Група 6.2. Електроенергія є.</p>\n</div>"

    @Test
    fun parsesDateUpdateTimeAndGroupIntervals() {
        val day = LoeParser.parseGpvHtml(html)!!
        assertEquals(LocalDate.of(2026, 10, 17), day.date)
        assertEquals("2026-10-17T18:56+03:00", day.updatedAt)
        assertEquals("2026-10-17T20:00+03:00[Europe/Kyiv]", day.groups.getValue("1.1").single().start.toString())
        assertTrue(day.groups.getValue("6.2").isEmpty())
    }

    @Test
    fun midnightEndRollsIntoNextDay() {
        val windows = LoeParser.parseGpvHtml(html)!!.groups.getValue("4.2")
        assertEquals(2, windows.size)
        assertEquals("2026-10-18T00:00+03:00[Europe/Kyiv]", windows[1].end.toString())
    }

    @Test
    fun emptyHtmlMeansNothingPublished() {
        assertNull(LoeParser.parseGpvHtml(""))
        assertNull(LoeParser.parseGpvHtml(null))
        assertNull(LoeParser.parseGpvHtml("<p>щось інше</p>"))
    }

    @Test
    fun realMenusPayloadWithEmptyTodayIsEmptySchedule() {
        val schedule = LoeParser.parseMenus(Fixtures.text("loe_empty.json"), "1.1")
        assertTrue(schedule.isEmpty)
        assertEquals(OutageSourceKind.LOE, schedule.source)
    }

    @Test
    fun menusPayloadWithScheduleYieldsQueueWindows() {
        val schedule = LoeParser.parseMenus(Fixtures.text("loe_schedule.json"), "1.1")
        assertEquals(listOf(LocalDate.of(2026, 10, 17)), schedule.publishedDates)
        assertEquals(1, schedule.windows.size)
        assertEquals("2026-10-17T18:56+03:00", schedule.updatedAt)
    }

    @Test
    fun publishedDayWithoutTheQueueMeansNoOutageForIt() {
        val schedule = LoeParser.parseMenus(Fixtures.text("loe_schedule.json"), "6.2")
        assertTrue(schedule.windows.isEmpty())
        assertEquals(1, schedule.publishedDates.size)
    }
}
