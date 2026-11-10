package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalDetectorTest {
    private val down = JournalObservation(ok = false)
    private fun up(
        grid: GridState = GridState.ON, direction: BatteryDirection = BatteryDirection.IDLE, battery: Double? = 0.0,
        backup: Double? = 0.0, soc: Int? = 80, temp: Double? = 25.0,
    ) = JournalObservation(true, grid, direction, battery, backup, soc, temp)

    private fun run(vararg obs: JournalObservation, threshold: Int = 2000): Pair<List<JournalEvent>, DetectorMemory> {
        var memory: DetectorMemory? = null
        val out = mutableListOf<JournalEvent>()
        obs.forEachIndexed { i, o ->
            val (events, m) = JournalDetector.step(memory, o, 1_000_000L + i * 30_000L, threshold)
            out += events
            memory = m
        }
        return out to memory!!
    }

    @Test
    fun backupOutputDrawIsLightLostAndIdleIsReturned() {
        val (events, _) = run(up(), up(GridState.OFF, BatteryDirection.DISCHARGING, -130.0, 121.0), up())
        assertEquals(listOf("light_lost", "light_returned"), events.map { it.kind })
        assertTrue(events[0].text.contains("121 Вт"))
        assertEquals(JournalCategory.LIGHT, events[0].category)
    }

    @Test
    fun lostConnectionIsJournaledSeparatelyAndNeverAsLightLost() {
        val (events, memory) = run(up(), down, down, down, down)
        assertEquals(listOf("connection_lost"), events.map { it.kind })
        assertEquals(JournalCategory.CONNECTION, events[0].category)
        assertTrue(events[0].text.contains("стан світла невідомий"))
        assertEquals(GridState.ON, memory.grid)
    }

    @Test
    fun twoFailuresAreNotYetLostAndRestoreIsItsOwnEvent() {
        assertEquals(emptyList<String>(), run(up(), down, down, up()).first.map { it.kind })
        assertEquals(listOf("connection_lost", "connection_restored"), run(up(), down, down, down, up()).first.map { it.kind })
    }

    @Test
    fun unknownGridKeepsLastKnownState() {
        assertEquals(emptyList<String>(), run(up(), up(grid = GridState.UNKNOWN), up()).first.map { it.kind })
    }

    @Test
    fun chargingStartedStoppedAndFull() {
        val (events, _) = run(up(soc = 50), up(direction = BatteryDirection.CHARGING, battery = 800.0, soc = 60),
            up(soc = 70), up(direction = BatteryDirection.CHARGING, battery = 800.0, soc = 98),
            up(direction = BatteryDirection.CHARGING, battery = 300.0, soc = 100), up(soc = 100))
        assertEquals(listOf("charging_started", "charging_stopped", "charging_started", "charged_full"), events.map { it.kind })
    }

    @Test
    fun dischargeAboveThresholdOnceWithRearmAndClamp() {
        val high = up(GridState.OFF, BatteryDirection.DISCHARGING, -2200.0, 2128.0)
        val low = up(GridState.OFF, BatteryDirection.DISCHARGING, -320.0, 300.0)
        val events = run(up(), high, high, low, high).first.filter { it.kind == "anomaly_high_discharge" }
        assertEquals(2, events.size)
        assertTrue(events[0].text.contains("перевищує поріг 2000 Вт"))
        assertEquals(200, JournalDetector.clampThreshold(50))
        assertEquals(10_000, JournalDetector.clampThreshold(50_000))
    }

    @Test
    fun dischargeWhileGridPresentIsAnAnomaly() {
        val obs = up(GridState.ON, BatteryDirection.DISCHARGING, -177.0, 0.0)
        val (events, _) = run(up(), obs, obs)
        assertEquals(listOf("anomaly_discharge_on_grid"), events.map { it.kind })
        assertTrue(events[0].text.contains("177 Вт"))
    }

    @Test
    fun memoryRoundTripsThroughJson() {
        val (_, memory) = run(up(), down, down, down)
        assertEquals(memory, DetectorMemory.fromJson(memory.toJson().toString()))
    }
}
