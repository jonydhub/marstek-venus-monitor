package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryTodayTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val midnight = ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, kyiv).toEpochSecond().toDouble()
    private val now = midnight + 12 * 3600

    private fun span(from: Double, seconds: Int, dir: String?, offgrid: Double, step: Int = 60) =
        (0 until seconds step step).map { HistorySample(from + it, 80, 4000.0, null, dir, offgrid, 0.0, 24.0) }

    @Test
    fun countsChargeDischargeAndNoLightSinceMidnight() {
        val rows = span(midnight, 3600, "charging", 0.0) + span(midnight + 3600, 7200, "discharging", 121.0) +
            span(midnight + 10800, 1800, "idle", 0.0)
        val (charge, discharge, outage) = HistoryMath.todayDurations(rows, now)
        assertEquals(3600.0, charge.toDouble(), 61.0)
        assertEquals(7200.0, discharge.toDouble(), 61.0)
        assertEquals(7200.0, outage.toDouble(), 61.0)
    }

    @Test
    fun gapsCountAsNothing() {
        val rows = span(midnight + 3600, 600, "discharging", 100.0) + span(midnight + 5 * 3600, 600, "discharging", 100.0)
        val (_, discharge, outage) = HistoryMath.todayDurations(rows, now)
        assertTrue(discharge < 1200 && outage < 1200)
    }

    @Test
    fun intervalAcrossMidnightCountsOnlyTodaysPart() {
        val rows = listOf(HistorySample(midnight - 60, 80, 4000.0, null, "discharging", 50.0, 0.0, null),
            HistorySample(midnight + 60, 80, 4000.0, null, "discharging", 50.0, 0.0, null))
        assertEquals(Triple(0L, 60L, 60L), HistoryMath.todayDurations(rows, now))
    }

    @Test
    fun outagePeriodsAreMergedAndSplitByGaps() {
        val rows = span(now - 4 * 3600, 3600, null, 120.0) + span(now - 3 * 3600, 600, null, 0.0) + span(now - 3600, 600, null, 120.0)
        assertEquals(2, HistoryMath.outagePeriods(rows, now).size)
    }

    @Test
    fun insightsCarryTodayPeriodsAndChartEvents() {
        val json = HistoryMath.insights(span(now - 3600, 1800, "discharging", 80.0), now, 80.0, 20.0,
            chartEvents = listOf((now.toLong() - 1800) * 1000 to "light_lost"))
        assertTrue(json.getJSONObject("today").getLong("outage_seconds") > 1700)
        assertEquals(1, json.getJSONArray("outage_periods").length())
        assertEquals("light_lost", json.getJSONArray("chart_events").getJSONObject(0).getString("kind"))
    }
}
