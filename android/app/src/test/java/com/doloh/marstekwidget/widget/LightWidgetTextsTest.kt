package com.doloh.marstekwidget.widget

import com.doloh.marstekwidget.domain.BatteryDirection
import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.LightKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Content of the standalone «Світло» widget: headline, «вже …» duration and the schedule line per state. */
class LightWidgetTextsTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val now = ZonedDateTime.of(2026, 11, 10, 20, 15, 0, 0, kyiv)
    private val nb = '\u00A0'

    private fun snapshot(
        grid: String,
        current: String = "null",
        next: String = "null",
        published: Boolean = true,
        outageSince: String? = null,
        lightSince: String? = null,
        autonomy: String = "{}",
        linkLost: Boolean = false,
        group: String? = "1.1",
        scheduleEnabled: Boolean = true,
    ): BatterySnapshot {
        fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"
        return BatterySnapshot(
            socPercent = 63, batteryCapacityWh = 3226.0, ratedCapacityWh = 5120.0, temperatureCelsius = 24.0,
            batteryPowerWatts = null, direction = BatteryDirection.IDLE, etaSeconds = null, collectedAt = null,
            powerOutageJson = """{"available":true,"group":${q(group)},"grid_state":"$grid","source_name":"Львівобленерго",
                "schedule_enabled":$scheduleEnabled,"current_outage":$current,"next_outage":$next,"schedule_published":$published,
                "autonomy":$autonomy,"grid_detail":{"outage_since":${q(outageSince)},"light_since":${q(lightSince)}}}""",
            batteryLinkLost = linkLost,
        )
    }

    private val scheduleNow = """{"kind":"schedule","start":"2026-11-10T18:00:00+02:00","end":"2026-11-10T21:00:00+02:00"}"""
    private val nextTonight = """{"kind":"schedule","start":"2026-11-10T22:00:00+02:00","end":"2026-11-11T00:00:00+02:00"}"""

    @Test
    fun elapsedUsesFullUnitWordsWithNoBreakSpacesAndNoZeroMinutes() {
        assertEquals("2${nb}год 15${nb}хв", LightWidgetTexts.elapsed(2 * 3600L + 15 * 60))
        assertEquals("1${nb}год", LightWidgetTexts.elapsed(3600L + 20))
        assertEquals("45${nb}хв", LightWidgetTexts.elapsed(45 * 60L + 59))
        assertEquals("1${nb}д 3${nb}год", LightWidgetTexts.elapsed(27 * 3600L + 40 * 60))
        assertEquals("2${nb}д", LightWidgetTexts.elapsed(48 * 3600L + 10 * 60))
        assertEquals("щойно", LightWidgetTexts.elapsed(30L))
        assertNull(LightWidgetTexts.elapsed(null))
        assertNull(LightWidgetTexts.elapsed(-5L))
    }

    @Test
    fun offWithScheduleShowsHowLongAndTheScheduledReturn() {
        val c = LightWidgetTexts.content(snapshot("off", current = scheduleNow, outageSince = "2026-11-10T18:00:00+02:00",
            autonomy = """{"runtime_seconds":245000,"covers_next_outage":true}"""), linkLost = false, now = now)
        assertEquals(LightKind.OFF, c.kind)
        assertEquals("Світла нема", c.headlines.first())
        assertEquals(listOf("вже 2${nb}год 15${nb}хв", "2${nb}год 15${nb}хв"), c.durations)
        // Same formatter as the app: a scheduled window that is really off.
        assertEquals(listOf("Світло за графіком о 21:00", "За графіком о 21:00"), c.next)
        assertEquals("Запас ≈ 2 д 20 год · вистачить", c.reserve)
        assertEquals(true, c.reserveCovers)
        assertEquals("Черга 1.1", c.group.last())
    }

    @Test
    fun offWithoutScheduleSaysTheReturnIsUnknownNeverANextOutage() {
        val c = LightWidgetTexts.content(snapshot("off", next = nextTonight, outageSince = "2026-11-10T19:30:00+02:00"), false, now)
        assertEquals(listOf("вже 45${nb}хв", "45${nb}хв"), c.durations)
        assertEquals(listOf("Час повернення невідомий", "Повернення невідоме"), c.next)
        assertFalse(c.next.any { it.startsWith("Відключення") })
    }

    @Test
    fun onWithNextOutageGivesTheWindowRangeFirstThenTheStart() {
        val c = LightWidgetTexts.content(snapshot("on", next = nextTonight, lightSince = "2026-11-10T07:05:00+02:00"), false, now)
        assertEquals(LightKind.ON, c.kind)
        assertEquals("Світло є", c.headlines.first())
        assertEquals("вже 13${nb}год 10${nb}хв", c.durations.first())
        assertEquals(listOf("Відключення о 22:00–24:00", "Відкл. о 22:00–24:00", "Відключення о 22:00", "Відкл. о 22:00"), c.next)
    }

    @Test
    fun onDuringAScheduledWindowFollowsMeasuredLightAndDropsTheLightOnTail() {
        val c = LightWidgetTexts.content(snapshot("on", current = scheduleNow), false, now)
        assertEquals(listOf("За графіком відключення до 21:00", "Графік: відключення до 21:00", "Графік: відкл. до 21:00"), c.next)
        assertTrue(c.durations.isEmpty())
    }

    @Test
    fun onWithNoOutageToday() {
        val c = LightWidgetTexts.content(snapshot("on", lightSince = "2026-11-10T20:00:00+02:00"), false, now)
        assertEquals(listOf("За графіком без відключень", "Графік: без відключень"), c.next)
        assertEquals("вже 15${nb}хв", c.durations.first())
    }

    @Test
    fun lostLinkNeverClaimsNoLightAndHidesTheDuration() {
        val c = LightWidgetTexts.content(snapshot("off", current = scheduleNow, outageSince = "2026-11-10T18:00:00+02:00",
            autonomy = """{"runtime_seconds":245000,"covers_next_outage":true}"""), linkLost = true, now = now)
        assertEquals(LightKind.NO_LINK, c.kind)
        assertEquals(listOf("Немає зв'язку з батареєю", "Немає зв'язку"), c.headlines)
        assertTrue(c.headlines.none { it.contains("нема", ignoreCase = true) && it.contains("Світл") })
        assertTrue(c.durations.isEmpty())
        assertEquals("За графіком зараз відключення", c.next.first())
        assertNull(c.reserve)
    }

    @Test
    fun serverReportingTheBatteryUnreachableIsAlsoNoLink() {
        val c = LightWidgetTexts.content(snapshot("unknown", linkLost = true), false, now)
        assertEquals(LightKind.NO_LINK, c.kind)
    }

    @Test
    fun unknownGridStateIsNamedPlainly() {
        val c = LightWidgetTexts.content(snapshot("unknown"), false, now)
        assertEquals(LightKind.UNKNOWN, c.kind)
        assertEquals("Стан світла невідомий", c.headlines.first())
        assertTrue(c.durations.isEmpty())
    }

    @Test
    fun aFutureOrMissingSinceHidesTheDuration() {
        assertTrue(LightWidgetTexts.content(snapshot("off", outageSince = "2026-11-10T21:00:00+02:00"), false, now).durations.isEmpty())
        assertTrue(LightWidgetTexts.content(snapshot("off"), false, now).durations.isEmpty())
        // light_since belongs to «on», never shown while off
        assertTrue(LightWidgetTexts.content(snapshot("off", lightSince = "2026-11-10T07:00:00+02:00"), false, now).durations.isEmpty())
    }

    @Test
    fun groupLineUsesTheQueueAndSource() {
        val c = LightWidgetTexts.content(snapshot("on"), false, now)
        assertEquals(listOf("Черга 1.1 · Львівобленерго", "Черга 1.1"), c.group)
        assertTrue(LightWidgetTexts.content(snapshot("on", group = null, scheduleEnabled = false), false, now).group.isEmpty())
    }

    @Test
    fun noPowerOutageBlockIsUnknownWithoutSchedule() {
        val bare = snapshot("on").copy(powerOutageJson = null)
        val c = LightWidgetTexts.content(bare, false, now)
        assertEquals(LightKind.UNKNOWN, c.kind)
        assertTrue(c.next.isEmpty())
    }

    // ---- strip text fitting (measure = 6 dp per character) ----

    private val perChar: (String) -> Float = { it.length * 6f }

    @Test
    fun wideStripPutsHeadlineAndDurationOnOneLineAndTheScheduleUnderIt() {
        val c = LightWidgetTexts.content(snapshot("off", current = scheduleNow, outageSince = "2026-11-10T18:00:00+02:00"), false, now)
        val s = LightWidgetTexts.strip(c, widthDp = 300f, twoLines = true, measure = perChar)
        assertEquals("Світла нема · вже 2${nb}год 15${nb}хв", s.primary)
        assertEquals("Світло за графіком о 21:00", s.secondary)
    }

    @Test
    fun narrowStripDropsTheWordAlreadyBeforeMovingTheDurationDown() {
        val c = LightWidgetTexts.content(snapshot("off", current = scheduleNow, outageSince = "2026-11-10T18:00:00+02:00"), false, now)
        // «Світла нема · 2 год 15 хв» = 25 chars = 150 dp
        assertEquals("Світла нема · 2${nb}год 15${nb}хв", LightWidgetTexts.strip(c, 155f, twoLines = false, measure = perChar).primary)
        // too narrow for both: headline on line 1, the duration on line 2 (never the schedule instead of the duration)
        val two = LightWidgetTexts.strip(c, 100f, twoLines = true, measure = perChar)
        assertEquals("Світла нема", two.primary)
        assertEquals("вже 2${nb}год 15${nb}хв", two.secondary)
    }

    @Test
    fun stripWithoutDurationShowsTheScheduleLine() {
        val c = LightWidgetTexts.content(snapshot("on", next = nextTonight), false, now)
        val s = LightWidgetTexts.strip(c, 300f, twoLines = true, measure = perChar)
        assertEquals("Світло є", s.primary)
        assertEquals("Відключення о 22:00–24:00", s.secondary)
    }

    @Test
    fun noLinkStripPicksTheShortHeadlineWhenNarrow() {
        val c = LightWidgetTexts.content(snapshot("off"), linkLost = true, now = now)
        assertEquals("Немає зв'язку", LightWidgetTexts.strip(c, 100f, twoLines = false, measure = perChar).primary)
    }

    @Test
    fun fitShrinksTypeBeforeFallingBackToAShorterCandidate() {
        val measure: (String, Float) -> Float = { text, sp -> text.length * sp * 0.5f }
        // «вже 2 год 15 хв» = 15 chars: 24 sp → 180 dp, 20 sp → 150 dp
        assertEquals("вже 2${nb}год 15${nb}хв" to 20f, LightWidgetFit.fit(listOf("вже 2${nb}год 15${nb}хв", "2${nb}год 15${nb}хв"), 150f, 24f, 18f, measure))
        // below the minimum size the shorter candidate wins at the largest size that fits it
        assertEquals("2${nb}год 15${nb}хв" to 22f, LightWidgetFit.fit(listOf("вже 2${nb}год 15${nb}хв", "2${nb}год 15${nb}хв"), 121f, 24f, 18f, measure))
        assertNull(LightWidgetFit.fit(emptyList(), 100f, 24f, 18f, measure))
    }
}
