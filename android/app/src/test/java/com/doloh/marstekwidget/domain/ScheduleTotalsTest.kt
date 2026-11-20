package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as server/tests/test_schedule_totals.py (web `scheduleTotals`). */
class ScheduleTotalsTest {
    private val h = 3600_000L
    private fun t(c: List<Triple<Long, Long, Boolean>>, day: Long) = ScheduleDays.totals(c, day)?.replace('\u00A0', ' ')
    private fun w(a: Double, b: Double, maybe: Boolean = false) = Triple((a * h).toLong(), (b * h).toLong(), maybe)

    @Test fun screenshotDay() =
        assertEquals("Без світла 6 год 30 хв · зі світлом 17 год 30 хв", t(listOf(w(10.5, 14.0), w(21.0, 24.0)), 24 * h))

    @Test fun threeWindows() =
        assertEquals("Без світла 9 год 30 хв · зі світлом 14 год 30 хв",
            t(listOf(w(4.0, 7.5), w(14.5, 18.0), w(21.5, 24.0)), 24 * h))

    @Test fun overlappingWindowsAreMerged() =
        assertEquals("Без світла 6 год · зі світлом 18 год", t(listOf(w(8.0, 12.0), w(10.0, 14.0)), 24 * h))

    @Test fun maybeCountedOutsideDefiniteOnly() =
        assertEquals("Без світла 4 год · зі світлом 18 год · ще можливе 2 год",
            t(listOf(w(8.0, 12.0), w(11.0, 14.0, true)), 24 * h))

    @Test fun onlyMaybe() =
        assertEquals("Без світла 0 хв · зі світлом 22 год · ще можливе 2 год", t(listOf(w(18.0, 20.0, true)), 24 * h))

    @Test fun noWindowsIsNull() = assertNull(ScheduleDays.totals(emptyList(), 24 * h))

    @Test fun dstDayOf23Hours() =
        assertEquals("Без світла 4 год · зі світлом 19 год", t(listOf(w(0.0, 4.0)), 23 * h))
}
