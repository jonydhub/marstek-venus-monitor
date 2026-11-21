package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Same cases as server tests/test_stats.py (outage_details / charge_sessions / stats). */
class HistoryStatsTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private fun ts(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, kyiv).toEpochSecond().toDouble()

    /** Grid present, no load. */
    private fun idle(from: Double, seconds: Int, step: Int = 15, soc: Int = 99) =
        (0 until seconds step step).map { HistorySample(from + it, soc, 5000.0, null, "idle", 0.0, 0.0, 20.0) }

    /** Outage: backup load [watts], bat_wh drains in 5 Wh steps (a flat bat_wh would read as bypass). */
    private fun drain(from: Double, seconds: Int, step: Int, watts: Double, soc: (Int) -> Int = { 99 }) =
        (0 until seconds step step).mapIndexed { i, dt ->
            HistorySample(from + dt, soc(i), 5.0 * Math.round((4000.0 - watts * dt / 3600.0) / 5.0), -watts, "discharging", watts, 0.0, 20.0)
        }

    private fun charging(from: Double, seconds: Int, step: Int, watts: Double, socFrom: Int, socTo: Int): List<HistorySample> {
        val n = maxOf(1, seconds / step)
        return (0 until n).map { i ->
            HistorySample(from + i * step, Math.round(socFrom + (socTo - socFrom) * i.toDouble() / n).toInt(), 4000.0 + i, watts,
                "charging", 0.0, -watts, 20.0)
        }
    }

    @Test
    fun outageHasEnergyAverageAndSoc() {
        val rows = idle(ts(21, 15), 3600) + drain(ts(21, 16), 1800, 15, 600.0) { 99 - it / 40 } + idle(ts(21, 16, 30), 1800, soc = 92)
        val now = ts(21, 17)
        val timeline = HistoryMath.gridTimeline(rows, ts(21, 0), now)
        val out = HistoryMath.outageDetails(rows, timeline, now)
        assertEquals(1, out.size)
        val o = out[0]
        assertEquals(ts(21, 16) * 1000, o.startMillis.toDouble(), 60_000.0)
        assertEquals(1800.0, o.seconds.toDouble(), 30.0)
        assertEquals(300.0, o.energyWh!!, 5.0)   // 600 W × 0.5 h
        assertEquals(600.0, o.avgW!!.toDouble(), 10.0)
        assertEquals(99, o.socStart)
        assertFalse(o.ongoing)
    }

    @Test
    fun todayJsonListsOngoingOutageWithEnergy() {
        val rows = drain(ts(21, 1), 1800, 30, 200.0)
        val now = ts(21, 1, 30)
        val today = HistoryMath.insights(rows, now, 200.0, 20.0).getJSONObject("today")
        val outages = today.getJSONArray("outages")
        assertEquals(1, outages.length())
        assertTrue(outages.getJSONObject(0).getBoolean("ongoing"))
        assertEquals(100.0, today.getDouble("outage_energy_wh"), 5.0)  // 200 W × 0.5 h so far
    }

    @Test
    fun rechargeIsOneSessionWithEnergyAndSoc() {
        val rows = charging(ts(21, 16, 35), 1560, 15, 1100.0, 92, 100) + idle(ts(21, 17, 1), 600, soc = 100)
        val out = HistoryMath.chargeSessions(rows, ts(21, 0), ts(21, 17, 20))
        assertEquals(1, out.size)
        val c = out[0]
        assertEquals(1100.0 * 1560 / 3600, c.energyWh!!, 10.0)  // ~477 Wh
        assertEquals(92 to 100, c.socStart to c.socEnd)
        assertFalse(c.estimated)
    }

    @Test
    fun standbyTopUpIsLeftOut() {
        val rows = charging(ts(21, 10), 360, 15, 300.0, 99, 100)  // 30 Wh, +1 %
        assertTrue(HistoryMath.chargeSessions(rows, ts(21, 0), ts(21, 11)).isEmpty())
    }

    @Test
    fun smallChargeNeedsThreePercentRise() {
        // 30 Wh: +2 % is still a top-up, +3 % counts (CHARGE_MIN_SOC = 3, same as the server)
        val two = charging(ts(21, 10), 360, 15, 300.0, 96, 98) + idle(ts(21, 10, 6), 60, soc = 98)
        assertTrue(HistoryMath.chargeSessions(two, ts(21, 0), ts(21, 11)).isEmpty())
        val three = charging(ts(21, 10), 360, 15, 300.0, 95, 98) + idle(ts(21, 10, 6), 60, soc = 98)
        assertEquals(1, HistoryMath.chargeSessions(three, ts(21, 0), ts(21, 11)).size)
    }

    @Test
    fun runsWithAShortPauseMerge() {
        val rows = charging(ts(21, 10), 600, 15, 1000.0, 80, 85) + idle(ts(21, 10, 10), 120, soc = 85) +
            charging(ts(21, 10, 12), 600, 15, 1000.0, 85, 90)
        val out = HistoryMath.chargeSessions(rows, ts(21, 0), ts(21, 11))
        assertEquals(1, out.size)
        assertEquals(80 to 90, out[0].socStart to out[0].socEnd)
    }

    @Test
    fun perDayTotalsOutagesAndChargesNewestFirst() {
        val rows = drain(ts(20, 10), 3600, 30, 150.0) + charging(ts(20, 11), 1800, 30, 1000.0, 90, 100) +
            drain(ts(21, 8), 1800, 30, 300.0) + idle(ts(21, 8, 30), 1800, 30)
        val st = HistoryMath.stats(rows, ts(21, 12))
        assertEquals(listOf(LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 20)), st.days.map { it.date })
        assertTrue(st.days[0].isToday)
        assertEquals(1, st.days[1].outages)
        assertEquals(150.0, st.days[1].outageEnergyWh, 5.0)
        assertEquals(150.0, st.days[0].outageEnergyWh, 5.0)
        assertTrue(st.days[1].chargeWh > 400)
        assertEquals(ts(21, 8) * 1000, st.outages[0].startMillis.toDouble(), 60_000.0)
        assertEquals(1, st.charges.size)
    }
}
