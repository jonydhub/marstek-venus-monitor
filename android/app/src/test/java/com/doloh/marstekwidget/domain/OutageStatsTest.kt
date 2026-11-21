package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

/** «Фактично» outage labels + energy line, /api/stats parsing and «Статистика» texts (same cases as the web). */
class OutageStatsTest {
    private val nb = '\u00A0'
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    private fun outage(a: String, b: String, wh: Double? = null, ongoing: Boolean = false, avg: Int? = null, s0: Int? = null, s1: Int? = null) =
        OutageDetail(ms(a), ms(b), (ms(b) - ms(a)) / 1000, wh, avg, s0, s1, ongoing)

    private val night = outage("2026-11-21T00:00:00+02:00", "2026-11-21T00:15:27+02:00", 35.2)
    private val day = outage("2026-11-21T15:58:30+02:00", "2026-11-21T16:35:09+02:00", 346.9)

    private fun layout(segments: List<ScheduleBarModel.Segment>, width: Float = 240f) =
        ScheduleTimeLabels.layout(segments, null, null, 0f, width, width, 4f) { it.length * 6f }.map { it.text }

    @Test
    fun outageFromMidnightReadsUntilAndShortOneMerges() {
        val segs = ActualOutageLabels.segments(listOf(night, day), ms("2026-11-21T17:30:00+02:00"))
        assertEquals(listOf("до 00:15", ""), segs.map { it.label })
        assertEquals(listOf("до 00:15", "15:58–16:35"), layout(segs))
    }

    @Test
    fun ongoingOutageReadsSince() {
        val now = ms("2026-11-21T16:36:00+02:00")
        assertEquals(listOf("з 15:58"), layout(ActualOutageLabels.segments(listOf(day.copy(ongoing = true)), now)))
        // end ≈ now counts as ongoing even without the flag
        assertEquals("з 15:58", ActualOutageLabels.segments(listOf(day), now).single().label)
    }

    @Test
    fun longOutageGetsEdgeLabels() {
        val long = outage("2026-11-21T06:00:00+02:00", "2026-11-21T14:00:00+02:00")
        assertEquals(listOf("06:00", "14:00"), layout(ActualOutageLabels.segments(listOf(long), ms("2026-11-21T20:00:00+02:00"))))
    }

    @Test
    fun fixedLabelFallsBackToFlushRightWhenCentreIsTaken() {
        // the neighbour's merged «02:24–07:12» (15..81 px) blocks the centred and flush-left spots
        val blocker = ScheduleBarModel.Segment(0.10f, 0.30f, false, "02:24", "07:12")
        val fixed = ScheduleBarModel.Segment(0.30f, 0.55f, false, "07:12", "13:12", label = "з 07:12")
        val labels = ScheduleTimeLabels.layout(listOf(blocker, fixed), null, null, 0f, 240f, 240f, 4f) { it.length * 6f }
        assertEquals(listOf("02:24–07:12", "з 07:12"), labels.map { it.text })
        assertEquals(132f, labels[1].right, 0.01f)  // flush with the block's right edge
        // with room it is centred
        val alone = ScheduleTimeLabels.layout(listOf(fixed), null, null, 0f, 240f, 240f, 4f) { it.length * 6f }.single()
        assertEquals(102f, alone.center, 0.01f)
    }

    @Test
    fun energyLineUnderTheAxis() {
        assertEquals("Без світла 52${nb}хв · зі станції 0,38${nb}кВт·год", ActualOutageLabels.energyLine(listOf(night, day), 382.1))
        assertEquals("Без світла 37${nb}хв", ActualOutageLabels.energyLine(listOf(day), 0.0))
        assertEquals("Без світла 37${nb}хв", ActualOutageLabels.energyLine(listOf(day), null))
        assertNull(ActualOutageLabels.energyLine(emptyList(), 100.0))
    }

    @Test
    fun actualDayCarriesLabelsAndLine() {
        val timeline = listOf(LightSpan(night.startMillis, night.endMillis, "off"), LightSpan(night.endMillis, day.startMillis, "on"),
            LightSpan(day.startMillis, day.endMillis, "off"), LightSpan(day.endMillis, ms("2026-11-21T17:30:00+02:00"), "on"))
        val now = ms("2026-11-21T17:30:00+02:00")
        val actual = ActualDays.day(timeline, now, listOf(night, day), 382.1)!!
        assertEquals(2, actual.outageLabels.size)
        assertTrue(actual.energyLine!!.startsWith("Без світла 52"))
        // older server without outages: labels from the timeline, no energy
        val old = ActualDays.day(timeline, now)!!
        assertEquals(listOf("до 00:15", ""), old.outageLabels.map { it.label })
        assertEquals("Без світла 52${nb}хв", old.energyLine)
    }

    @Test
    fun insightsParseTodayOutages() {
        val block = JSONObject("""{"today":{"outage_energy_wh":382.1,"grid_timeline":[],"outages":[
            {"start":"2026-11-21T15:58:30+02:00","end":"2026-11-21T16:35:09+02:00","seconds":2199,"energy_wh":346.9,"avg_w":568,
             "soc_start":99,"soc_end":null,"ongoing":false}]}}""")
        val ins = BatteryInsightsParser.parse(block)!!
        assertEquals(382.1, ins.todayOutageEnergyWh!!, 0.01)
        val o = ins.todayOutages!!.single()
        assertEquals(2199L, o.seconds)
        assertEquals(568, o.avgW)
        assertNull(o.socEnd)
        assertNull(BatteryInsightsParser.parse(JSONObject("""{"today":{}}"""))!!.todayOutages)
    }

