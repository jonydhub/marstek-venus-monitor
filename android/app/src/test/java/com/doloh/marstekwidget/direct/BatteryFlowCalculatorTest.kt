package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryFlowCalculatorTest {
    private fun ramp(startWh: Double, whPerMinute: Double, minutes: Int, stepSeconds: Int = 30): List<CapacitySample> =
        (0..minutes * 60 / stepSeconds).map { i -> CapacitySample(1000.0 + i * stepSeconds, startWh + whPerMinute * i * stepSeconds / 60.0) }

    @Test
    fun noSamplesIsUnavailable() {
        val flow = BatteryFlowCalculator.calculate(emptyList(), null, null)
        assertFalse(flow.available)
        assertEquals("unknown", flow.direction)
        assertEquals("collecting_samples", flow.reason)
    }

    @Test
    fun needsTwoMinutesOfSamples() {
        val flow = BatteryFlowCalculator.calculate(ramp(2000.0, 10.0, 1), 2010.0, 5120.0)
        assertFalse(flow.available)
    }

    @Test
    fun risingCapacityIsChargingWithEtaToFull() {
        // +20 Wh/min = 1200 W
        val samples = ramp(4000.0, 20.0, 4)
        val flow = BatteryFlowCalculator.calculate(samples, samples.last().wh, 5120.0)
        assertTrue(flow.available)
        assertEquals("charging", flow.direction)
        assertEquals(1200.0, flow.powerW!!, 0.5)
        val remaining = 5120.0 - samples.last().wh
        assertEquals(remaining / 1200.0 * 3600.0, flow.chargeEtaSeconds!!, 1.0)
        assertNull(flow.dischargeEtaSeconds)
    }

    @Test
    fun fallingCapacityIsDischargingWithEtaToEmpty() {
        val samples = ramp(3000.0, -5.0, 5) // -300 W
        val flow = BatteryFlowCalculator.calculate(samples, samples.last().wh, 5120.0)
        assertEquals("discharging", flow.direction)
        assertEquals(-300.0, flow.powerW!!, 0.5)
        assertEquals(samples.last().wh / 300.0 * 3600.0, flow.dischargeEtaSeconds!!, 1.0)
    }

    @Test
    fun slopeInsideDeadbandIsIdle() {
        val samples = ramp(3000.0, 0.1, 5) // 6 W
        val flow = BatteryFlowCalculator.calculate(samples, 3000.0, 5120.0)
        assertEquals("idle", flow.direction)
        assertEquals(0.0, flow.powerW!!, 0.0)
    }

    @Test
    fun onlyLastFiveMinutesCount() {
        val old = ramp(1000.0, 50.0, 10).map { it.copy(t = it.t - 3600.0) }
        val recent = ramp(3000.0, -5.0, 4).map { it.copy(t = it.t + 10_000.0) }
        val flow = BatteryFlowCalculator.calculate(old + recent, recent.last().wh, 5120.0)
        assertEquals("discharging", flow.direction)
    }

    @Test
    fun fullBatteryEtaIsZero() {
        val samples = ramp(5110.0, 20.0, 3)
        val flow = BatteryFlowCalculator.calculate(samples, 5120.0, 5120.0)
        assertEquals(0.0, flow.chargeEtaSeconds!!, 0.0)
    }

    @Test
    fun jsonMatchesServerKeys() {
        val samples = ramp(4000.0, 20.0, 4)
        val json = BatteryFlowCalculator.calculate(samples, samples.last().wh, 5120.0).toJson()
        assertTrue(json.getBoolean("available"))
        assertEquals("charging", json.getString("direction"))
        assertTrue(json.has("charge_eta_seconds"))
        assertEquals("battery_capacity_delta_regression", json.getString("method"))
    }
}
