package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Light state vs. schedule never contradict each other; today/tomorrow rows and window lists. */
class ScheduleTextsTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val now = ZonedDateTime.of(2026, 11, 11, 9, 30, 0, 0, kyiv)

    private fun info(grid: String) = PowerOutageParser.parse(JSONObject(block(grid)))!!

    private fun block(grid: String) = """
        {"available":true,"group":"1.1","grid_state":"$grid",
         "current_outage":{"kind":"schedule","start":"2026-11-11T07:00:00+02:00","end":"2026-11-11T13:00:00+02:00"},
         "next_outage":{"kind":"schedule","start":"2026-11-11T15:00:00+02:00","end":"2026-11-11T21:00:00+02:00"},
         "schedule_published":true,"schedule_dates":["2026-11-11"],"source":"loe","source_name":"Львівобленерго","provider":"auto",
         "windows":[{"start":"2026-11-11T07:00:00+02:00","end":"2026-11-11T13:00:00+02:00","kind":"off"},
                    {"start":"2026-11-11T15:00:00+02:00","end":"2026-11-11T21:00:00+02:00","kind":"off"},
                    {"start":"2026-11-11T21:00:00+02:00","end":"2026-11-11T22:00:00+02:00","kind":"maybe"}],
         "autonomy":{}}
    """.trimIndent()

    @Test
    fun gridOnDuringScheduledWindowSaysScheduleAndLightIsOn() {
        assertEquals("За графіком відключення до 13:00 · світло є", PowerOutageFormatter.detail(info("on"), now))
        assertEquals("графік: відкл. до 13:00", PowerOutageFormatter.shortDetail(info("on"), now))
    }

    @Test
    fun returnTimeOnlyWhenGridIsReallyOff() {
        assertEquals("Повернення о 13:00", PowerOutageFormatter.detail(info("off"), now))
        assertEquals("до 13:00", PowerOutageFormatter.shortDetail(info("off"), now))
        assertEquals("За графіком відключення до 13:00", PowerOutageFormatter.detail(info("unknown"), now))
    }

    @Test
    fun lostLinkNeverClaimsLightAndHeadlineIsNotRepeated() {
        assertEquals("За графіком відключення до 13:00", PowerOutageFormatter.detail(info("on"), now, GridState.UNKNOWN))
        assertEquals("За графіком відключення до 13:00", PowerOutageFormatter.detailBesideHeadline(info("on"), now))
        val snapshot = BatterySnapshotParser.parse(JSONObject("""{"methods":{"Bat.GetStatus":{"ok":true,"data":{"soc":90,"bat_capacity":4608,"rated_capacity":5120}}},"battery_flow":{"available":false},"power_outage":${block("on")}}"""))
        val lost = WidgetSummaryResolver.resolve(snapshot, now, linkLost = true)
        assertTrue(lost.lines.toString(), lost.lines.none { it.text.contains("світло є") })
    }

    @Test
    fun nextOutageWhenGridOnAndNoActiveWindow() {
        val noCurrent = PowerOutageParser.parse(JSONObject("""
            {"available":true,"group":"1.1","grid_state":"on","current_outage":null,
             "next_outage":{"kind":"schedule","start":"2026-11-11T15:00:00+02:00","end":"2026-11-11T21:00:00+02:00"},
             "schedule_published":true,"autonomy":{}}
        """.trimIndent()))!!
        assertEquals("Відключення о 15:00", PowerOutageFormatter.detail(noCurrent, now))
    }

    @Test
    fun widgetSummaryNeverShowsReturnTimeWhileLightIsOn() {
        val snapshot = BatterySnapshotParser.parse(JSONObject("""
            {"methods":{"Bat.GetStatus":{"ok":true,"data":{"soc":100,"bat_capacity":5120,"rated_capacity":5120}}},
             "battery_flow":{"available":true,"direction":"idle","power_w":0},"power_outage":${block("on")}}
        """.trimIndent()))
        val summary = WidgetSummaryResolver.resolve(snapshot, now)
        val all = listOfNotNull(summary.primary, summary.secondary, summary.trailingPrimary, summary.trailingSecondary) + summary.lines.map { it.text }
        assertTrue(all.toString(), all.none { it.contains("Повернення") })
        assertEquals("За графіком відключення до 13:00 · світло є", summary.secondary)
    }

    @Test
    fun todayAndTomorrowRowsListWindowsAndPublication() {
        val days = ScheduleDays.days(info("on"), now.toInstant().toEpochMilli())
        val today = days[0]
        assertTrue(today.isToday)
        assertTrue(today.published)
        assertEquals(listOf("07:00–13:00", "15:00–21:00"), today.definite)
        assertEquals("Відключення 07:00–13:00, 15:00–21:00", today.windowsText)
        assertEquals("Можливе 21:00–22:00", today.maybeText)
        assertEquals(9.5f / 24f, today.nowFraction!!, 0.001f)
        assertEquals(3, today.segments.size)
        val tomorrow = days[1]
        assertFalse(tomorrow.published)
        assertNull(tomorrow.nowFraction)
        assertEquals("Завтра: графік ще не опубліковано", tomorrow.summary)
    }

    @Test
    fun windowCrossingMidnightIsSplitIntoBothDays() {
        val night = PowerOutageParser.parse(JSONObject("""
            {"available":true,"group":"1.1","grid_state":"on","schedule_published":true,"schedule_dates":["2026-11-11","2026-11-12"],
             "windows":[{"start":"2026-11-11T22:00:00+02:00","end":"2026-11-12T02:00:00+02:00","kind":"off"}],"autonomy":{}}
        """.trimIndent()))!!
        val (today, tomorrow) = ScheduleDays.days(night, now.toInstant().toEpochMilli())
        assertEquals(listOf("22:00–24:00"), today.definite)
        assertEquals(listOf("00:00–02:00"), tomorrow.definite)
        val clear = ScheduleDays.days(night.copy(windows = emptyList()), now.toInstant().toEpochMilli())
        assertEquals("Без відключень", clear[1].windowsText)
    }

    @Test
    fun parserPrefersSourceNameAndKeepsIdsForTheLink() {
        val parsed = info("on")
        assertEquals("Львівобленерго", parsed.source)
        assertEquals("loe", parsed.sourceId)
        assertEquals(listOf("2026-11-11"), parsed.scheduleDates)
        assertEquals("https://poweron.loe.lviv.ua/", OutageSourceLinks.url(parsed.sourceId, parsed.source))
        assertNull(OutageSourceLinks.url("ua-outages"))
        val auto = PowerOutageParser.parse(JSONObject("""{"available":true,"provider":"auto","grid_state":"on","autonomy":{}}"""))!!
        assertNull(auto.source)
    }

    @Test
    fun emptyJournalTextsAreInformative() {
        assertTrue(JournalEmptyText.EMPTY.startsWith("Подій ще немає. Вони з"))
        assertEquals("Сервер старої версії — оновіть сервер, щоб бачити журнал.", JournalEmptyText.OLD_SERVER)
        val text = JournalEmptyText.serverEmpty(1_000_000L, 1_000_000L + 3 * 3_600_000L + 10 * 60_000L)
        assertTrue(text, text.endsWith("Журнал сервера ведеться 3 год 10 хв."))
        assertEquals(JournalEmptyText.EMPTY, JournalEmptyText.serverEmpty(null, 5L))
        assertEquals(JournalCategory.SYSTEM, JournalCategory.fromId("system"))
    }
}