    private val statsJson = """{"days":[
        {"date":"2026-11-21","is_today":true,"off_seconds":3126,"outages":1,"outage_energy_wh":382.1,"charge_wh":1999.7,"discharge_wh":570.4},
        {"date":"2026-11-20","is_today":false,"off_seconds":26031,"outages":3,"outage_energy_wh":1220.7,"charge_wh":1631.7,"discharge_wh":1403.0},
        {"date":"2026-11-19","is_today":false,"off_seconds":0,"outages":0,"outage_energy_wh":0.0,"charge_wh":966.5,"discharge_wh":256.1}],
      "outages":[
        {"start":"2026-11-21T15:58:30+02:00","end":"2026-11-21T16:35:09+02:00","seconds":2199,"energy_wh":346.9,"avg_w":568,"soc_start":99,"soc_end":92,"ongoing":false},
        {"start":"2026-11-20T21:06:57+02:00","end":"2026-11-21T00:15:27+02:00","seconds":11310,"energy_wh":595.9,"avg_w":190,"soc_start":100,"soc_end":86,"ongoing":false}],
      "charges":[
        {"start":"2026-11-21T16:35:09+02:00","end":"2026-11-21T17:01:00+02:00","seconds":1551,"energy_wh":477.0,"avg_w":1095,"soc_start":92,"soc_end":100,"estimated":false,"ongoing":false},
        {"start":"2026-11-21T09:00:00+02:00","end":"2026-11-21T09:30:00+02:00","seconds":1800,"energy_wh":150.0,"avg_w":null,"soc_start":null,"soc_end":null,"estimated":true,"ongoing":false}],
      "generated_at":"2026-11-21T17:30:00+00:00"}"""

    @Test
    fun parsesStats() {
        val st = StatsParser.parse(JSONObject(statsJson))
        assertEquals(3, st.days.size)
        assertEquals(LocalDate.of(2026, 11, 21), st.days[0].date)
        assertTrue(st.days[0].isToday)
        assertEquals(26031L, st.days[1].offSeconds)
        assertEquals(3, st.days[1].outages)
        assertEquals(2, st.outages.size)
        assertEquals(100, st.outages[1].socStart)
        assertTrue(st.charges[1].estimated)
        assertNull(st.charges[1].avgW)
        assertTrue(StatsParser.parse(JSONObject("{}")).days.isEmpty())
    }

    @Test
    fun tilesAndPlural() {
        val tiles = StatsTexts.tiles(StatsParser.parse(JSONObject(statsJson)))
        assertEquals(listOf("Без світла", "Зі станції", "Заряджено", "Найдовше"), tiles.map { it.title })
        assertEquals("8${nb}год 6${nb}хв", tiles[0].value)
        assertEquals("2 відключення", tiles[0].sub)
        assertEquals("1,60${nb}кВт·год", tiles[1].value)
        assertEquals("4,60${nb}кВт·год", tiles[2].value)
        assertEquals("3${nb}год 9${nb}хв", tiles[3].value)
        assertEquals("Пт 20.11, 21:06", tiles[3].sub)
        assertEquals("—", StatsTexts.tiles(StatsReport(emptyList(), emptyList(), emptyList()))[3].sub)
        assertEquals(listOf("0 відключень", "1 відключення", "4 відключення", "5 відключень", "11 відключень", "14 відключень",
            "21 відключення", "25 відключень"), listOf(0, 1, 4, 5, 11, 14, 21, 25).map(StatsTexts::outagesCount))
    }

    @Test
    fun dayRows() {
        val st = StatsParser.parse(JSONObject(statsJson))
        val today = LocalDate.of(2026, 11, 21)
        assertEquals(listOf("Сьогодні", "Вчора", "Чт, 19.11"), st.days.map { StatsTexts.dayTitle(it, today) })
        assertEquals("без світла 7${nb}год 14${nb}хв · зі станції 1,22 · заряд 1,63${nb}кВт·год", StatsTexts.dayLine(st.days[1]))
        assertEquals("без відключень · заряд 0,97${nb}кВт·год", StatsTexts.dayLine(st.days[2]))
    }

    @Test
    fun outageAndChargeLines() {
        val st = StatsParser.parse(JSONObject(statsJson))
        assertEquals("Пт 20.11 · 21:06–00:15 · 3${nb}год 9${nb}хв" to "0,60${nb}кВт·год зі станції · ≈190${nb}Вт · заряд 100 → 86${nb}%",
            StatsTexts.outageLines(st.outages[1]))
        val ongoing = outage("2026-11-21T15:58:00+02:00", "2026-11-21T16:35:00+02:00", ongoing = true)
        assertEquals("Сб 21.11 · з 15:58 · 37${nb}хв · триває" to "", StatsTexts.outageLines(ongoing))
        assertEquals("Сб 21.11 · 16:35–17:01 · 26${nb}хв" to "+0,48${nb}кВт·год · ≈1095${nb}Вт · 92 → 100${nb}%", StatsTexts.chargeLines(st.charges[0]))
        assertEquals("≈${nb}+0,15${nb}кВт·год", StatsTexts.chargeLines(st.charges[1]).second)
    }
}
