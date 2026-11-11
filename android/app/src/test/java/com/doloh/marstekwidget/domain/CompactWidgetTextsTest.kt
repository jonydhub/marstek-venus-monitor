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
    fun todayLineIn2x2IsOnlyTheNoLightTotalInWords() {
        val c = CompactWidgetTexts.todayCandidates(TodayDurations(chargeSeconds = 3600, dischargeSeconds = 7200, outageSeconds = 60))
        assertEquals("Сьогодні без світла 1\u00A0хв", c.first())
        assertEquals(listOf("Сьогодні без відключень", "Без відключень"),
            CompactWidgetTexts.todayCandidates(TodayDurations(chargeSeconds = 31 * 60L, dischargeSeconds = 36 * 60L, outageSeconds = 0)))
        assertTrue(CompactWidgetTexts.todayCandidates(null).isEmpty())
    }

    @Test
    fun noSingleLetterAbbreviationsAnywhere() {
        val today = TodayDurations(chargeSeconds = 3600, dischargeSeconds = 7500, outageSeconds = 60)
        val all = CompactWidgetTexts.todayCandidates(today) + TodayFormatter.candidates(today)
        val cryptic = Regex("(^|\\s)[ЗР] |\\dг|\\dхв|Без св\\.")
        assertTrue(all.toString(), all.none { cryptic.containsMatchIn(it) })
    }

    @Test
    fun wideTodayLineHasLabelAndFullWords() {
        val today = TodayDurations(chargeSeconds = 3600, dischargeSeconds = 7200, outageSeconds = 60)
        assertEquals("Сьогодні: Заряд 1\u00A0год · Розряд 2\u00A0год · Без світла 1\u00A0хв", TodayFormatter.candidates(today).first())
        assertEquals("Заряд 1\u00A0год · Розряд 2\u00A0год · Без світла 1\u00A0хв", TodayFormatter.compact(today))
        val c = TodayFormatter.candidates(TodayDurations(3840, 7500, 36_180))
        assertEquals("Сьогодні: Заряд 1\u00A0год 4\u00A0хв · Розряд 2\u00A0год 5\u00A0хв · Без світла 10\u00A0год 3\u00A0хв", c[0])
        assertEquals("Сьогодні: Заряд 1\u00A0год · Розряд 2\u00A0год · Без світла 10\u00A0год", c[1])
    }

    @Test
    fun durationsUseFullUnitWords() {
        assertEquals("1\u00A0год", TodayFormatter.short(3600))
        assertEquals("2\u00A0год 5\u00A0хв", TodayFormatter.short(7500))
        assertEquals("1\u00A0хв", TodayFormatter.short(60))
        assertEquals("0\u00A0хв", TodayFormatter.short(0))
        assertEquals("1 год", EtaTextFormatter.formatSeconds(3600))
        assertEquals("3 год 22 хв", EtaTextFormatter.formatSeconds(12_120))
        assertEquals("1 хв", EtaTextFormatter.formatSeconds(60))
    }

    @Test
    fun todayLineFitsAt170dp() {
        val today = TodayDurations(chargeSeconds = 3840, dischargeSeconds = 35_820, outageSeconds = 36_180)
        // 2×2 at 170 dp: 148 dp of text width at 11 sp.
        val picked = TextFit.pick(CompactWidgetTexts.todayCandidates(today), 148f, width(11f))!!
        assertTrue(picked, width(11f)(picked) <= 148f)
        assertTrue(picked, picked.contains("без світла 10\u00A0год") || picked.contains("Без світла 10\u00A0год"))
    }

    @Test
    fun scheduleNotPublishedIsOneShortLine() {
        val s = snapshot(BatteryDirection.IDLE, soc = 100, outage = unpublished)
        val day = ScheduleDays.days(s.powerOutage, nowMillis)[0]
        val row = CompactWidgetTexts.scheduleRow(s.powerOutage, day, now)!!
        assertFalse(row.showBar)
        assertEquals(listOf("Графік не опубліковано", "Графіка нема"), row.captions)
        assertTrue(width(10.5f)(row.captions.last()) < 100f)
    }

    @Test
    fun scheduleRowCaptionsAreShort() {
        val off = snapshot(BatteryDirection.DISCHARGING, power = -420.0, outage = outageOff)
        val offRow = CompactWidgetTexts.scheduleRow(off.powerOutage, ScheduleDays.days(off.powerOutage, nowMillis)[0], now)!!
        // Light off now and the schedule names the end: one text line, no bar.
        assertFalse(offRow.showBar)
        assertEquals("Світло за графіком о 22:00", offRow.caption)
        assertTrue(offRow.captions.last().contains("22:00"))
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
        assertEquals("Заряд ≈ 820\u00A0Вт · 100% за 1\u00A0год 24\u00A0хв", c.first())
        val picked = TextFit.pick(c, 190f, width(12.5f))!!
        assertTrue(picked, picked.startsWith("Заряд ≈ 820\u00A0Вт") && picked.contains("год"))
        assertTrue(width(12.5f)(picked) <= 190f)
        // Narrow: power beats the ETA.
        assertEquals("Заряд ≈ 820\u00A0Вт", TextFit.pick(c, 95f, width(12.5f)))
        val kw = snapshot(BatteryDirection.CHARGING, soc = 72, power = 1200.0, eta = 3600)
        assertTrue(CompactWidgetTexts.stateCandidates(WidgetSummaryResolver.resolve(kw, now), kw, now).contains("Заряд ≈ 1,2\u00A0кВт"))
    }

    private fun outage1330(): BatterySnapshot = BatterySnapshot(
        socPercent = 98, batteryCapacityWh = 5030.0, ratedCapacityWh = 5120.0, temperatureCelsius = 24.0,
        batteryPowerWatts = -1330.0, direction = BatteryDirection.DISCHARGING, etaSeconds = null, collectedAt = null,
        offgridPowerWatts = 1330.0, powerOutageJson = JSONObject("""
            {"available":true,"group":"1.1","grid_state":"off",
             "current_outage":{"kind":"schedule","start":"2026-10-17T18:00:00+03:00","end":"2026-10-17T21:00:00+03:00"},
             "schedule_published":true,"schedule_dates":["2026-10-17"],
             "windows":[{"start":"2026-10-17T18:00:00+03:00","end":"2026-10-17T21:00:00+03:00","kind":"off"}],
             "autonomy":{"load_w":1330.0,"load_basis":"measured_backup_output","runtime_seconds":12091,"usable_wh":4467,
                         "stored_wh":5030,"floor_pct":11,"outage_seconds":7200,"covers_next_outage":true}}
        """.trimIndent()).toString(), insightsJson = null,
    )

    @Test
    fun outagePowerRowAlwaysShowsTheMeasuredLoadThenRuntime() {
        val s = outage1330()
        val c = CompactWidgetTexts.stateCandidates(WidgetSummaryResolver.resolve(s, now), s, now)
        assertEquals("Споживання 1330\u00A0Вт · вистачить 3\u00A0год 22\u00A0хв", c[0])
        assertEquals("1330\u00A0Вт · вистачить 3\u00A0год 22\u00A0хв", c[1])
        assertEquals("1330\u00A0Вт · 3\u00A0год 22\u00A0хв", c[2])
        assertTrue(c.all { it.contains("1330") })
        assertEquals("1330\u00A0Вт · вистачить 3\u00A0год 22\u00A0хв", TextFit.pick(c, 210f, width(12.5f)))
        assertEquals("1330\u00A0Вт · 3\u00A0год 22\u00A0хв", TextFit.pick(c, 140f, width(12.5f)))
    }

    @Test
    fun negativeBackupNoiseIsNeverShownAsLoad() {
        val s = outage1330().copy(offgridPowerWatts = -14.0)
        assertNull(WidgetSummaryResolver.backupLoadWatts(s))
        assertEquals(1330.0, WidgetSummaryResolver.backupLoadWatts(outage1330())!!, 0.0)
    }

    @Test
    fun idleAndDischargingWithGridRows() {
        val idle = snapshot(BatteryDirection.IDLE, soc = 80, outage = onWithNext)
        assertEquals("Очікування", CompactWidgetTexts.stateCandidates(WidgetSummaryResolver.resolve(idle, now), idle, now).last())
        val dis = snapshot(BatteryDirection.DISCHARGING, soc = 80, power = -300.0, outage = onWithNext)
        assertTrue(CompactWidgetTexts.stateCandidates(WidgetSummaryResolver.resolve(dis, now), dis, now).contains("Розряд ≈ 300\u00A0Вт"))
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
