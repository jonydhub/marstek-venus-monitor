package com.doloh.marstekwidget.direct

import com.doloh.marstekwidget.domain.BatteryDirection
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.JournalDetector
import com.doloh.marstekwidget.domain.JournalEvent
import com.doloh.marstekwidget.domain.JournalObservation
import com.doloh.marstekwidget.domain.DetectorMemory
import com.doloh.marstekwidget.testutil.Fixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Light-return hysteresis + overload trip on the REAL fw148 UPS evening (fixtures/ups_ac_trip_fw148.json, same file as
 * the server test). Times are Europe/Kyiv; in CET the same evening is one hour earlier (21:46:45 Kyiv = 20:46:45 CET).
 */
class GridHysteresisRealSequenceTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    private data class Row(val local: String, val sample: HistorySample)

    private val rows: List<Row> = JSONObject(Fixtures.text("ups_ac_trip_fw148.json")).getJSONArray("samples").let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun d(k: String) = if (o.isNull(k)) null else o.getDouble(k)
            Row(o.getString("local_kyiv"), HistorySample(o.getDouble("ts"), o.getInt("soc"), d("bat_wh"), d("flow_w"),
                o.getString("flow_dir"), d("offgrid_w"), d("ongrid_w"), d("temp_c")))
        }
    }

    private fun hm(ts: Double) = Instant.ofEpochMilli((ts * 1000).toLong()).atZone(kyiv).format(clock)

    private fun run(): List<Triple<Row, GridTracker, GridSignal>> {
        var tracker: GridTracker? = null
        return rows.map { r ->
            val (t, s) = GridHysteresis.step(tracker, r.sample.ts, r.sample.offgridW, r.sample.ongridW)
            tracker = t
            Triple(r, t, s)
        }
    }

    @Test
    fun oneOutageReturnedByChargingThenASecondOneWithoutFalseReturns() {
        val log = run()
        assertEquals(listOf("21:46:45", "21:51:55"), log.filter { it.third.lost }.map { it.first.local })
        assertEquals(listOf("21:51:18", "22:15:02"), log.mapNotNull { it.third.returnedAt }.map(::hm))
        for (t in listOf("21:48:40", "21:52:32", "21:55:03", "22:08:35", "22:10:57", "22:13:27")) {
            val e = log.single { it.first.local == t }
            assertEquals(t, "off", e.second.state)
            assertTrue(t, e.third.pending)
        }
        assertEquals("grid_charging", log.last().second.confirmedBy)
    }

    @Test
    fun overloadTripDetectedOnceAndBlocksTheIdleRule() {
        val log = run()
        assertEquals(listOf("22:08:35" to 779.0), log.filter { it.third.tripW != null }.map { it.first.local to it.third.tripW })
        assertTrue(log.single { it.first.local == "22:13:27" }.second.tripped)
    }

    @Test
    fun todayTimelineMatchesTheServer() {
        val midnight = ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, kyiv).toEpochSecond().toDouble()
        val now = rows.single { it.local == "22:16:16" }.sample.ts + 10
        val spans = GridHysteresis.timeline(HistoryMath.gridPoints(rows.map { it.sample }), midnight, now)
        val view = spans.map { Triple(hm(it.start), hm(it.end), it.state) }
        assertEquals(Triple("00:00:00", "21:36:06", "unknown"), view.first())
        assertEquals(listOf(Triple("21:46:45", "21:51:18", "off"), Triple("21:51:18", "21:51:55", "on"),
            Triple("21:51:55", "22:15:02", "off"), Triple("22:15:02", hm(now), "on")), view.takeLast(4))
        spans.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, 1e-6); assertTrue(a.state != b.state) }
    }

    @Test
    fun directModeJournalHasNoFalseReturnsAndOneTripAnomaly() {
        var memory: DetectorMemory? = null
        val events = mutableListOf<JournalEvent>()
        var manual = false
        rows.forEachIndexed { i, r ->
            manual = manual || r.local == "22:10:57"
            val points = HistoryMath.gridPoints(rows.take(i + 1).map { it.sample })
            val (tracker, signal) = GridHysteresis.replay(points, confirmGap = HistoryMath.CONFIRM_GAP_SECONDS)
            val esOk = r.sample.offgridW != null
            val grid = if (!esOk) GridState.UNKNOWN else when (tracker.state) { "on" -> GridState.ON; "off" -> GridState.OFF; else -> GridState.UNKNOWN }
            val obs = JournalObservation(true, grid, BatteryDirection.fromApi(r.sample.flowDir), r.sample.flowW, false, r.sample.offgridW,
                r.sample.soc, r.sample.tempC, returnedAtMillis = signal.returnedAt?.let { (it * 1000).toLong() },
                returnBasis = tracker.confirmedBy.takeIf { signal.returnedAt != null }, overloadTripW = signal.tripW,
                mode = if (manual) "Manual" else "UPS")
            val (ev, m) = JournalDetector.step(memory, obs, (r.sample.ts * 1000).toLong())
            memory = m
            events += ev
        }
        fun at(kind: String) = events.filter { it.kind == kind }.map { hm(it.tsMillis / 1000.0) }
        assertEquals(listOf("21:46:45", "21:51:55"), at("light_lost"))
        assertEquals(listOf("21:51:18", "22:15:02"), at("light_returned"))
        assertTrue(events.first { it.kind == "light_returned" }.text.contains("після 5 хв (заряджання від мережі)"))
        val trip = events.single { it.kind == "anomaly_overload_trip" }
        assertEquals("22:08:35", hm(trip.tsMillis / 1000.0))
        assertEquals(JournalDetector.overloadTripText(779.0), trip.text)
        assertEquals(listOf("22:10:57"), at("mode_left_ups"))
        assertEquals("Станція вийшла з режиму UPS (зараз: Manual)", events.single { it.kind == "mode_left_ups" }.text)
    }

    @Test
    fun runtimeLoadIsTheTenMinuteEnergyWeightedMean() {
        val until = rows.indexOfFirst { it.local == "22:07:01" }
        val history = rows.take(until + 1).map { it.sample }
        val (tracker, _) = GridHysteresis.replay(HistoryMath.gridPoints(history))
        val mean = HistoryMath.smoothedBackupLoad(history, history.last().ts, tracker.offSince)!!
        assertTrue("$mean", mean in 300.0..400.0)  // not the last 779 W
        val (paused, _) = GridHysteresis.replay(HistoryMath.gridPoints(rows.take(rows.indexOfFirst { it.local == "21:55:03" } + 1).map { it.sample }))
        assertEquals("off", paused.state)
        assertNull(HistoryMath.smoothedBackupLoad(history, history.last().ts, null))
    }

    @Test
    fun todayStatsAndLastGridCharge() {
        val all = rows.map { it.sample }
        val now = all.last().ts
        val stats = HistoryMath.todayStats(all, now)
        val off = stats.timeline.filter { it.state == "off" }.map { hm(it.start) to hm(it.end) }
        assertEquals(listOf("21:46:45" to "21:51:18", "21:51:55" to "22:15:02"), off)
        assertTrue(stats.dischargeWh > 50)
        assertTrue(stats.chargeWh > 30)
        assertEquals(1195L, HistoryMath.lastGridCharge(all, now)!!.first)
        assertFalse(stats.outageSeconds < 1600)
    }
}
