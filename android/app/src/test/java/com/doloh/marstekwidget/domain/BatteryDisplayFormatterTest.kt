package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryDisplayFormatterTest {
    @Test
    fun formatsStoredEnergyAsKilowattHours() {
        assertEquals("5,04 kWh", BatteryDisplayFormatter.formatKwh(5_043.0))
    }

    @Test
    fun formatsPositiveChargeAndNegativeDischargePower() {
        assertEquals("≈ +0,99 кВт", BatteryDisplayFormatter.formatPowerKw(988.5))
        assertEquals("≈ −0,45 кВт", BatteryDisplayFormatter.formatPowerKw(-450.0))
    }

    @Test
    fun smallWidgetShowsBatteryFlowDirectionAndEstimatedPowerClearly() {
        assertEquals("↑≈0,99 кВт", BatteryDisplayFormatter.formatCompactFlowPower(BatteryDirection.CHARGING, 988.5))
        assertEquals("↓≈0,45 кВт", BatteryDisplayFormatter.formatCompactFlowPower(BatteryDirection.DISCHARGING, -450.0))
        assertEquals("↔0,00 кВт", BatteryDisplayFormatter.formatCompactFlowPower(BatteryDirection.IDLE, 0.0))
    }
}
