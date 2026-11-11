package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridAndAutonomyTest {
    @Test
    fun backupDrawAboveThresholdMeansGridOff() {
        assertEquals("off", GridStateDetector.detect(42.0, deviceOk = true, thresholdW = 5.0))
        // Negative backup-port power is not a load draw (Venus E UPS mode: uint16 65522 = −14 W).
        assertEquals("on", GridStateDetector.detect(-42.0, deviceOk = true, thresholdW = 5.0))
        assertEquals("on", GridStateDetector.detect(3.0, deviceOk = true, thresholdW = 5.0))
        assertEquals("on", GridStateDetector.detect(0.0, deviceOk = true, thresholdW = 5.0))
        assertEquals("unknown", GridStateDetector.detect(null, deviceOk = true, thresholdW = 5.0))
        assertEquals("unknown", GridStateDetector.detect(42.0, deviceOk = false, thresholdW = 5.0))
        assertEquals("on", GridStateDetector.detect(42.0, deviceOk = true, thresholdW = 50.0))
    }

    private fun flow(direction: String, power: Double?) = BatteryFlow(true, power, direction, 300, 10, null, null, null)

    @Test
    fun measuredDischargeDuringOutageWins() {
        val a = AutonomyCalculator.calculate("off", 2560.0, flow("discharging", -640.0), expectedLoadW = 100.0, measuredLoadW = 50.0, outageRemainingSeconds = 7200)
        assertEquals("measured_discharge", a.loadBasis)
        assertEquals(14400L, a.runtimeSeconds)
        assertEquals(true, a.coversNextOutage)
    }

    @Test
    fun configuredAverageBeforeFirstOutage() {
        val a = AutonomyCalculator.calculate("on", 2560.0, flow("idle", 0.0), expectedLoadW = 1280.0, measuredLoadW = 50.0, outageRemainingSeconds = 10800)
        assertEquals("configured_average", a.loadBasis)
        assertEquals(7200L, a.runtimeSeconds)
        assertEquals(false, a.coversNextOutage)
    }

    @Test
    fun measuredAverageWhenNothingConfigured() {
        val a = AutonomyCalculator.calculate("on", 1000.0, null, expectedLoadW = null, measuredLoadW = 100.0, outageRemainingSeconds = null)
        assertEquals("measured_average_24h", a.loadBasis)
        assertEquals(36000L, a.runtimeSeconds)
        assertNull(a.coversNextOutage)
    }

    @Test
    fun unknownWithoutAnyLoad() {
        val a = AutonomyCalculator.calculate("on", 1000.0, null, null, null, 3600)
        assertNull(a.runtimeSeconds)
        assertEquals(3600L, a.outageSeconds)
        val json = a.toJson()
        assertEquals(true, json.isNull("runtime_seconds"))
    }
}
