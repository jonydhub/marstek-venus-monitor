package com.doloh.marstekwidget.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

/** «Інші графіки» — same cases as server/tests/test_extra_schedules_web.py (web extraSchedulesParse / extraRow). */
class ExtraSchedulesTest {
    private val now = ms("2026-11-28T12:00:00+02:00")
    private val parents = ExtraSchedule("Батьки", "lviv", "2.1")

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun outage(windows: List<Triple<String, String, String>>, published: Boolean = true, available: Boolean = true,
                       error: String? = null, emergency: Boolean = false) = ExtraScheduleData(
        available = available,
        published = published,
        scheduleDates = if (published) listOf("2026-11-28") else emptyList(),
        windows = windows.map { (s, e, k) -> ScheduleWindow(ms(s), ms(e), k == "maybe") },
        emergency = emergency,
        error = error,
    )

    @Test
    fun parseCleansNamesDropsDuplicatesAndBadEntries() {
        val raw = JSONArray()
            .put(JSONObject().put("name", "  Робота  ").put("region", "lviv").put("queue", "1.1"))
            .put(JSONObject().put("name", "").put("region", "kyiv").put("queue", "3.1"))
            .put(JSONObject().put("name", "Дубль").put("region", "lviv").put("queue", "1.1"))
            .put(JSONObject().put("name", "Без черги").put("region", "lviv"))
            .put(JSONObject.NULL)
            .put(JSONObject().put("name", "Дуже довга назва графіка, що не влізе").put("region", "lviv").put("queue", "2.2"))
            .toString()
        assertEquals(listOf(
            ExtraSchedule("Робота", "lviv", "1.1"),
            ExtraSchedule("Графік", "kyiv", "3.1"),
            ExtraSchedule("Дуже довга назва графіка", "lviv", "2.2"),
        ), ExtraSchedules.parse(raw))
    }

    @Test
    fun parseGarbageAndLimit() {
        assertEquals(emptyList<ExtraSchedule>(), ExtraSchedules.parse("not json"))
        assertEquals(emptyList<ExtraSchedule>(), ExtraSchedules.parse("{\"a\":1}"))
        assertEquals(emptyList<ExtraSchedule>(), ExtraSchedules.parse(null))
        val many = JSONArray().apply { (1..9).forEach { put(JSONObject().put("name", "$it").put("region", "lviv").put("queue", "$it.1")) } }
        assertEquals(6, ExtraSchedules.parse(many.toString()).size)
    }

    @Test
    fun addRoundTripsAndKeepsTheLimit() {
        val list = ExtraSchedules.add(emptyList(), ExtraSchedule("  Робота ", "kyiv", "3.1"))
        assertEquals(listOf(ExtraSchedule("Робота", "kyiv", "3.1")), list)
        assertEquals(list, ExtraSchedules.add(list, ExtraSchedule("Дубль", "kyiv", "3.1")))
        assertEquals(list, ExtraSchedules.parse(ExtraSchedules.encode(list)))
    }

    @Test
    fun insideDefiniteWindowIsWithoutLight() {
        val r = ExtraSchedules.row(parents, outage(listOf(Triple("2026-11-28T11:00:00+02:00", "2026-11-28T14:30:00+02:00", "off"))), now)
        assertEquals(listOf("Батьки", "Львівська обл. · черга 2.1", "off", "без світла"), listOf(r.name, r.sub, r.state.id, r.status))
        assertEquals(listOf("11:00" to "14:30"), r.days!![0].segments.map { it.from to it.to })
        assertFalse(r.days!![1].published)
    }

    @Test
    fun outsideWindowsLightOnMaybeDoesNotCount() {
        val r = ExtraSchedules.row(parents, outage(listOf(
            Triple("2026-11-28T11:00:00+02:00", "2026-11-28T13:00:00+02:00", "maybe"),
            Triple("2026-11-28T18:00:00+02:00", "2026-11-28T21:00:00+02:00", "off"),
        )), now)
        assertEquals("on" to "світло є", r.state.id to r.status)
    }

    @Test
    fun notPublished() {
        val r = ExtraSchedules.row(parents, outage(emptyList(), published = false), now)
        assertEquals("none" to "не опубліковано", r.state.id to r.status)
        assertFalse(r.days!![0].published)
    }

    @Test
    fun unavailableSourceAndFailedFetch() {
        val r = ExtraSchedules.row(parents, outage(emptyList(), published = false, available = false), now)
        assertEquals("na" to "графік недоступний", r.state.id to r.status)
        assertNull(r.days)
        assertEquals("графік недоступний", ExtraSchedules.row(parents, outage(emptyList(), published = false, error = "HTTPError"), now).status)
    }

    @Test
    fun loadingAndEmergency() {
        // the app knows region names offline: «Київ», not the web's «kyiv» placeholder while loading
        val r = ExtraSchedules.row(ExtraSchedule("Робота", "kyiv", "3.1"), null, now)
        assertEquals(listOf("Робота", "Київ · черга 3.1", "loading", "…"), listOf(r.name, r.sub, r.state.id, r.status))
        assertNull(r.days)
        assertEquals("xx · черга 1", ExtraSchedules.row(ExtraSchedule("X", "xx", "1"), null, now).sub)
        val e = ExtraSchedules.row(parents, outage(emptyList(), emergency = true), now)
        assertEquals("off" to "аварійні відключення", e.state.id to e.status)
    }
}
