package com.doloh.marstekwidget.direct

import com.doloh.marstekwidget.domain.BatteryDirection
import com.doloh.marstekwidget.domain.BatterySnapshotParser
import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectStatusComposerTest {
    private fun ok(name: String, id: Int) = MarstekRpc.decode(Fixtures.text(name).toByteArray(), id)!!

    private val results = mapOf(
        "Bat.GetStatus" to ok("device_bat_status.json", 504),
        "ES.GetStatus" to ok("device_es_status.json", 505),
        "ES.GetMode" to ok("device_es_mode.json", 506),
        "EM.GetStatus" to ok("device_em_status.json", 507),
    )

    @Test
    fun realDeviceAnswersBecomeTheSameSnapshotTheServerProduces() {
        val root = DirectStatusComposer.compose(
            results, BatteryFlowCalculator.calculate(emptyList(), null, null), powerOutage = null, insights = null,
            collectedAt = "2026-11-10T12:00:00Z", model = "VenusE 3.0",
        )
        val snapshot = BatterySnapshotParser.parse(root)
        assertEquals(99, snapshot.socPercent)
        assertEquals(5114.0, snapshot.batteryCapacityWh!!, 0.0)
        assertEquals(5120.0, snapshot.ratedCapacityWh!!, 0.0)
        assertEquals(27.0, snapshot.temperatureCelsius!!, 0.0)
        assertEquals(-333.0, snapshot.gridPowerWatts!!, 0.0)
        assertEquals(0.0, snapshot.offgridPowerWatts!!, 0.0)
        assertEquals("Manual", snapshot.operatingMode)
        assertEquals(0, snapshot.ctState)
        assertEquals(BatteryDirection.UNKNOWN, snapshot.direction)
        assertEquals("2026-11-10T12:00:00Z", snapshot.collectedAt)
        assertNull(snapshot.powerOutage)
    }

    @Test
    fun missingBatteryAnswerKeepsMethodsButParserRejects() {
        val root = DirectStatusComposer.compose(results - "Bat.GetStatus" + ("Bat.GetStatus" to RpcResult.NoResponse),
            BatteryFlowCalculator.calculate(emptyList(), null, null), null, null, "2026-11-10T12:00:00Z", null)
        assertEquals(false, root.getJSONObject("methods").getJSONObject("Bat.GetStatus").getBoolean("ok"))
        assertEquals(false, DirectStatusComposer.hasBattery(root))
    }

    @Test
    fun ratedCapacityFallbackWhenDeviceDoesNotReportIt() {
        val bat = MarstekRpc.decode("""{"id":1,"result":{"soc":50,"bat_capacity":2500}}""".toByteArray(), 1)!!
        val root = DirectStatusComposer.compose(mapOf("Bat.GetStatus" to bat), BatteryFlowCalculator.calculate(emptyList(), null, null),
            null, null, "2026-11-10T12:00:00Z", null, fallbackRatedWh = 5120.0)
        assertEquals(5120.0, BatterySnapshotParser.parse(root).ratedCapacityWh!!, 0.0)
    }
}
