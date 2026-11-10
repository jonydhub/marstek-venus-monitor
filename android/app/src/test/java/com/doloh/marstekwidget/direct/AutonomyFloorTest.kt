package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reserve = energy above the discharge floor / measured backup output (example: 63 % of 5120 Wh at 121 W). */
class AutonomyFloorTest {
    private val discharging = BatteryFlow(true, -130.0, "discharging", 300, 10, null, null, null)

    @Test
    fun usesUsableEnergyAboveFloorAndMeasuredBackupOutput() {
        val a = AutonomyCalculator.calculate("off", 0.63 * 5120, discharging, null, null, null,
            backupOutputW = 121.0, ratedWh = 5120.0, socPercent = 63.0, floorPct = 11.0)
        assertEquals("measured_backup_output", a.loadBasis)
        assertEquals(121.0, a.loadW!!, 0.01)
        assertEquals(563L, a.floorWh)
        assertEquals(2662L, a.usableWh)
        assertEquals(Math.round((0.63 * 5120 - 563.2) / 121 * 3600), a.runtimeSeconds) // 22 h 0 min (was 26 h 40 min to 0 %)
    }

    @Test
    fun capacityTrendIsTheFallbackDuringAnOutage() {
        val a = AutonomyCalculator.calculate("off", 3225.6, discharging, null, null, null, backupOutputW = null, ratedWh = 5120.0, floorPct = 11.0)
        assertEquals("measured_discharge", a.loadBasis)
    }

    @Test
    fun ratedFromSocAndBelowFloorIsZero() {
        assertEquals(563L, AutonomyCalculator.calculate("off", 3225.6, discharging, null, null, null, 121.0, null, 63.0, 11.0).floorWh)
        val empty = AutonomyCalculator.calculate("off", 400.0, discharging, null, null, 3600, 121.0, 5120.0, null, 11.0)
        assertEquals(0L, empty.runtimeSeconds)
        assertEquals(false, empty.coversNextOutage)
    }

    @Test
    fun withoutFloorTheJsonSaysSo() {
        val a = AutonomyCalculator.calculate("off", 3225.6, discharging, null, null, null, backupOutputW = 121.0)
        assertNull(a.floorPct)
        assertEquals(3226L, a.usableWh)
        assertEquals(true, a.toJson().isNull("floor_pct"))
    }
}
