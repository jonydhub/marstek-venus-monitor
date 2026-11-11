package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class PowerOutageTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val now = ZonedDateTime.of(2026, 10, 17, 19, 0, 0, 0, kyiv)

    private fun outage(body: String) = PowerOutageParser.parse(JSONObject(body))!!

    private val scheduled = """
        {"available":true,"group":"1.1","grid_state":"on","current_outage":null,
         "next_outage":{"kind":"schedule","start":"2026-10-17T20:00:00+03:00","end":"2026-10-17T22:00:00+03:00","duration_seconds":7200,"remaining_seconds":7200},
         "schedule_published":true,
         "autonomy":{"load_w":640.0,"load_basis":"configured_average","stored_wh":2560,"runtime_seconds":14400,"outage_seconds":7200,"covers_next_outage":true},
         "error":null}
    """.trimIndent()

    @Test
    fun parsesGridStateGroupNextOutageAndAutonomy() {
        val info = outage(scheduled)
        assertEquals(GridState.ON, info.gridState)
        assertEquals("1.1", info.group)
        assertEquals("2026-10-17T20:00:00+03:00", info.nextStart)
        assertEquals(7200L, info.nextDurationSeconds)
        assertEquals(14400L, info.runtimeSeconds)
        assertEquals(true, info.coversOutage)
        assertTrue(info.schedulePublished)
    }

    @Test
    fun missingOrUnavailableBlockIsNull() {
        assertNull(PowerOutageParser.parse(null))
        assertNull(PowerOutageParser.parse(JSONObject("""{"available":false}""")))
    }

    @Test
    fun headlineSaysWhetherPowerIsPresent() {
        assertEquals("Світло є", PowerOutageFormatter.headline(outage(scheduled)))
        val off = outage("""{"available":true,"group":"1.1","grid_state":"off","schedule_published":false,"autonomy":{}}""")
        assertEquals("Світла нема", PowerOutageFormatter.headline(off))
        val unknown = outage("""{"available":true,"group":"1.1","grid_state":"unknown","schedule_published":false,"autonomy":{}}""")
        assertEquals("Світло: —", PowerOutageFormatter.headline(unknown))
    }

    @Test
    fun detailShowsNextWindowInKyivTime() {
        assertEquals("Відключення о 20:00", PowerOutageFormatter.detail(outage(scheduled), now))
    }

    @Test
    fun detailShowsEndOfCurrentOutage() {
        val current = outage("""
            {"available":true,"group":"1.1","grid_state":"off",
             "current_outage":{"kind":"schedule","start":"2026-10-17T20:00:00+03:00","end":"2026-10-17T22:00:00+03:00","duration_seconds":7200,"remaining_seconds":5400},
             "schedule_published":true,"autonomy":{}}
        """.trimIndent())
        assertEquals("Повернення о 22:00", PowerOutageFormatter.detail(current, now))
    }

    @Test
    fun detailForOtherDayIncludesDate() {
        val tomorrow = outage(scheduled.replace("2026-10-17T2", "2026-10-18T2"))
        assertEquals("Відключення завтра о 20:00", PowerOutageFormatter.detail(tomorrow, now))
        val later = outage(scheduled.replace("2026-10-17T2", "2026-10-19T2"))
        assertEquals("Відключення 19.10 о 20:00", PowerOutageFormatter.detail(later, now))
    }

    @Test
    fun detailWithoutScheduleDoesNotClaimSafety() {
        val none = outage("""{"available":true,"group":"1.1","grid_state":"on","schedule_published":false,"autonomy":{}}""")
        assertEquals("Графік не опубліковано", PowerOutageFormatter.detail(none, now))
        val clear = outage("""{"available":true,"group":"1.1","grid_state":"on","schedule_published":true,"autonomy":{}}""")
        assertEquals("За графіком без відключень", PowerOutageFormatter.detail(clear, now))
    }

    @Test
    fun reserveVerdictOnlyWhenRuntimeKnown() {
        assertEquals("Запас ≈ 4 год 0 хв · вистачить", PowerOutageFormatter.reserve(outage(scheduled)))
        val short = outage(scheduled.replace("\"covers_next_outage\":true", "\"covers_next_outage\":false"))
        assertEquals("Запас ≈ 4 год 0 хв · не вистачить", PowerOutageFormatter.reserve(short))
        val unknown = outage("""{"available":true,"group":"1.1","grid_state":"on","schedule_published":false,"autonomy":{"runtime_seconds":null}}""")
        assertNull(PowerOutageFormatter.reserve(unknown))
    }

    @Test
    fun shortDetailFitsSmallWidgets() {
        assertEquals("відкл. 20:00", PowerOutageFormatter.shortDetail(outage(scheduled), now))
        val current = outage("""
            {"available":true,"group":"1.1","grid_state":"off",
             "current_outage":{"kind":"schedule","start":"2026-10-17T20:00:00+03:00","end":"2026-10-17T22:00:00+03:00","duration_seconds":7200,"remaining_seconds":5400},
             "schedule_published":true,"autonomy":{}}
        """.trimIndent())
        assertEquals("до 22:00", PowerOutageFormatter.shortDetail(current, now))
        val none = outage("""{"available":true,"group":"1.1","grid_state":"on","schedule_published":false,"autonomy":{}}""")
        assertNull(PowerOutageFormatter.shortDetail(none, now))
        val tomorrow = outage(scheduled.replace("2026-10-17T2", "2026-10-18T2"))
        assertNull(PowerOutageFormatter.shortDetail(tomorrow, now))
    }

    @Test
    fun snapshotParserCarriesOutageBlockAndRawJson() {
        val root = JSONObject("""
            {"methods":{"Bat.GetStatus":{"ok":true,"data":{"soc":50,"bat_capacity":2560,"rated_capacity":5120}}},
             "battery_flow":{"available":false},
             "power_outage":$scheduled}
        """.trimIndent())
        val snapshot = BatterySnapshotParser.parse(root)
        assertEquals(GridState.ON, snapshot.powerOutage!!.gridState)
        assertFalse(snapshot.powerOutageJson.isNullOrBlank())
        assertEquals("1.1", PowerOutageParser.parse(JSONObject(snapshot.powerOutageJson!!))!!.group)
    }
}
