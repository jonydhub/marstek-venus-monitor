package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * UPS bypass — mirror of server/tests/test_bypass_load.py (keep in sync). Some firmwares report the backup-port load
 * while the grid feeds it; our fw148 unit reads 0 W there and, in a real outage, ongrid 0 with bat_wh falling in 5 Wh
 * steps — that must still go OFF at once in auto mode. 37 s polls.
 */
class GridBypassTest {
    private val t0 = 1_763_000_000.0
    private val poll = 37.0

    private data class P(val ts: Double, val off: Double?, val on: Double?, val wh: Double?)
    private data class E(val ts: Double, val t: GridTracker, val s: GridSignal)

    private fun q(wh: Double) = 5.0 * (wh / 5.0).roundToInt()

    private fun draw(start: Double, minutes: Double, watts: Double, ongrid: Double = 0.0, wh0: Double = 4000.0, drains: Boolean = true): List<P> {
        val count = (minutes * 60 / poll).toInt() + 1
        return (0 until count).map { i -> P(start + i * poll, watts, ongrid, q(wh0 - if (drains) watts * i * poll / 3600.0 else 0.0)) }
    }

    private fun idle(start: Double, minutes: Double, wh: Double = 4000.0, ongrid: Double = 0.0): List<P> =
        (0 until (minutes * 60 / poll).toInt() + 1).map { i -> P(start + i * poll, 0.0, ongrid, wh) }

    private fun run(points: List<P>, start: GridTracker? = null, mode: String = GridBypass.AUTO, gap: Double = GridHysteresis.DEFAULT_CONFIRM_GAP_SECONDS): Pair<GridTracker, List<E>> {
        var t = start
        val log = points.map { p ->
            val (nt, s) = GridHysteresis.step(t, p.ts, p.off, p.on, 5.0, gap, p.wh, mode)
            t = nt
            E(p.ts, nt, s)
        }
        return t!! to log
    }

    private fun gp(points: List<P>) = points.map { GridPoint(it.ts, it.off, it.on, it.wh) }

    @Test
    fun gridImportBeatsBackupDrawAndIsLearned() {
        val (t, log) = run(draw(t0, 3.0, 200.0, ongrid = -230.0, drains = false))
        assertEquals("on", log[0].t.state)
        assertEquals("grid_charging", log[0].t.confirmedBy)
        assertTrue(log.all { it.t.state == "on" })
        assertTrue(t.bypassLoadSeen)
        assertTrue(t.bypassActive)
        assertFalse(log.any { it.s.lost })
    }

    @Test
    fun gridImportReturnsTheLightDuringADraw() {
        val (off, _) = run(draw(t0, 2.0, 300.0))
        assertEquals("off", off.state)
        val (_, log) = run(draw(t0 + 200, 1.0, 300.0, ongrid = -400.0, wh0 = 3900.0, drains = false), off)
        assertEquals("on", log[0].t.state)
        assertEquals(t0 + 200, log[0].s.returnedAt!!, 0.0)
    }

    @Test
    fun flatBatteryWhileBackupDrawsIsBypassAfter30Wh() {
        val (t, log) = run(draw(t0, 12.0, 200.0, drains = false))
        assertEquals("off", log[0].t.state)
        val back = log.first { it.s.returnedAt != null }
        assertTrue(back.ts - t0 >= 9 * 60 - poll)
        assertTrue(back.ts - t0 < 10 * 60)
        assertEquals(t0, back.s.returnedAt!!, 0.0)
        assertEquals("on", t.state)
        assertEquals("bypass_no_discharge", t.confirmedBy)
        assertTrue(t.bypassLoadSeen)
    }

    @Test
    fun bypassTimelineIsBackDatedToOn() {
        val points = idle(t0 - 600, 9.0) + draw(t0, 12.0, 200.0, drains = false)
        val spans = GridHysteresis.timeline(gp(points), t0, points.last().ts)
        assertEquals(listOf("on"), spans.map { it.state })
    }

    @Test
    fun realOutageOngridZeroAndFallingBatteryStaysOff() {
        val (t, log) = run(idle(t0 - 400, 6.0) + draw(t0, 30.0, 290.0, wh0 = 3000.0))
        val first = log.first { it.ts == t0 }
        assertTrue(first.s.lost)
        val after = log.filter { it.ts >= t0 }
        assertTrue(after.all { it.t.state == "off" })
        assertFalse(after.any { it.s.returnedAt != null })
        assertFalse(t.bypassLoadSeen)
        assertEquals("backup_draw", t.lostBy)
    }

    @Test
    fun lateQuantizedStepsDoNotFakeABypass() {
        val points = draw(t0, 30.0, 160.0, wh0 = 5120.0).mapIndexed { i, p -> if (i < 3) p.copy(wh = p.wh!! + 5.0) else p }
        val (_, log) = run(points)
        assertTrue(log.all { it.t.state == "off" })
    }

    @Test
    fun failedPollRestartsTheEnergyWindow() {
        val points = draw(t0, 6.0, 200.0, drains = false) + P(t0 + 380, null, null, null) + draw(t0 + 400, 6.0, 200.0, drains = false)
        val (_, log) = run(points)
        assertTrue(log.all { it.t.state == "off" })
    }

