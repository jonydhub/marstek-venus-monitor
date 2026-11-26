package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as server/tests/test_energy_calc.py (web `energyCalc`). */
class EnergyCalcTest {
    private val battery = EnergyCalc.Battery(ratedWh = 5120.0, storedWh = 4014.0, usableWh = 3451.0, floorPct = 11.0)
    private fun String?.c() = this?.replace('\u00A0', ' ')

    @Test fun kettleHalfHour() {
        val r = EnergyCalc.calc(1500.0, 30 * 60, battery)!!
        assertEquals("750 Вт·год", r.energy.c())
        assertEquals("3,24 грн", r.cost.c())
        assertEquals("15 %", r.batteryShare.c())
        assertEquals("64 %", r.socAfter.c())
        assertEquals(true, r.fits)
        assertEquals("2 год 18 хв", r.runtime.c())
        assertEquals("до 11 %", r.runtimeTo.c())
        assertFalse(r.overLimit)
    }

    @Test fun doesNotFit() {
        val r = EnergyCalc.calc(2000.0, 3 * 3600, battery)!!
        assertEquals("6,00 кВт·год", r.energy.c())
        assertEquals("25,92 грн", r.cost.c())
        assertEquals("117 %", r.batteryShare.c())
        assertEquals(false, r.fits)
        assertNull(r.socAfter)
        assertEquals("1 год 44 хв", r.runtime.c())
    }

    @Test fun overBackupLimit() {
        val r = EnergyCalc.calc(3000.0, 10 * 60, battery)!!
        assertEquals("500 Вт·год", r.energy.c())
        assertTrue(r.overLimit)
    }

    @Test fun withoutBatteryData() {
        val r = EnergyCalc.calc(15.0, 24 * 3600, null)!!
        assertEquals("360 Вт·год", r.energy.c())
        assertEquals("1,56 грн", r.cost.c())
        assertNull(r.batteryShare)
        assertNull(r.fits)
        assertNull(r.runtime)
        assertEquals("до 0 %", r.runtimeTo.c())
    }

    @Test fun usableFromStoredWhenServerHasNoAutonomy() {
        // direct mode: no usable_wh → stored − floor share (4014 − 563,2 = 3450,8)
        val r = EnergyCalc.calc(1500.0, 30 * 60, battery.copy(usableWh = null))!!
        assertEquals("2 год 18 хв", r.runtime.c())
    }

    @Test fun emptyInputs() {
        assertNull(EnergyCalc.calc(0.0, 1800, battery))
        assertNull(EnergyCalc.calc(1500.0, 0, battery))
        assertNull(EnergyCalc.calc(null, 1800, battery))
        assertNull(EnergyCalc.calc(1500.0, 1800, battery, tariff = 0.0)!!.cost)
    }
}
