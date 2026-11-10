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
    fun halfHourCellsBecomeMergedSlots() {
        val cells = (0 until 48).map { i -> when (i) { in 4..17 -> false; 18 -> true; 47 -> false; else -> null } }
        assertEquals(listOf(DaySlot(120, 540), DaySlot(540, 570, maybe = true), DaySlot(1410, 1440)), UaScheduleText.cells(cells))
    }

    @Test
    fun windowsAcrossMidnightMergeWhenBothDaysAreGiven() {
        val d10 = LocalDate.of(2026, 11, 10)
        val d11 = LocalDate.of(2026, 11, 11)
        val windows = listOf(OutageWindow(kyivAt(d10, 1410), kyivAt(d10, 1440)), OutageWindow(kyivAt(d11, 0), kyivAt(d11, 60)))
        assertEquals(listOf(OutageWindow(at(23, 30), at(1, 0, day = 11))), OutageWindowMerger.merge(windows))
    }

    @Test
    fun definiteAndMaybeWindowsDoNotMergeIntoEachOther() {
        val merged = OutageWindowMerger.merge(listOf(
            OutageWindow(at(14, 30), at(15), maybe = true), OutageWindow(at(15), at(21)), OutageWindow(at(21), at(21, 30), maybe = true),
        ))
        assertEquals(3, merged.size)
        assertEquals(listOf(true, false, true), merged.map { it.maybe })
    }
}
