package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UaOutagesParserTest {
    private val raw = Fixtures.text("uao_now.json")

    @Test
    fun offAndMaybeIntervalsAreKeptDistinct() {
        val schedule = UaOutagesParser.parse(raw, "mykolaiv", "1.1")
        assertEquals(OutageSourceKind.UA_OUTAGES, schedule.source)
        assertEquals(listOf(LocalDate.of(2026, 11, 10)), schedule.publishedDates)
        val definite = schedule.windows.filter { !it.maybe }
        val maybe = schedule.windows.filter { it.maybe }
        assertEquals(1, definite.size)
        assertEquals("2026-11-10T15:00+02:00[Europe/Kyiv]", definite[0].start.toString())
        assertEquals("2026-11-10T21:00+02:00[Europe/Kyiv]", definite[0].end.toString())
        assertEquals(2, maybe.size)
        assertEquals("2026-11-10T14:30+02:00[Europe/Kyiv]", maybe[0].start.toString())
    }

    @Test
    fun emergencyStatusIsFlagged() {
        val schedule = UaOutagesParser.parse(raw, "kyiv", "1.1")
        assertTrue(schedule.emergency)
        assertTrue(schedule.windows.isEmpty())
    }

    @Test
    fun unknownOnlyOrNoGroupsIsEmpty() {
        assertTrue(UaOutagesParser.parse(raw, "rivne", "1.1").isEmpty)
        assertTrue(UaOutagesParser.parse(raw, "lviv", "1.1").isEmpty)
    }

    @Test(expected = OutageSourceException::class)
    fun failedRegionThrowsSoTheChainFallsBack() {
        UaOutagesParser.parse(raw, "kharkiv", "1.1")
    }

    @Test
    fun noOutagesStatusIsPublishedWithoutWindows() {
        val synthetic = """{"today":"2026-11-10","regions":[{"id":"sumy","ok":true,"groups":["1.1"],
            "days":{"2026-11-10":{"1.1":{"s":"n","i":[]}}}}]}"""
        val schedule = UaOutagesParser.parse(synthetic, "sumy", "1.1")
        assertFalse(schedule.isEmpty)
        assertTrue(schedule.windows.isEmpty())
    }

    @Test
    fun listsGroupsForQueuePicker() {
        assertEquals(60, UaOutagesParser.groups(raw, "kyiv").size)
        assertEquals(emptyList<String>(), UaOutagesParser.groups(raw, "lviv"))
    }
}
