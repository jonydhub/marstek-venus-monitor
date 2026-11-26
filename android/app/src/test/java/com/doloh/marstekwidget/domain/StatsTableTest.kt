package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/** Same cases as server/tests/test_stats_table.py (web `statsDayCells` / `statsGroups`). */
class StatsTableTest {
    private val nb = '\u00A0'
    private val today = LocalDate.of(2026, 11, 26)
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    private fun clean(cells: List<String>) = cells.map { it.replace(nb, ' ') }

    @Test
    fun dayLines() {
        val days = listOf(
            StatsDay(today, true, 4560, 2, 720.4, 840.0, 0.0),
            StatsDay(today.minusDays(3), false, 0, 0, 0.0, 966.5, 0.0),
            StatsDay(today.minusDays(4), false, 0, 0, 0.0, 0.0, 0.0),  // no data → skipped
            StatsDay(today.minusDays(6), false, 25860, 3, 1220.0, 1631.7, 900.0),
        )
        val lines = StatsTable.dayLines(days, today)
        assertEquals(3, lines.size)
        assertEquals(listOf("Сьогодні", "1 год 16 хв", "0,72", "0,84"), clean(lines[0].cells))
        assertTrue(lines[0].highlight)
        assertEquals(listOf("Пн, 23.11", "—", "—", "0,97"), clean(lines[1].cells))
        assertFalse(lines[1].highlight)
        assertEquals(listOf("Пт, 20.11", "7 год 11 хв", "1,22", "1,63"), clean(lines[2].cells))
    }

    @Test
    fun outagesGroupedByDay() {
        fun o(a: String, b: String, s: Long, wh: Double?, s1: Int?, s2: Int?, on: Boolean = false) =
            OutageDetail(ms(a), ms(b), s, wh, 100, s1, s2, on)
        val groups = StatsTable.outageGroups(listOf(
            o("2026-11-26T17:06:00+02:00", "2026-11-26T18:05:00+02:00", 3540, 560.0, 99, 87, on = true),
            o("2026-11-26T09:23:00+02:00", "2026-11-26T09:40:00+02:00", 1020, 170.0, 100, 97),
            o("2026-11-25T22:08:00+02:00", "2026-11-25T22:11:00+02:00", 180, 2.0, null, 97),
            o("2026-11-20T21:10:00+02:00", "2026-11-21T00:15:00+02:00", 11100, 590.0, 100, 86),
        ), today)
        assertEquals(listOf("Сьогодні", "Вчора", "Пт, 20.11"), groups.map { it.title })
        assertEquals(listOf("з 17:06", "59 хв", "0,56", "99→87"), clean(groups[0].lines[0].cells))
        assertTrue(groups[0].lines[0].highlight)
        assertEquals(listOf("09:23–09:40", "17 хв", "0,17", "100→97"), clean(groups[0].lines[1].cells))
        assertEquals(listOf("22:08–22:11", "3 хв", "—", "—"), clean(groups[1].lines[0].cells))
        assertEquals(listOf("21:10–00:15", "3 год 5 хв", "0,59", "100→86"), clean(groups[2].lines[0].cells))
    }

    @Test
    fun charges() {
        val groups = StatsTable.chargeGroups(listOf(
            ChargeSession(ms("2026-11-26T09:40:00+02:00"), ms("2026-11-26T09:53:00+02:00"), 780, 230.0, 1011, 97, 100, false, false),
            ChargeSession(ms("2026-11-24T10:42:00+02:00"), ms("2026-11-24T11:21:00+02:00"), 2340, 750.0, null, null, null, true, false),
        ), today)
        assertEquals(listOf("Сьогодні", "Вт, 24.11"), groups.map { it.title })
        assertEquals(listOf("09:40–09:53", "13 хв", "+0,23", "97→100"), clean(groups[0].lines[0].cells))
        assertEquals(listOf("10:42–11:21", "39 хв", "≈ +0,75", "—"), clean(groups[1].lines[0].cells))
    }
}
