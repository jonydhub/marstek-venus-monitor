package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Light on/off only from the backup output; a lost connection is «Немає зв'язку з батареєю», never «Світла нема». */
class LightStatusTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val now = ZonedDateTime.of(2026, 11, 10, 20, 0, 0, 0, kyiv)

    private fun snapshot(grid: String, scheduledNow: Boolean = false, linkLost: Boolean = false): BatterySnapshot {
        val current = if (scheduledNow) """{"kind":"schedule","start":"2026-11-10T19:00:00+02:00","end":"2026-11-10T23:00:00+02:00"}""" else "null"
        return BatterySnapshot(
            socPercent = 63, batteryCapacityWh = 3226.0, ratedCapacityWh = 5120.0, temperatureCelsius = 24.0,
            batteryPowerWatts = null, direction = BatteryDirection.IDLE, etaSeconds = null, collectedAt = null,
            powerOutageJson = """{"available":true,"group":"1.1","grid_state":"$grid","current_outage":$current,"schedule_published":true,"autonomy":{}}""",
            batteryLinkLost = linkLost,
        )
    }

    @Test
    fun lostConnectionIsNoLinkEvenWhenTheCachedSnapshotSaidOff() {
        val status = LightStatusResolver.resolve(snapshot("off"), fetchFailed = true, now = now)
        assertEquals(LightKind.NO_LINK, status.kind)
        assertEquals("Немає зв'язку з батареєю", status.headline)
        assertEquals(GridState.UNKNOWN, status.gridState)
        assertNull(status.hint)
    }

    @Test
    fun serverReportingTheBatteryUnreachableIsNoLink() {
        assertEquals(LightKind.NO_LINK, LightStatusResolver.resolve(snapshot("unknown", linkLost = true), false, now).kind)
    }

    @Test
    fun scheduleOutageDuringLostConnectionIsOnlyAHint() {
        val status = LightStatusResolver.resolve(snapshot("on", scheduledNow = true), fetchFailed = true, now = now)
        assertEquals(LightKind.NO_LINK, status.kind)
        assertEquals("за графіком зараз відключення", status.hint)
        assertEquals(GridState.UNKNOWN, status.gridState)
    }

    @Test
    fun freshDataUsesTheBackupOutputState() {
        assertEquals(LightKind.OFF, LightStatusResolver.resolve(snapshot("off"), false, now).kind)
        assertEquals(LightKind.ON, LightStatusResolver.resolve(snapshot("on", scheduledNow = true), false, now).kind)
    }

    @Test
    fun widgetSummaryWithLostLinkNeverSaysNoLight() {
        val summary = WidgetSummaryResolver.resolve(snapshot("off", scheduledNow = true), now, linkLost = true)
        assertEquals(GridState.UNKNOWN, summary.lightState)
        assertEquals("Немає зв'язку з батареєю", summary.primary)
        assertFalse(summary.primary.contains("нема"))
        assertTrue(summary.secondary!!.contains("графіком зараз відключення"))
    }

    @Test
    fun lostLinkWithScheduledOutageDoesNotFireLightLost() {
        val memory = AlertMemory(gridState = GridState.ON, direction = BatteryDirection.IDLE, soc = 63, hadFreshData = true)
        val nowMillis = now.toInstant().toEpochMilli()
        val unknown = snapshot("unknown", scheduledNow = true, linkLost = true).copy(collectedAt = now.toInstant().toString())
        val result = AlertEvaluator.evaluate(memory, unknown, nowMillis, AlertSettings())
        assertFalse(result.notifications.any { it.id == AlertId.LIGHT_LOST })
        assertEquals(GridState.ON, result.memory.gridState)
        // even an inconsistent cached «off» with a lost link must not fire it
        val cachedOff = snapshot("off", scheduledNow = true, linkLost = true).copy(collectedAt = now.toInstant().toString())
        assertFalse(AlertEvaluator.evaluate(memory, cachedOff, nowMillis, AlertSettings()).notifications.any { it.id == AlertId.LIGHT_LOST })
    }
}
