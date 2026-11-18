package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/** Server mode renders the server's grid_state; grid_detail.bypass_now adds «визначено: байпас» (UPS bypass). */
class BypassLightStatusTest {
    private fun snapshot(detail: String) = BatterySnapshot(
        socPercent = 100, batteryCapacityWh = 5120.0, ratedCapacityWh = 5120.0, temperatureCelsius = 22.0,
        batteryPowerWatts = null, direction = BatteryDirection.IDLE, etaSeconds = null, collectedAt = null,
        powerOutageJson = """{"available":true,"grid_state":"on","current_outage":null,"schedule_published":true,"autonomy":{},"grid_detail":$detail}""",
    )

    @Test
    fun bypassNowIsParsedAndShownAsAHint() {
        val detail = """{"pending_return":false,"confirmed_by":"bypass_no_discharge","bypass_load_seen":true,"bypass_now":true}"""
        assertTrue(PowerOutageParser.parse(JSONObject("""{"available":true,"grid_state":"on","grid_detail":$detail}"""))!!.bypassNow)
        val status = LightStatusResolver.resolve(snapshot(detail), fetchFailed = false)
        assertEquals(LightKind.ON, status.kind)
        assertEquals("визначено: байпас", status.hint)
    }

    @Test
    fun oldServersWithoutTheKeyStayPlain() {
        val status = LightStatusResolver.resolve(snapshot("""{"pending_return":false}"""), fetchFailed = false)
        assertEquals(LightKind.ON, status.kind)
        assertNull(status.hint)
        assertFalse(PowerOutageParser.parse(JSONObject("""{"available":true,"grid_state":"on","grid_detail":null}"""))!!.bypassNow)
    }
}
