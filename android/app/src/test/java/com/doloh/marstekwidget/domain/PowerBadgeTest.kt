package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as server/tests/test_power_badge.py (web `powerBadge`). */
class PowerBadgeTest {
    private fun snap(w: Double?, dir: BatteryDirection, estimated: Boolean = false) =
        BatterySnapshot(99, 5080.0, 5120.0, 24.0, w, dir, null, null, batteryPowerEstimated = estimated)
    private fun text(b: PowerBadge?) = b?.text?.replace('\u00A0', ' ')

    @Test fun discharging() = assertEquals("↓ 445 Вт", text(PowerBadges.of(snap(-445.0, BatteryDirection.DISCHARGING))))
    @Test fun charging() {
        val b = PowerBadges.of(snap(1100.0, BatteryDirection.CHARGING))
        assertEquals("↑ 1,1 кВт", text(b))
        assertEquals(true, b?.charging)
    }
    @Test fun estimated() = assertEquals("↓ ≈ 445 Вт", text(PowerBadges.of(snap(-445.0, BatteryDirection.DISCHARGING, estimated = true))))
    @Test fun idleOrTinyOrNoLinkIsHidden() {
        assertNull(PowerBadges.of(snap(0.0, BatteryDirection.IDLE)))
        assertNull(PowerBadges.of(snap(-5.0, BatteryDirection.DISCHARGING)))
        assertNull(PowerBadges.of(snap(null, BatteryDirection.CHARGING)))
        assertNull(PowerBadges.of(snap(-445.0, BatteryDirection.DISCHARGING), linkLost = true))
    }
}
