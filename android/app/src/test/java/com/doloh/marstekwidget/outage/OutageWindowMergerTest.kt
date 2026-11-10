package com.doloh.marstekwidget.outage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class OutageWindowMergerTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private fun at(h: Int, m: Int = 0, day: Int = 10) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, kyiv)

    @Test
    fun mergesTouchingAndOverlappingWindows() {
        val merged = OutageWindowMerger.merge(listOf(
            OutageWindow(at(10), at(12)), OutageWindow(at(2), at(3)), OutageWindow(at(12), at(13)), OutageWindow(at(12, 30), at(14)),
        ))
        assertEquals(listOf(OutageWindow(at(2), at(3)), OutageWindow(at(10), at(14))), merged)
    }

    @Test
    fun halfHourSlotsBecomeMergedWindows() {
        val slots = (0 until 48).associate { i ->
            val label = "%02d:%02d".format(i / 2, (i % 2) * 30)
            label to when (i) { in 4..17 -> 2; 47 -> 2; else -> 1 }
        }
        val windows = HalfHourSlots.toWindows(LocalDate.of(2026, 11, 10), slots)
        assertEquals(listOf(OutageWindow(at(2), at(9)), OutageWindow(at(23, 30), at(0, 0, day = 11))), windows)
    }

    @Test
    fun windowsAcrossMidnightMergeWhenBothDaysAreGiven() {
        val today = HalfHourSlots.toWindows(LocalDate.of(2026, 11, 10), mapOf("23:30" to 2))
        val tomorrow = HalfHourSlots.toWindows(LocalDate.of(2026, 11, 11), mapOf("00:00" to 2, "00:30" to 2))
        assertEquals(listOf(OutageWindow(at(23, 30), at(1, 0, day = 11))), OutageWindowMerger.merge(today + tomorrow))
    }

    @Test
    fun definiteAndMaybeWindowsDoNotMergeIntoEachOther() {
        val merged = OutageWindowMerger.merge(listOf(
            OutageWindow(at(14, 30), at(15), maybe = true), OutageWindow(at(15), at(21)), OutageWindow(at(21), at(21, 30), maybe = true),
        ))
        assertEquals(3, merged.size)
        assertEquals(listOf(true, false, true), merged.map { it.maybe })
    }

    @Test
    fun slotScoring() {
        assertEquals(0, HalfHourSlots.score(null))
        assertEquals(1, HalfHourSlots.score(mapOf("00:00" to 0)))
        assertEquals(2, HalfHourSlots.score(mapOf("00:00" to 0, "00:30" to 1)))
    }
}
