package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class YasnoParserTest {
    @Test
    fun definiteSlotsBecomeWindowsInKyivTime() {
        val schedule = YasnoParser.parse(Fixtures.text("yasno_3_301.json"), "1.1")
        assertEquals(OutageSourceKind.YASNO, schedule.source)
        assertFalse(schedule.emergency)
        assertEquals(listOf(LocalDate.of(2026, 11, 10)), schedule.publishedDates) // tomorrow WaitingForSchedule
        assertEquals(2, schedule.windows.size)
        assertEquals("2026-11-10T04:30+02:00[Europe/Kyiv]", schedule.windows[0].start.toString())
        assertEquals("2026-11-10T08:30+02:00[Europe/Kyiv]", schedule.windows[0].end.toString())
        assertEquals("2026-11-10T15:00+02:00[Europe/Kyiv]", schedule.windows[1].start.toString())
        assertEquals("2026-11-10T22:30+02:00[Europe/Kyiv]", schedule.windows[1].end.toString())
        assertEquals("2026-11-10T04:23:07+00:00", schedule.updatedAt)
    }

    @Test
    fun emergencyShutdownsAreFlaggedWithoutWindows() {
        val schedule = YasnoParser.parse(Fixtures.text("yasno_25_902.json"), "2.1")
        assertTrue(schedule.emergency)
        assertTrue(schedule.windows.isEmpty())
        assertTrue(schedule.publishedDates.isEmpty())
        assertFalse(schedule.isEmpty)
    }

    @Test
    fun unknownQueueIsEmpty() {
        assertTrue(YasnoParser.parse(Fixtures.text("yasno_3_301.json"), "99.9").isEmpty)
    }

    @Test(expected = Exception::class)
    fun malformedPayloadThrows() {
        YasnoParser.parse("[1,2,3]", "1.1")
    }

    @Test
    fun officialSourceMapping() {
        assertEquals(OfficialSource.Loe, OfficialSources.forRegion("lviv"))
        assertEquals(OfficialSource.Yasno(25, 902), OfficialSources.forRegion("kyiv"))
        assertEquals(OfficialSource.Yasno(3, 301), OfficialSources.forRegion("dnipro"))
        assertEquals(OfficialSource.Yasno(3, 303), OfficialSources.forRegion("dnipro-cek"))
        assertEquals(null, OfficialSources.forRegion("kyiv-region")) // DTEK site behind bot protection: aggregators only
        assertEquals(null, OfficialSources.forRegion("odesa"))
        assertEquals(
            "https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/25/dsos/902/planned-outages",
            OfficialSource.Yasno(25, 902).url,
        )
    }
}
