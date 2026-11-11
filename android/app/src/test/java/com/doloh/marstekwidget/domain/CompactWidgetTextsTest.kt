package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CompactWidgetTextsTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val now = ZonedDateTime.of(2026, 10, 17, 19, 0, 0, 0, kyiv)
    private val nowMillis = now.toInstant().toEpochMilli()

    /** Monospace-ish width model for tests: 6.2 dp per char at 12 sp, scaled by size. */
    private fun width(sp: Float): (String) -> Float = { it.length * 6.2f * sp / 12f }

    private val outageOff = JSONObject("""
        {"available":true,"group":"1.1","grid_state":"off",
         "current_outage":{"kind":"schedule","start":"2026-10-17T18:00:00+03:00","end":"2026-10-17T22:00:00+03:00","duration_seconds":14400},
         "schedule_published":true,"schedule_dates":["2026-10-17"],
         "windows":[{"start":"2026-10-17T18:00:00+03:00","end":"2026-10-17T22:00:00+03:00","kind":"off"}],
         "autonomy":{"load_w":420.0,"runtime_seconds":51000,"outage_seconds":10800,"covers_next_outage":true}}
    """.trimIndent()).toString()

    private val unpublished = JSONObject("""
        {"available":true,"group":"1.1","grid_state":"on","current_outage":null,"next_outage":null,
         "schedule_published":false,"schedule_dates":[],"windows":[]}
    """.trimIndent()).toString()

    private val onWithNext = JSONObject("""
        {"available":true,"group":"1.1","grid_state":"on","current_outage":null,
         "next_outage":{"kind":"schedule","start":"2026-10-17T21:00:00+03:00","end":"2026-10-17T23:00:00+03:00","duration_seconds":7200},
         "schedule_published":true,"schedule_dates":["2026-10-17"],
         "windows":[{"start":"2026-10-17T21:00:00+03:00","end":"2026-10-17T23:00:00+03:00","kind":"off"}]}
    """.trimIndent()).toString()

    private fun snapshot(direction: BatteryDirection, soc: Int = 64, power: Double? = null, eta: Long? = null, outage: String? = null, temp: Double? = 27.0) =
        BatterySnapshot(
            socPercent = soc, batteryCapacityWh = soc * 51.2, ratedCapacityWh = 5120.0, temperatureCelsius = temp,
            batteryPowerWatts = power, direction = direction, etaSeconds = eta, collectedAt = null,
            offgridPowerWatts = null, powerOutageJson = outage, insightsJson = null,
        )

    @Test
    fun temperatureIsOneUnbreakableToken() {
        val t = CompactWidgetTexts.temperature(27.04)!!
        assertEquals("27\u00A0°C", t)
        assertFalse("no breakable space", t.contains(' '))
        assertEquals("27,0\u00A0°C", CompactWidgetTexts.temperature(27.0, compact = false))
        assertEquals("-3\u00A0°C", CompactWidgetTexts.temperature(-3.4))
        assertNull(CompactWidgetTexts.temperature(null))
        assertNull(CompactWidgetTexts.temperature(Double.NaN))
    }

    @Test
    fun todayLineKeepsAllThreeNumbersIncludingZero() {
        val today = TodayDurations(chargeSeconds = 31 * 60L, dischargeSeconds = 36 * 60L, outageSeconds = 0)
        val c = CompactWidgetTexts.todayCandidates(today)
        assertEquals("З 31\u00A0хв · Р 36\u00A0хв · Без світла 0", c.first())
        assertTrue(c.all { it.contains("31") && it.contains("36") && it.endsWith(" 0") })
        assertTrue(CompactWidgetTexts.todayCandidates(null).isEmpty())
    }

    @Test
    fun todayLineShortensLabelsNotNumbersAt170dp() {
        val today = TodayDurations(chargeSeconds = 3840, dischargeSeconds = 35_820, outageSeconds = 36_180)
        // 2×2 at 170 dp: 148 dp of text width at 11 sp.
        val picked = TextFit.pick(CompactWidgetTexts.todayCandidates(today), 148f, width(11f))!!
        assertTrue(picked, width(11f)(picked) <= 148f)
        // Every candidate keeps the three numbers; only labels and minute precision shrink.
        assertTrue(picked, picked.startsWith("З 1г") && picked.contains("Р ") && picked.contains("Без св"))
    }

    @Test
    fun scheduleNotPublishedIsOneShortLine() {
        val s = snapshot(BatteryDirection.IDLE, soc = 100, outage = unpublished)
        val day = ScheduleDays.days(s.powerOutage, nowMillis)[0]
        val row = CompactWidgetTexts.scheduleRow(s.powerOutage, day, now)!!
        assertFalse(row.showBar)
        assertEquals("Графік: немає", row.caption)
        assertTrue(width(10.5f)(row.caption!!) < 100f)
    }

    @Test
    fun scheduleRowCaptionsAreShort() {
        val off = snapshot(BatteryDirection.DISCHARGING, power = -420.0, outage = outageOff)
        val offRow = CompactWidgetTexts.scheduleRow(off.powerOutage, ScheduleDays.days(off.powerOutage, nowMillis)[0], now)!!
        assertTrue(offRow.showBar)
        assertEquals("до 22:00", offRow.caption)
        val on = snapshot(BatteryDirection.IDLE, outage = onWithNext)
        assertEquals("відкл. 21:00", CompactWidgetTexts.scheduleRow(on.powerOutage, ScheduleDays.days(on.powerOutage, nowMillis)[0], now)!!.caption)
        assertNull(CompactWidgetTexts.scheduleRow(null, null, now))
    }

    @Test
    fun stateLineIdleFullSaysCharged() {
        val s = snapshot(BatteryDirection.IDLE, soc = 100, outage = unpublished)
        val c = CompactWidgetTexts.stateCandidates(WidgetSummaryResolver.resolve(s, now), s, now)
        assertEquals("Очікування · Заряджено", c.first())
    }

    @Test
    fun stateLineOutageCarriesVerdictAndRuntimeInEveryCandidate() {
        val s = snapshot(BatteryDirection.DISCHARGING, power = -420.0, outage = outageOff)
        val c = CompactWidgetTexts.stateCandidates(WidgetSummaryResolver.resolve(s, now), s, now)
        assertEquals("Вистачить ≈ 14\u00A0год 10\u00A0хв", c.first())
        assertTrue(c.all { it.startsWith("Вистачить") && it.contains("14") })
        val picked = TextFit.pick(c, 148f, width(12.5f))!!
        assertTrue(width(12.5f)(picked) <= 148f)
    }

    @Test
    fun stateLineChargingFitsWithoutLosingTheEta() {
        val s = snapshot(BatteryDirection.CHARGING, soc = 72, power = 820.0, eta = 5040)
        val c = CompactWidgetTexts.stateCandidates(WidgetSummaryResolver.resolve(s, now), s, now)
        assertEquals("Заряд 0,82\u00A0кВт · 100% за 1\u00A0год 24\u00A0хв", c.first())
        val picked = TextFit.pick(c, 148f, width(12.5f))!!
        assertTrue(picked, picked.contains("100%") && picked.contains("год"))
        assertTrue(width(12.5f)(picked) <= 148f)
    }

    @Test
    fun headerKeepsTemperatureOnOneLineBeforeTheRefreshButton() {
        val w = width(13f)
        fun pick(headline: String, room: Float, important: Boolean = false) = CompactWidgetTexts.header(
            CompactWidgetTexts.headlineCandidates(headline), hasTemperature = true, hasTime = true, timeImportant = important,
            roomDp = room, headlineDp = w, temperatureDp = 34f, timeDp = 32f, glyphDp = 10f, refreshButtonDp = 22f,
        )
        // Roomy: everything incl. the button.
        assertEquals(CompactWidgetTexts.HeaderChoice("Світло є", true, true, true), pick("Світло є", 160f))
        // 2×2 at 170 dp with «Світла нема» (≈ 72 dp): the button folds into a «↻» on the time, temperature stays.
        assertEquals(CompactWidgetTexts.HeaderChoice("Світла нема", true, true, false), pick("Світла нема", 152f))
        // Very narrow: temperature before time when data are fresh…
        assertEquals(CompactWidgetTexts.HeaderChoice("Світла нема", true, false, false), pick("Світла нема", 120f))
        // …but a stale/offline time beats temperature (it then joins the state row).
        assertEquals(CompactWidgetTexts.HeaderChoice("Світла нема", false, true, true), pick("Світла нема", 135f, important = true))
        // The long no-link headline shortens instead of wrapping.
        assertEquals("Немає зв'язку", pick(LightStatusResolver.NO_LINK, 150f, important = true).headline)
    }

    @Test
    fun textFitFallsBackToTheShortestCandidate() {
        assertEquals("ab", TextFit.pick(listOf("abcdef", "abcd", "ab"), 1f) { it.length.toFloat() })
        assertEquals("abcd", TextFit.pick(listOf("abcdef", "abcd", "ab"), 4f) { it.length.toFloat() })
        assertNull(TextFit.pick(emptyList(), 4f) { 0f })
    }

    @Test
    fun noLinkHeadlineHasAShortForm() {
        val c = CompactWidgetTexts.headlineCandidates(LightStatusResolver.NO_LINK)
        assertEquals(LightStatusResolver.NO_LINK, c.first())
        assertTrue(c.last().length <= 13)
        assertEquals(listOf("Світло є"), CompactWidgetTexts.headlineCandidates("Світло є"))
    }

    @Test
    fun ringColourFollowsTheBarGradient() {
        assertEquals(0xFFEF634D.toInt(), SocGradient.colorAt(0))
        assertEquals(0xFF35C9DA.toInt(), SocGradient.colorAt(100))
        assertEquals(0xFFF5BE28.toInt(), SocGradient.colorAt(42))
        assertEquals(0xFF8FD18A.toInt(), SocGradient.colorAt(72))
        // Clamped, opaque.
        assertEquals(SocGradient.colorAt(100), SocGradient.colorAt(150))
        assertEquals(0xFF, SocGradient.colorAt(57) ushr 24)
    }

    @Test
    fun compactPlanFillsSamsungCellsWithoutOverflow() {
        for ((w, h) in listOf(170f to 200f, 180f to 230f, 178f to 186f, 165f to 150f)) {
            val p = CompactPlanner.plan(w, h, hasToday = true, hasSchedule = true)
            assertTrue("$w×$h overflows: ${p.contentDp} > ${p.innerHeightDp}", p.contentDp <= p.innerHeightDp + 0.5f)
            assertTrue("$w×$h leaves a band: ${p.contentDp}/${p.innerHeightDp}", p.contentDp >= 0.9f * p.innerHeightDp)
            assertTrue(p.ringDp <= w - CompactPlanner.PAD_H)
        }
        val tall = CompactPlanner.plan(170f, 200f, hasToday = true, hasSchedule = true)
        assertTrue(tall.showToday && tall.showSchedule)
        assertTrue("ring ${tall.ringDp}", tall.ringDp >= 80f)
        // Taller cell → bigger ring and type.
        val taller = CompactPlanner.plan(180f, 230f, hasToday = true, hasSchedule = true)
        assertTrue(taller.ringDp > tall.ringDp && taller.stateSp > tall.stateSp)
    }

    @Test
    fun compactPlanDropsLowPriorityRowsInTinyCells() {
        val p = CompactPlanner.plan(110f, 110f, hasToday = true, hasSchedule = true)
        assertFalse(p.showSchedule)
        assertTrue(p.contentDp <= p.innerHeightDp + 0.5f)
        assertNotNull(p)
    }
}
