package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Same cases as server/tests/test_counters_web.py (web `countersRows` / `countersNote`). */
class CountersTableTest {
    private val json = """
        {"since": "2026-11-20", "window_days": 7,
         "today": {"charge_wh": 843.9, "discharge_wh": 1718.5, "outage_energy_wh": 1462.4, "off_seconds": 9387, "outages": 2},
         "window": {"charge_wh": 8934.2, "discharge_wh": 5242.6, "outage_energy_wh": 3659.6, "off_seconds": 51127, "outages": 9},
         "total": {"charge_wh": 12034.2, "discharge_wh": 2.0, "outage_energy_wh": 0, "off_seconds": 30, "outages": 9}}
    """.trimIndent()
    private fun List<String>.c() = map { it.replace('\u00A0', ' ') }

    @Test fun rows() {
        val c = Counters.parse(JSONObject(json))!!
        val t = CountersTable.table(c)
        assertEquals(listOf("", "Сьогодні", "7 днів", "Усього"), t.head.c())
        assertEquals(listOf(
            listOf("Заряджено", "0,84", "8,93", "12,03"),
            listOf("Розряджено", "1,72", "5,24", "—"),
            listOf("Спожито без світла", "1,46", "3,66", "—"),
            listOf("Без світла, год", "2,6", "14,2", "—"),
        ), t.rows)
    }

    @Test fun shortWindowAndNote() {
        val c = Counters.parse(JSONObject(json).put("window_days", 2))!!
        assertEquals("2 дні", CountersTable.table(c).head[2].replace('\u00A0', ' '))
        assertTrue(CountersTable.note(c).startsWith("Усього — з 20.11.2026"))
        assertNull(Counters.parse(null))
        assertNull(Counters.parse(JSONObject("""{"today": {}}""")))
    }

    @Test fun directModeHasNoTotal() {
        val today = LocalDate.of(2026, 11, 26)
        val c = Counters.fromDays(listOf(
            StatsDay(today, true, 3600, 1, 300.0, 840.0, 500.0),
            StatsDay(today.minusDays(1), false, 0, 0, 0.0, 200.0, 0.0),
        ))!!
        assertNull(c.total)
        val t = CountersTable.table(c)
        assertEquals(listOf("", "Сьогодні", "2 дні"), t.head.c())
        assertEquals(listOf("Заряджено", "0,84", "1,04"), t.rows[0])
        assertEquals(listOf("Без світла, год", "1,0", "1,0"), t.rows[3])
        assertTrue(CountersTable.note(c).startsWith("З пам'яті телефона"))
    }

    @Test fun parsedFromStats() {
        val report = StatsParser.parse(JSONObject("""{"days": [], "outages": [], "charges": [], "counters": $json}"""))
        assertEquals(12034.2, report.counters!!.total!!.chargeWh, 0.01)
    }
}