    @Test
    fun missingBatWhKeepsTheLegacyRule() {
        val (_, log) = run(draw(t0, 20.0, 200.0, drains = false).map { it.copy(wh = null) })
        assertTrue(log.all { it.t.state == "off" })
    }

    @Test
    fun longGapRestartsTheEnergyWindow() {
        val a = draw(t0, 6.0, 200.0, drains = false)
        val b = draw(a.last().ts + 600, 6.0, 200.0, drains = false)
        val (_, log) = run(a + b, gap = 120.0)
        assertTrue(log.all { it.t.state == "off" })
    }

    private fun learnedOn(): GridTracker {
        val (t, _) = run(idle(t0 - 400, 6.0, ongrid = -60.0))
        assertEquals("on", t.state)
        return t.copy(bypassLoadSeen = true)
    }

    @Test
    fun learnedNewDrawIsPendingWhileTheBatteryIsFlat() {
        val (_, log) = run(draw(t0, 8.0, 200.0, drains = false), learnedOn())
        assertTrue(log.all { it.t.state == "on" })
        assertTrue(log[0].s.pendingLoss)
        assertFalse(log.any { it.s.lost })
    }

    @Test
    fun learnedFlatDrawBecomesBypassThenNoLongerPending() {
        val (t, log) = run(draw(t0, 15.0, 200.0, drains = false), learnedOn())
        assertTrue(t.bypassActive)
        assertFalse(log.last().s.pendingLoss)
        assertEquals("on", t.state)
    }

    @Test
    fun learnedNewDrawGoesOffOnceTheBatteryVisiblyDischarges() {
        val (t, log) = run(draw(t0, 10.0, 290.0, wh0 = 3000.0), learnedOn())
        val lost = log.first { it.s.lost }
        assertTrue(lost.ts - t0 <= 3 * 60)
        assertEquals(t0, lost.s.lostAt!!, 0.0)
        assertEquals("off", t.state)
        assertEquals("battery_discharge", t.lostBy)
        assertEquals(t0, t.offSince!!, 0.0)
        assertTrue(log.filter { it.ts < lost.ts }.all { it.t.state == "on" })
    }

    @Test
    fun learnedOutageDuringALongBypassDrawIsDetected() {
        val bypass = draw(t0, 60.0, 200.0, drains = false)
        val outage = draw(bypass.last().ts + poll, 15.0, 200.0, wh0 = 4000.0)
        val (t, log) = run(bypass + outage, learnedOn())
        val lost = log.first { it.s.lost }
        assertTrue(lost.ts > bypass.last().ts)
        assertTrue(lost.ts - bypass.last().ts < 8 * 60)
        assertEquals("off", t.state)
    }

    @Test
    fun learnedFlagFromReplaySeed() {
        val points = gp(idle(t0 - 400, 6.0, ongrid = -60.0) + draw(t0, 4.0, 200.0, drains = false))
        assertEquals("off", GridHysteresis.replay(points).first.state)
        assertEquals("on", GridHysteresis.replay(points, bypass = GridBypass(seen = true)).first.state)
        assertEquals("on", GridHysteresis.replay(points, bypass = GridBypass(GridBypass.YES)).first.state)
        assertEquals("off", GridHysteresis.replay(points, bypass = GridBypass(GridBypass.NO, seen = true)).first.state)
    }

    @Test
    fun learnedTimelineBackDatesTheLoss() {
        val points = idle(t0 - 400, 6.0, ongrid = -60.0) + draw(t0, 10.0, 290.0, wh0 = 3000.0)
        val spans = GridHysteresis.timeline(gp(points), t0 - 400, points.last().ts, bypass = GridBypass(seen = true))
        assertEquals(t0, spans.first { it.state == "off" }.start, 0.0)
    }

    @Test
    fun noKeepsLegacyImmediateLossAndNeverLearns() {
        val (t, log) = run(draw(t0, 15.0, 200.0, ongrid = -230.0, drains = false), mode = GridBypass.NO)
        assertTrue(log[0].s.lost)
        assertTrue(log.all { it.t.state == "off" })
        assertFalse(t.bypassLoadSeen)
    }

    @Test
    fun yesRequiresBatteryConfirmationWithoutLearning() {
        val (on, _) = run(idle(t0 - 400, 6.0, ongrid = -60.0))
        val (_, log) = run(draw(t0, 5.0, 200.0, drains = false), on, mode = GridBypass.YES)
        assertTrue(log.all { it.t.state == "on" })
        assertTrue(log[0].s.pendingLoss)
    }

    @Test
    fun modeNormalization() {
        assertEquals("auto", GridBypass.normalize(null))
        assertEquals("yes", GridBypass.normalize("YES"))
        assertEquals("no", GridBypass.normalize("off"))
        assertEquals("auto", GridBypass.normalize("maybe"))
    }

    @Test
    fun historyOutagePeriodsIgnoreABypassDraw() {
        val points = idle(t0 - 600, 9.0) + draw(t0, 30.0, 200.0, drains = false)
        val rows = points.map { HistorySample(it.ts, 100, it.wh, null, null, it.off, it.on, 20.0) }
        assertEquals(emptyList<Pair<Long, Long>>(), HistoryMath.outagePeriods(rows, points.last().ts))
        assertEquals(1, HistoryMath.outagePeriods(rows, points.last().ts, bypass = GridBypass(GridBypass.NO)).size)
    }
}
