package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryWarningResolverTest {
    @Test
    fun criticalTemperatureHasHighestPriority() {
        val snapshot = sample(soc = 10, temperature = 50.0, power = 900.0)
        assertEquals(BatteryWarning.CRITICAL_TEMPERATURE, BatteryWarningResolver.resolve(snapshot))
    }

    @Test
    fun warningTemperatureStartsAtFortyFiveDegrees() {
        assertEquals(BatteryWarning.HIGH_TEMPERATURE, BatteryWarningResolver.resolve(sample(temperature = 45.0)))
    }

    @Test
    fun lowChargeIsBelowTwentyPercent() {
        assertEquals(BatteryWarning.LOW_SOC, BatteryWarningResolver.resolve(sample(soc = 19)))
        assertNull(BatteryWarningResolver.resolve(sample(soc = 20)))
    }

    @Test
    fun highBatteryFlowIsStrictlyAboveFiveHundredWatts() {
        assertEquals(BatteryWarning.HIGH_POWER, BatteryWarningResolver.resolve(sample(power = 500.1)))
        assertNull(BatteryWarningResolver.resolve(sample(power = 500.0)))
    }

    @Test
    fun normalTelemetryHasNoWarning() {
        assertNull(BatteryWarningResolver.resolve(sample(soc = 80, temperature = 25.0, power = 300.0)))
    }

    private fun sample(
        soc: Int? = 80,
        temperature: Double? = 25.0,
        power: Double? = 0.0,
    ) = BatterySnapshot(
        socPercent = soc,
        batteryCapacityWh = 4_000.0,
        ratedCapacityWh = 5_120.0,
        temperatureCelsius = temperature,
        batteryPowerWatts = power,
        direction = BatteryDirection.IDLE,
        etaSeconds = null,
        collectedAt = null,
    )
}
