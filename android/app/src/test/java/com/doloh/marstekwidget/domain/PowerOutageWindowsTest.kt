package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerOutageWindowsTest {
    @Test
    fun parsesWindowsFloorUsableAndServerMaybeList() {
        val info = PowerOutageParser.parse(JSONObject("""{"available":true,"grid_state":"off","group":"1.1",
            "current_outage":{"kind":"schedule","start":"2026-11-10T19:00:00+02:00","end":"2026-11-10T23:00:00+02:00"},
            "maybe_outages":[{"kind":"maybe","start":"2026-11-11T08:00:00+02:00","end":"2026-11-11T10:00:00+02:00"}],
            "windows":[{"start":"2026-11-10T19:00:00+02:00","end":"2026-11-10T23:00:00+02:00","kind":"off"},
                       {"start":"2026-11-11T08:00:00+02:00","end":"2026-11-11T10:00:00+02:00","kind":"maybe"}],
            "autonomy":{"runtime_seconds":79200,"usable_wh":2662,"floor_pct":11,"stored_wh":3226}}"""))!!
        assertEquals(2, info.windows.size)
        assertEquals(true, info.windows[1].maybe)
        assertEquals(11.0, info.floorPct!!, 0.0)
        assertEquals(2662.0, info.usableWh!!, 0.0)
        assertEquals("2026-11-11T08:00:00+02:00", info.maybeStart)
        assertEquals("2026-11-10T19:00:00+02:00", info.currentStart)
    }

    @Test
    fun dischargeEtaIsToTheFloorWhenKnown() {
        val snapshot = BatterySnapshot(63, 3226.0, 5120.0, 24.0, -121.0, BatteryDirection.DISCHARGING, 95_000L, null,
            powerOutageJson = """{"available":true,"grid_state":"on","autonomy":{"usable_wh":2662,"floor_pct":11}}""")
        assertEquals((2662 / 121.0 * 3600).toLong(), WidgetSummaryResolver.dischargeEtaSeconds(snapshot))
        val summary = WidgetSummaryResolver.resolve(snapshot)
        assertEquals("до 11%", summary.trailingSecondary!!.substringBefore(" ·"))
    }
}
