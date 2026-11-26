package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** At 100 % the station keeps topping up a few watts: the ETA line must not read «До 100% ≈ Готово · оцінка». */
class ToppingUpTest {
    private fun snap(soc: Int, eta: Long?) = BatterySnapshot(
        socPercent = soc, batteryCapacityWh = soc * 51.2, ratedCapacityWh = 5120.0, temperatureCelsius = 30.0,
        batteryPowerWatts = 40.0, direction = BatteryDirection.CHARGING, etaSeconds = eta, collectedAt = null,
    )

    @Test fun fullAndToppingUp() {
        assertEquals("Заряджено · дозаряд", WidgetSummaryResolver.resolve(snap(100, 0)).etaText)
        assertEquals("Заряджено · дозаряд", WidgetSummaryResolver.resolve(snap(100, null)).etaText)
    }

    @Test fun stillCharging() {
        assertEquals("До 100% ≈ 22 хв · оцінка", WidgetSummaryResolver.resolve(snap(95, 22 * 60)).etaText?.replace('\u00A0', ' '))
    }
}
