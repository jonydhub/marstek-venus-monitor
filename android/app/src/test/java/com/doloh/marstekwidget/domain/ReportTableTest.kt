package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as server/tests/test_report_web.py (web `reportRows`); fixtures = server/report.py output for its DAYS. */
class ReportTableTest {
    private val week = """
        {"period": "week", "title": "Тиждень 23–26.11",
         "current": {"covered_days": 4, "off_seconds": 22167, "outages": 5, "outage_energy_wh": 2052.4, "charge_wh": 4157.8, "charge_cost_uah": 17.96},
         "previous": {"covered_days": 3, "off_seconds": 37740, "outages": 4, "outage_energy_wh": 1750.0, "charge_wh": 4776.4, "charge_cost_uah": 20.63}}
    """.trimIndent()

    @Test fun weekWithPrevious() {
        val r = ReportTable.parse(JSONObject(week))!!
        assertEquals("Тиждень 23–26.11", r.title)
        val t = ReportTable.table(r)!!
        assertEquals(listOf("", "Цей тиждень", "Минулий"), t.head)
        assertEquals(listOf(
            listOf("Без світла", "6 год 9 хв", "10 год 29 хв"),
            listOf("Відключень", "5", "4"),
            listOf("Станція віддала, кВт·год", "2,05", "1,75"),
            listOf("Заряджено, кВт·год", "4,16", "4,78"),
            listOf("Вартість заряду, грн", "17,96", "20,63"),
        ), t.rows)
    }

    @Test fun monthWithoutPrevious() {
        val json = JSONObject(week).put("period", "month")
            .put("previous", JSONObject("""{"covered_days": 0, "off_seconds": 0, "outages": 0, "outage_energy_wh": 0, "charge_wh": 0, "charge_cost_uah": 0}"""))
        json.getJSONObject("current").put("off_seconds", 59907)
        val t = ReportTable.table(ReportTable.parse(json)!!)!!
        assertEquals(listOf("", "Цей місяць"), t.head)
        assertEquals(listOf("Без світла", "16 год 38 хв"), t.rows[0])
    }

    @Test fun empty() {
        val json = JSONObject(week)
        json.getJSONObject("current").put("covered_days", 0)
        assertNull(ReportTable.table(ReportTable.parse(json)!!))
        assertNull(ReportTable.parse(JSONObject("{}")))
    }
}
