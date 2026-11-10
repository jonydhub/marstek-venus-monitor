package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatterySnapshotParserTest {
    @Test
    fun parsesChargingSnapshotAndUsesChargeEta() {
        val json = JSONObject("""
            {
              "collected_at":"2026-11-08T17:30:18Z",
              "methods":{
                "Bat.GetStatus":{"ok":true,"data":{"soc":97,"bat_capacity":4976,"rated_capacity":5120,"bat_temp":21.0}},
                "ES.GetStatus":{"ok":true,"data":{"pv_power":0,"ongrid_power":-1094,"offgrid_power":0}}
              },
              "battery_flow":{"available":true,"power_w":988.5,"direction":"charging","window_seconds":223,"charge_eta_seconds":524.4,"discharge_eta_seconds":null}
            }
        """.trimIndent())

        val snapshot = BatterySnapshotParser.parse(json)

        assertEquals(97, snapshot.socPercent)
        assertEquals(21.0, snapshot.temperatureCelsius!!, 0.01)
        assertEquals(BatteryDirection.CHARGING, snapshot.direction)
        assertEquals(988.5, snapshot.batteryPowerWatts!!, 0.01)
        assertEquals(524L, snapshot.etaSeconds)
        assertEquals("2026-11-08T17:30:18Z", snapshot.collectedAt)
    }

    @Test
    fun switchesToDischargeEtaWhenBatteryEnergyFalls() {
        val json = JSONObject("""
            {
              "methods":{"Bat.GetStatus":{"ok":true,"data":{"soc":62,"bat_capacity":3174,"rated_capacity":5120,"bat_temp":24.0}}},
              "battery_flow":{"available":true,"power_w":-450.0,"direction":"discharging","window_seconds":300,"charge_eta_seconds":null,"discharge_eta_seconds":25392.0}
            }
        """.trimIndent())

        val snapshot = BatterySnapshotParser.parse(json)

        assertEquals(BatteryDirection.DISCHARGING, snapshot.direction)
        assertEquals(-450.0, snapshot.batteryPowerWatts!!, 0.01)
        assertEquals(25_392L, snapshot.etaSeconds)
    }

    @Test
    fun unavailableFlowDoesNotInventEta() {
        val snapshot = BatterySnapshotParser.parse(JSONObject("""
            {"methods":{"Bat.GetStatus":{"ok":true,"data":{"soc":80,"bat_temp":20}}},"battery_flow":{"available":false}}
        """.trimIndent()))

        assertEquals(BatteryDirection.UNKNOWN, snapshot.direction)
        assertNull(snapshot.batteryPowerWatts)
        assertNull(snapshot.etaSeconds)
    }
}
