package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryOperationStateResolverTest {
    @Test
    fun distinguishesChargingDischargingWaitingAndUnknown() {
        assertEquals(BatteryOperationState.CHARGING, BatteryOperationStateResolver.resolve(BatteryDirection.CHARGING))
        assertEquals(BatteryOperationState.DISCHARGING, BatteryOperationStateResolver.resolve(BatteryDirection.DISCHARGING))
        assertEquals(BatteryOperationState.WAITING, BatteryOperationStateResolver.resolve(BatteryDirection.IDLE))
        assertEquals(BatteryOperationState.UNKNOWN, BatteryOperationStateResolver.resolve(BatteryDirection.UNKNOWN))
    }
}
