package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryInsightsTest {
    private val block = """
        {"backup_output_w":420.0,"backup_avg_w_24h":310.5,"backup_avg_coverage_hours":23.5,
         "backup_energy_today_wh":1840.2,"temperature_c":24.5,
         "soc_history":[{"t":"2026-11-09T10:00:00+02:00","soc":80},{"t":"2026-11-09T10:15:00+02:00","soc":78},{"t":"bad","soc":70}]}
    """.trimIndent()

    @Test
    fun parsesAllInsightFieldsAndSkipsBadHistoryPoints() {
        val insights = BatteryInsightsParser.parse(JSONObject(block))!!
        assertEquals(420.0, insights.backupOutputWatts!!, 0.0)
        assertEquals(310.5, insights.backupAverageWatts24h!!, 0.0)
        assertEquals(23.5, insights.backupAverageCoverageHours!!, 0.0)
        assertEquals(1840.2, insights.backupEnergyTodayWh!!, 0.0)
        assertEquals(24.5, insights.temperatureCelsius!!, 0.0)
        assertEquals(2, insights.socHistory.size)
        assertEquals(80, insights.socHistory[0].soc)
        assertEquals(15 * 60 * 1000L, insights.socHistory[1].epochMillis - insights.socHistory[0].epochMillis)
    }

    @Test
    fun missingBlockIsNullAndNullFieldsStayNull() {
        assertNull(BatteryInsightsParser.parse(null))
        val sparse = BatteryInsightsParser.parse(JSONObject("""{"backup_output_w":null,"soc_history":[]}"""))!!
        assertNull(sparse.backupOutputWatts)
        assertNull(sparse.backupAverageWatts24h)
        assertTrue(sparse.socHistory.isEmpty())
    }

    @Test
    fun snapshotParserKeepsInsightsOptional() {
        val root = JSONObject("""{"methods":{"Bat.GetStatus":{"data":{"soc":55}}}}""")
        assertNull(BatterySnapshotParser.parse(root).insights)
        root.put("insights", JSONObject(block))
        assertEquals(420.0, BatterySnapshotParser.parse(root).insights!!.backupOutputWatts!!, 0.0)
    }
}
