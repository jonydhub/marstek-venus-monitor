package com.doloh.marstekwidget.domain

import com.doloh.marstekwidget.direct.BatteryFlow
import com.doloh.marstekwidget.direct.BatteryFlowCalculator
import com.doloh.marstekwidget.direct.CapacitySample
import com.doloh.marstekwidget.direct.HistoryMath
import com.doloh.marstekwidget.direct.HistorySample
import com.doloh.marstekwidget.testutil.Fixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression on real Venus E history (fw148, Manual, grid present, 100 %) — same fixture as the server test.
 * 1.3.1 turned each 1 % SOC step into a fake −335…−711 W discharge and a «конфлікт з ДБЖ» anomaly; in reality
 * the battery self-drains one step and tops up from the grid (ongrid_power ≈ −1195 W = charging).
 */
class StandbyTopUpRegressionTest {
    private val fixture = JSONObject(Fixtures.text("standby_topup_fw148.json"))
    private val rated = fixture.getDouble("rated_wh")
    private val base = 1_700_000_000.0

    private data class Row(val clock: String, val t: Double, val soc: Int, val wh: Double, val offgrid: Double, val ongrid: Double)

    private val rows = fixture.getJSONArray("samples").let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Row(it.getString("clock"), base + it.getDouble("t"), it.getInt("soc"), it.getDouble("bat_wh"),
                it.getDouble("offgrid_w"), it.getDouble("ongrid_w"))
        }
    }

    private fun replay(): Pair<List<Pair<String, BatteryFlow>>, List<JournalEvent>> {
        var window = listOf<CapacitySample>()
        var memory: DetectorMemory? = null
        val flows = mutableListOf<Pair<String, BatteryFlow>>()
        val journal = mutableListOf<JournalEvent>()
        rows.forEach { r ->
            window = (window + CapacitySample(r.t, r.wh)).filter { r.t - it.t <= BatteryFlowCalculator.TREND_WINDOW_SECONDS }
            val flow = BatteryFlowCalculator.calculate(window, r.wh, rated, ongridW = r.ongrid, offgridW = r.offgrid)
            flows += r.clock to flow
            val obs = JournalObservation(
                ok = true, gridState = if (abs(r.offgrid) >= 5) GridState.OFF else GridState.ON,
                direction = BatteryDirection.fromApi(flow.direction), batteryWatts = flow.powerW.takeIf { flow.available },
                batteryMeasured = !flow.estimated, backupWatts = r.offgrid, soc = r.soc, temperatureC = 25.0,
            )
            val (events, m) = JournalDetector.step(memory, obs, (r.t * 1000).toLong())
            journal += events
            memory = m
        }
        return flows to journal
    }

    @Test
    fun noFalseDischargeAnywhere() {
        assertEquals(emptyList<String>(), replay().first.filter { it.second.direction == "discharging" }.map { it.first })
    }

    @Test
    fun topUpIsMeasuredChargingFromTheGrid() {
        val flows = replay().first.toMap()
        listOf("07:53:37" to 1195.0, "08:33:10" to 1195.0, "09:11:13" to 637.0).forEach { (clock, watts) ->
            val f = flows.getValue(clock)
            assertEquals(clock, "charging", f.direction)
            assertFalse(clock, f.estimated)
            assertEquals(clock, watts, f.powerW!!, 0.1)
        }
    }

    @Test
    fun singleSocStepIsIdle() {
        val flows = replay().first.toMap()
        listOf("07:54:16", "07:56:09", "07:58:03", "08:33:47", "08:36:55", "09:12:27", "09:14:20").forEach {
            assertEquals(it, "idle", flows.getValue(it).direction)
        }
    }

    @Test
    fun journalHasNoAnomalyAndAtMostOneMaintenanceRecord() {
        val kinds = replay().second.map { it.kind }
        assertTrue(kinds.toString(), kinds.all { it == "maintenance_topup" })
        assertTrue(kinds.count { it == "maintenance_topup" } <= 1)
        assertFalse(replay().second.any { "оцінка" in it.text || "конфлікт" in it.text })
    }

    @Test
    fun standbySelfConsumptionFromTopUpCadence() {
        val samples = rows.map { HistorySample(it.t, it.soc, it.wh, null, null, it.offgrid, it.ongrid, null) }
        val watts = HistoryMath.standbyConsumption(samples, rows.last().t + 60)
        assertNotNull(watts)
        assertTrue(watts.toString(), watts!! in 70..95)
        assertEquals(null, HistoryMath.standbyConsumption(samples.take(3), rows[2].t))
    }

    @Test
    fun ongridSignConvention() {
        assertEquals(800.0, BatteryFlowCalculator.measuredPower(-800.0, 0.0)!!, 0.0)
        assertEquals(-450.0, BatteryFlowCalculator.measuredPower(450.0, 0.0)!!, 0.0)
        assertEquals(-300.0, BatteryFlowCalculator.measuredPower(0.0, 300.0)!!, 0.0)
        assertEquals(null, BatteryFlowCalculator.measuredPower(0.0, 0.0))
    }
}
