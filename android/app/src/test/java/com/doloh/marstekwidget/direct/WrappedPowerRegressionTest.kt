package com.doloh.marstekwidget.direct

import com.doloh.marstekwidget.domain.BatteryDirection
import com.doloh.marstekwidget.domain.BatterySnapshotParser
import com.doloh.marstekwidget.domain.DetectorMemory
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.JournalDetector
import com.doloh.marstekwidget.domain.JournalEvent
import com.doloh.marstekwidget.domain.JournalObservation
import com.doloh.marstekwidget.domain.PowerFields
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real event (Venus E fw 148, UPS mode, grid present): ES.GetStatus offgrid_power = 65522 (uint16 for −14 W),
 * ongrid_power = −2490 → false «Світло зникло: резервний вихід 65522 Вт» and «Розрядка 65522 Вт перевищує поріг».
 */
class WrappedPowerRegressionTest {
    private val es = JSONObject("""{"offgrid_power":65522,"ongrid_power":-2490,"pv_power":0,"bat_soc":99}""")

    private fun observe(esData: JSONObject): JournalObservation {
        val offgrid = PowerFields.read(esData, "offgrid_power")
        val ongrid = PowerFields.read(esData, "ongrid_power")
        val flow = BatteryFlowCalculator.calculate(emptyList(), 5000.0, 5120.0, ongridW = ongrid, offgridW = offgrid)
        val grid = GridStateDetector.detect(offgrid, deviceOk = true, thresholdW = 5.0)
        return JournalObservation(
            ok = true,
            gridState = when (grid) { "on" -> GridState.ON; "off" -> GridState.OFF; else -> GridState.UNKNOWN },
            direction = BatteryDirection.fromApi(flow.direction),
            batteryWatts = flow.powerW.takeIf { flow.available },
            batteryMeasured = !flow.estimated,
            backupWatts = offgrid,
            soc = 99,
            temperatureC = 25.0,
        )
    }

    private fun journal(vararg obs: JournalObservation): List<JournalEvent> {
        var memory: DetectorMemory? = null
        val out = mutableListOf<JournalEvent>()
        obs.forEachIndexed { i, o ->
            val (events, m) = JournalDetector.step(memory, o, 1_000_000L + i * 30_000L, 2000)
            out += events
            memory = m
        }
        return out
    }

    @Test
    fun wrappedBackupPowerIsFoldedAndGridStaysOn() {
        val folded = PowerFields.normalize(es)
        assertEquals(-14.0, folded.getDouble("offgrid_power"), 0.0)
        assertEquals(-2490.0, folded.getDouble("ongrid_power"), 0.0)
        assertEquals(99, folded.getInt("bat_soc"))
        assertEquals("on", GridStateDetector.detect(PowerFields.read(es, "offgrid_power"), true, 5.0))
    }

    @Test
    fun wrappedBackupPowerProducesNoLightLostOrHighDischarge() {
        val normal = JSONObject("""{"offgrid_power":0,"ongrid_power":-2490}""")
        val events = journal(observe(normal), observe(es), observe(es), observe(normal))
        val kinds = events.map { it.kind }
        assertTrue(kinds.toString(), "light_lost" !in kinds)
        assertTrue(kinds.toString(), "anomaly_high_discharge" !in kinds)
        assertTrue(events.none { it.text.contains("65522") })
    }

    @Test
    fun realBackupDrawStillMeansLightLost() {
        val outage = JSONObject("""{"offgrid_power":438,"ongrid_power":0}""")
        val kinds = journal(observe(JSONObject("""{"offgrid_power":0,"ongrid_power":0}""")), observe(outage)).map { it.kind }
        assertTrue(kinds.toString(), "light_lost" in kinds)
    }

    @Test
    fun serverModeSnapshotFoldsWrappedPowers() {
        val root = JSONObject("""
            {"methods":{
              "Bat.GetStatus":{"ok":true,"data":{"soc":99,"bat_capacity":5070,"rated_capacity":5120,"bat_temp":25.0}},
              "ES.GetStatus":{"ok":true,"data":{"pv_power":65535,"ongrid_power":65000,"offgrid_power":65522}}},
             "battery_flow":{"available":false}}
        """.trimIndent())
        val snapshot = BatterySnapshotParser.parse(root)
        assertEquals(-14.0, snapshot.offgridPowerWatts!!, 0.0)
        assertEquals(-536.0, snapshot.gridPowerWatts!!, 0.0)
        assertEquals(-1.0, snapshot.solarPowerWatts!!, 0.0)
    }
}
