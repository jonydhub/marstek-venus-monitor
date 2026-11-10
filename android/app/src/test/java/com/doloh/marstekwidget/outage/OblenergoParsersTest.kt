package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** Pages captured 2026-11-10 ~15:40 Kyiv from a Ukrainian connection (same fixtures as the server tests). */
class OblenergoParsersTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val queues = (1..6).flatMap { listOf("$it.1", "$it.2") }

    private fun at(h: Int, m: Int = 0, day: Int = 10) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, KYIV)
    private fun off(schedule: OutageSchedule) = schedule.windows.filter { !it.maybe }.map { it.start to it.end }

    @Test
    fun uaDatesAndGroupLines() {
        assertEquals(LocalDate.of(2026, 11, 11), UaScheduleText.findDate("ГПВ на 11 листопада", today))
        assertEquals(LocalDate.of(2026, 11, 10), UaScheduleText.findDate("ОНОВЛЕНО ГПВ НА 10 ЛИСТОПАДА", today))
        assertEquals(LocalDate.of(2026, 11, 9), UaScheduleText.findDate("графік 09.11.2026", today))
        assertEquals(LocalDate.of(2026, 12, 31), UaScheduleText.findDate("31 грудня", LocalDate.of(2027, 1, 1)))
        assertNull(UaScheduleText.findDate("черга 3.1 о 21:25", today))
        val lines = UaScheduleText.groupLines("<p>1.1 00:00-01:30; 06:00-12:00<br>1.2: 19:00 – 24:00</p><p>Групи 2.1, 2.2: з 08:00 до 10:00</p>")
        assertEquals(listOf(DaySlot(0, 90), DaySlot(360, 720)), lines["1.1"])
        assertEquals(listOf(DaySlot(1140, 1440)), lines["1.2"])
        assertEquals(lines["2.1"], lines["2.2"])
        assertTrue(UaScheduleText.groupLines("ГПВ в обсязі 2.5 черги з 08:00 до 22:00").isEmpty())
    }

    @Test
    fun kharkivJsonApiPosts() {
        val days = OblenergoParsers.kharkiv(Fixtures.text("oblenergo_kharkiv.json"), today)
        assertEquals(queues, days.groups)
        assertEquals("2026-11-09T20:23:26+02:00", days.updatedAt)
        val s = days.toSchedule(OutageSourceKind.KHARKIV, "1.1", today)
        assertEquals(listOf(today), s.publishedDates) // yesterday's post is dropped
        assertEquals(listOf(at(0) to at(1, 30), at(6) to at(12), at(15, 30) to at(22)), off(s))
        assertTrue(OblenergoParsers.KHARKIV_URL.endsWith("filter%5Bt%5D%5Bcondition%5D%5Bvalue%5D=%D0%BF%D0%BE%D0%B3%D0%BE%D0%B4%D0%B8%D0%BD%D0%BD"))
    }

    @Test
    fun zaporizhzhiaNewestPostWins() {
        val days = OblenergoParsers.zaporizhzhia(Fixtures.text("oblenergo_zaporizhzhia.html"), today)
        assertEquals(queues, days.groups)
        assertNull(days.updatedAt)
        // the 14:40 correction (first on the page) adds 14:30–18:00 for 1.2
        assertEquals(listOf(DaySlot(330, 540), DaySlot(870, 1080), DaySlot(1410, 1440)), days.days.getValue(today).getValue("1.2").slots)
        val s = days.toSchedule(OutageSourceKind.ZAPORIZHZHIA, "1.1", today)
        assertEquals(listOf(at(10) to at(15), at(19) to at(0, day = 11)), off(s))
    }

    @Test
    fun poltavaCellsMatchThePagePeriodList() {
        val days = OblenergoParsers.poltava(Fixtures.text("oblenergo_poltava.html"), today)
        assertEquals(queues, days.groups)
        val s = days.toSchedule(OutageSourceKind.POLTAVA, "1.1", today)
        // page text: «1.1 — 04:00–07:30, 10:00–14:00, 16:00–20:00, 22:00–24:00»
        assertEquals(listOf(at(4) to at(7, 30), at(10) to at(14), at(16) to at(20), at(22) to at(0, day = 11)), off(s))
        assertEquals(at(0) to at(0, 30), s.windows.first { it.maybe }.let { it.start to it.end })
    }

    @Test
    fun chernivtsiNumberedGroupsAndUpdatedAt() {
        val days = OblenergoParsers.chernivtsi(listOf(Fixtures.text("oblenergo_chernivtsi.html")))
        assertEquals((1..12).map(Int::toString), days.groups)
        assertEquals("2026-11-10T15:30:00+02:00", days.updatedAt)
        val s = days.toSchedule(OutageSourceKind.CHERNIVTSI, "1", today)
        assertEquals(listOf(at(7) to at(9), at(13) to at(15), at(17) to at(20)), off(s))
        assertEquals(3, s.windows.count { it.maybe })
        try {
            days.toSchedule(OutageSourceKind.CHERNIVTSI, "1.1", today)
            fail("queue 1.1 does not exist in Chernivtsi")
        } catch (_: OutageSourceException) {
        }
    }

    @Test
    fun chernivtsiAllOnMeansNoOutages() {
        val page = Fixtures.text("oblenergo_chernivtsi.html").replace("<o>в</o>", "<u>з</u>").replace("<s>мз</s>", "<u>з</u>")
        val s = OblenergoParsers.chernivtsi(listOf(page)).toSchedule(OutageSourceKind.CHERNIVTSI, "1", today)
        assertEquals(listOf(today), s.publishedDates)
        assertTrue(s.windows.isEmpty())
    }

    @Test(expected = OutageSourceException::class)
    fun zaporizhzhiaPageWithoutPostsIsAnError() {
        OblenergoParsers.zaporizhzhia("<html></html>", today)
    }
}
