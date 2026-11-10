package com.doloh.marstekwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetMetricPlanTest {
    @Test
    fun compactAndStripPrioritizeCoreBatteryMetrics() {
        val expected = setOf(
            WidgetMetric.SOC,
            WidgetMetric.STORED_ENERGY,
            WidgetMetric.BATTERY_STATE,
            WidgetMetric.ESTIMATED_BATTERY_POWER,
            WidgetMetric.ETA,
            WidgetMetric.FRESHNESS,
        )

        assertEquals(expected, WidgetMetricPlan.forVariant(WidgetVariant.MINI))
        assertEquals(expected, WidgetMetricPlan.forVariant(WidgetVariant.STRIP))
    }

    @Test
    fun mediumAddsTemperatureAndLiveGridAndBackupPower() {
        val metrics = WidgetMetricPlan.forVariant(WidgetVariant.MEDIUM)

        assertTrue(metrics.containsAll(setOf(
            WidgetMetric.SOC,
            WidgetMetric.STORED_ENERGY,
            WidgetMetric.BATTERY_STATE,
            WidgetMetric.ESTIMATED_BATTERY_POWER,
            WidgetMetric.ETA,
            WidgetMetric.TEMPERATURE,
            WidgetMetric.GRID_POWER,
            WidgetMetric.BACKUP_POWER,
            WidgetMetric.FRESHNESS,
        )))
        assertFalse(metrics.contains(WidgetMetric.ACCUMULATED_COUNTERS))
    }

    @Test
    fun largeReplacesCountersWithReserveAndAddsOperatingMode() {
        val metrics = WidgetMetricPlan.forVariant(WidgetVariant.LARGE)
        val medium = WidgetMetricPlan.forVariant(WidgetVariant.MEDIUM)

        assertTrue(metrics.containsAll(medium))
        assertFalse(metrics.contains(WidgetMetric.ACCUMULATED_COUNTERS))
        assertTrue(metrics.contains(WidgetMetric.RESERVE))
        assertTrue(metrics.contains(WidgetMetric.OPERATING_MODE))
        assertFalse(metrics.any { it.name.contains("PV") || it.name.contains("CYCLE") })
    }
}
