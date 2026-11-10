package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class JournalExportTest {
    private val t = ZonedDateTime.of(2026, 11, 7, 10, 39, 42, 0, ZoneId.of("Europe/Kyiv")).toInstant().toEpochMilli()
    private val events = listOf(
        JournalEvent(t, "anomaly_high_discharge", JournalCategory.ANOMALY, "Розрядка 2128 Вт перевищує поріг 2000 Вт; заряд 78%", 2128.0),
        JournalEvent(t - 60_000, "connection_lost", JournalCategory.CONNECTION, "Немає зв'язку з батареєю"),
    )

    @Test
    fun textAndCsvCarryOnlyTimeCategoryValueAndText() {
        assertEquals("2026-11-07 10:39:42 | Аномалія | Розрядка 2128 Вт перевищує поріг 2000 Вт; заряд 78%", JournalExport.text(events).lines()[0])
        val csv = JournalExport.csv(events).lines()
        assertEquals("time;category;kind;value;text", csv[0])
        assertEquals("2026-11-07 10:39:42;anomaly;anomaly_high_discharge;2128.0;\"Розрядка 2128 Вт перевищує поріг 2000 Вт; заряд 78%\"", csv[1])
        assertFalse(JournalExport.csv(events).contains("192.168"))
    }

    @Test
    fun parsesServerEventsWithIsoTimestamps() {
        val e = JournalEvent.fromJson(JSONObject("""{"ts":"2026-11-07T10:39:42+02:00","kind":"light_lost","category":"light","text":"Світло зникло","value":121.0}"""))!!
        assertEquals(t, e.tsMillis)
        assertEquals(JournalCategory.LIGHT, e.category)
        assertTrue(JournalEvent.fromJson(JSONObject(events[0].toJson().toString())) == events[0])
    }
}
