package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BatterySnapshotExtendedParserTest {
    @Test
    fun parsesBatteryFlowEnergyMetersAndModeWithoutACycleCounter() {
        val json = JSONObject("""
            {
              "collected_at":"2026-11-08T20:50:00Z",
              "methods":{
                "Bat.GetStatus":{"ok":true,"data":{"soc":98,"bat_capacity":5043,"rated_capacity":5120,"bat_temp":25.0}},
                "ES.GetStatus":{"ok":true,"data":{"bat_soc":98,"bat_cap":5120,"pv_power":150,"ongrid_power":-330,"offgrid_power":0,"total_pv_energy":1200,"total_grid_input_energy":2998,"total_grid_output_energy":400,"total_load_energy":1800}},
                "ES.GetMode":{"ok":true,"data":{"mode":"UPS","ct_state":0}},
                "EM.GetStatus":{"ok":true,"data":{"a_power":0,"b_power":0,"c_power":0,"total_power":0,"input_energy":0,"output_energy":0}}
              },
              "battery_flow":{"available":true,"power_w":988.5,"direction":"charging","window_seconds":240,"charge_eta_seconds":280.0}
            }
        """.trimIndent())

        val snapshot = BatterySnapshotParser.parse(json)

        assertEquals(5043.0, snapshot.batteryCapacityWh!!, 0.01)
        assertEquals(150.0, snapshot.solarPowerWatts!!, 0.01)
        assertEquals(-330.0, snapshot.gridPowerWatts!!, 0.01)
        assertEquals(0.0, snapshot.offgridPowerWatts!!, 0.01)
        assertEquals(1200.0, snapshot.totalPvEnergyWh!!, 0.01)
        assertEquals(2998.0, snapshot.totalGridInputEnergyWh!!, 0.01)
        assertEquals(400.0, snapshot.totalGridOutputEnergyWh!!, 0.01)
        assertEquals(1800.0, snapshot.totalLoadEnergyWh!!, 0.01)
        assertEquals("UPS", snapshot.operatingMode)
        assertEquals(0, snapshot.ctState)
        assertFalse(snapshot.toString().contains("cycle", ignoreCase = true))
    }
}
