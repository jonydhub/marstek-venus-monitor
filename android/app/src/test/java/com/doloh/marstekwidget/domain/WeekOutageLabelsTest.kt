package com.doloh.marstekwidget.domain

import com.doloh.marstekwidget.settings.AppSettings
import com.doloh.marstekwidget.settings.ConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Same cases as server/tests/test_week_outage_labels.py (web weekDayOutages / weekEnergyLabels / weekSkippedLine). */
class WeekOutageLabelsTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private fun ms(text: String) = java.time.LocalDateTime.parse(text).atZone(kyiv).toInstant().toEpochMilli()
    private fun nb(s: String?) = s?.replace('\u00A0', ' ')
    private fun day(date: String, today: Boolean, vararg spans: Triple<String, String, String>): WeekDay {
        val d = LocalDate.parse(date)
        val start = d.atStartOfDay(kyiv).toInstant().toEpochMilli()
        val end = d.plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli()
        return WeekDay(d, today, start, end, 0, 0, 0, spans.map { (a, b, s) -> LightSpan(ms(a), ms(b), s) })
    }
    private fun outage(a: String, b: String, wh: Double, s0: Int, s1: Int) =
        OutageDetail(ms(a), ms(b), (ms(b) - ms(a)) / 1000, wh, null, s0, s1, false)

    // Ivan's 20.11: off 00:00–00:40 (tail of 19.11 23:50), 08:10–09:20, 09:50–10:05, 21:30–24:00 (→ 21.11 03:00)
    private val d20 = day("2026-11-20", false,
        Triple("2026-11-20T00:00", "2026-11-20T00:40", "off"), Triple("2026-11-20T00:40", "2026-11-20T08:10", "on"),
        Triple("2026-11-20T08:10", "2026-11-20T09:20", "off"), Triple("2026-11-20T09:20", "2026-11-20T09:50", "on"),
        Triple("2026-11-20T09:50", "2026-11-20T10:05", "off"), Triple("2026-11-20T10:05", "2026-11-20T21:30", "on"),
        Triple("2026-11-20T21:30", "2026-11-21T00:00", "off"))
    private val d21 = day("2026-11-21", false,
        Triple("2026-11-21T00:00", "2026-11-21T03:00", "off"), Triple("2026-11-21T03:00", "2026-11-22T00:00", "on"))
    private val stats = listOf(
        outage("2026-11-19T23:50", "2026-11-20T00:40", 90.0, 100, 97),
        outage("2026-11-20T08:10", "2026-11-20T09:20", 420.0, 80, 62),
        outage("2026-11-20T09:50", "2026-11-20T10:05", 60.0, 62, 60),
        outage("2026-11-20T21:30", "2026-11-21T03:00", 900.0, 60, 35),
    )
    private val now = ms("2026-11-22T12:00")
    private fun width7(t: String) = t.length * 7f
    private fun width6(t: String) = t.length * 6f

    @Test fun day20LabelsAndEnergy() {
        val o = WeekOutageLabels.weekDayOutages(d20, stats, now)
        assertEquals(listOf("до 00:40", "", "", ""), o.map { it.label })
        assertEquals(listOf("00:00" to "00:40", "08:10" to "09:20", "09:50" to "10:05", "21:30" to "00:00"), o.map { it.from to it.to })
        assertEquals(listOf(Triple(90.0, 100, 97), Triple(420.0, 80, 62), Triple(60.0, 62, 60), Triple(0.0, null, null)),
            o.map { Triple(it.energyWh, it.socStart, it.socEnd) })
    }

    @Test fun outageAcrossMidnightIsLabelledOnceOnTheLongerPart() {
        val o = WeekOutageLabels.weekDayOutages(d21, stats, now)
        assertEquals(listOf("до 03:00"), o.map { it.label })
        assertEquals(Triple(900.0, 60, 35), o.single().let { Triple(it.energyWh, it.socStart, it.socEnd) })
        assertEquals(emptyList<String>(), WeekOutageLabels.weekEnergyTexts(WeekOutageLabels.weekDayOutages(d20, stats, now)[3]))
    }

    @Test fun narrowBarPlacesBiggestFirstAndListsTheRest() {
        val o = WeekOutageLabels.weekDayOutages(d20, stats, now)
        val l = WeekOutageLabels.weekEnergyLabels(o, 300f) { width7(it) }
        assertEquals(listOf("0,42 кВт·год · 80→62 %"), l.labels.map { nb(it.text) })
        assertEquals("до 00:40 — 0,09 кВт·год · 100→97 %; 09:50–10:05 — 0,06 кВт·год · 62→60 %",
            nb(WeekOutageLabels.weekMissingLine(WeekOutageLabels.weekMissing(o, emptyList(), l.skipped))))
        // with the time labels of the same 300 px bar (7 px/char, gap 3): only 09:50–10:05 has none — already listed
        val unlabeled = ScheduleTimeLabels.unlabeled(o.map { it.segment }, null, null, 0f, 300f, 300f, 3f) { width7(it) }
        assertEquals(listOf("09:50–10:05"), unlabeled.map { it.from + "–" + it.to })
        assertEquals("до 00:40 — 0,09 кВт·год · 100→97 %; 09:50–10:05 — 0,06 кВт·год · 62→60 %",
            nb(WeekOutageLabels.weekMissingLine(WeekOutageLabels.weekMissing(o, unlabeled, l.skipped))))
    }

    @Test fun wideBarPlacesAllLongTexts() {
        val l = WeekOutageLabels.weekEnergyLabels(WeekOutageLabels.weekDayOutages(d20, stats, now), 2400f) { width6(it) }
        assertEquals(listOf("0,09 кВт·год · 100→97 %", "0,42 кВт·год · 80→62 %", "0,06 кВт·год · 62→60 %"), l.labels.map { nb(it.text) })
        assertNull(WeekOutageLabels.weekMissingLine(l.skipped))
    }

    @Test fun middleOutageFallsBackToShortText() {
        val o = listOf(
            WeekOutageLabels.DayOutage(0.00f, 0.05f, "00:00", "01:12", "", 500.0, 90, 70),
            WeekOutageLabels.DayOutage(0.50f, 0.52f, "12:00", "12:29", "", 100.0, 70, 68),
            WeekOutageLabels.DayOutage(0.95f, 1.00f, "22:48", "00:00", "", 400.0, 68, 52),
        )
        val l = WeekOutageLabels.weekEnergyLabels(o, 400f) { width6(it) }
        assertEquals(listOf("0,50 кВт·год · 90→70 %", "0,10 кВт·год", "0,40 кВт·год · 68→52 %"), l.labels.map { nb(it.text) })
        assertTrue(l.skipped.isEmpty())
    }

    @Test fun twoShortAdjacentOutagesOneGoesToTheLine() {
        val o = listOf(
            WeekOutageLabels.DayOutage(0.40f, 0.42f, "09:36", "10:05", "", 200.0, 80, 76),
            WeekOutageLabels.DayOutage(0.43f, 0.45f, "10:19", "10:48", "", 150.0, 76, 73),
        )
        val l = WeekOutageLabels.weekEnergyLabels(o, 300f) { width7(it) }
        assertEquals(listOf("0,20 кВт·год · 80→76 %"), l.labels.map { nb(it.text) })
        assertEquals("10:19–10:48 — 0,15 кВт·год · 76→73 %", nb(WeekOutageLabels.weekMissingLine(WeekOutageLabels.weekMissing(o, emptyList(), l.skipped))))
    }

    // live 20.11: 07:47–08:49 used to steal the room from 10:34–13:52 (start order); longest first keeps 10:34–13:52
    @Test fun live20LongestOutageKeepsItsTimeLabel() {
        val d = day("2026-11-20", false,
            Triple("2026-11-20T00:00", "2026-11-20T06:47", "on"), Triple("2026-11-20T06:47:00", "2026-11-20T06:47:20", "off"),
            Triple("2026-11-20T06:47:20", "2026-11-20T07:47", "on"), Triple("2026-11-20T07:47", "2026-11-20T08:49", "off"),
            Triple("2026-11-20T08:49", "2026-11-20T10:34", "on"), Triple("2026-11-20T10:34", "2026-11-20T13:52", "off"),
            Triple("2026-11-20T13:52", "2026-11-20T21:06", "on"), Triple("2026-11-20T21:06", "2026-11-21T00:00", "off"))
        val st = listOf(outage("2026-11-20T21:06", "2026-11-21T00:15", 595.9, 100, 86),
            outage("2026-11-20T10:34", "2026-11-20T13:52", 499.8, 100, 88), outage("2026-11-20T07:47", "2026-11-20T08:49", 160.1, 100, 96))
        val o = WeekOutageLabels.weekDayOutages(d, st, now)
        assertEquals(listOf("07:47", "10:34", "21:06"), o.map { it.from })
        assertEquals(listOf(62, 198, 174), o.map { it.priority })
        val times = ScheduleTimeLabels.layout(o.map { it.segment }, null, null, 0f, 350f, 350f, 3f) { width6(it) }
        assertTrue(times.map { it.text }.toString(), times.any { it.text == "10:34–13:52" })
        val unlabeled = ScheduleTimeLabels.unlabeled(o.map { it.segment }, null, null, 0f, 350f, 350f, 3f) { width6(it) }
        val l = WeekOutageLabels.weekEnergyLabels(o, 350f) { width6(it) }
        assertEquals(listOf("0,50 кВт·год · 100→88 %", "0,60 кВт·год · 100→86 %"), l.labels.map { nb(it.text) })
        assertEquals("07:47–08:49 — 0,16 кВт·год · 100→96 %", nb(WeekOutageLabels.weekMissingLine(WeekOutageLabels.weekMissing(o, unlabeled, l.skipped))))
        // without the priority (start order) 10:34–13:52 loses its label
        val plain = ScheduleTimeLabels.layout(o.map { it.segment.copy(priority = 0) }, null, null, 0f, 350f, 350f, 3f) { width6(it) }
        assertFalse(plain.any { it.text.startsWith("10:34") })
    }

    @Test fun missingLineWithoutEnergyIsJustTheTime() {
        val o = WeekOutageLabels.DayOutage(0.3f, 0.32f, "07:12", "07:40", "")
        assertEquals("07:12–07:40", WeekOutageLabels.weekMissingLine(listOf(o)))
    }

    @Test fun runningSpanToday() {
        val today = day("2026-11-22", true,
            Triple("2026-11-22T00:00", "2026-11-22T21:05", "on"), Triple("2026-11-22T21:05", "2026-11-22T21:40", "off"))
        assertEquals(listOf("з 21:05"), WeekOutageLabels.weekDayOutages(today, emptyList(), ms("2026-11-22T21:42")).map { it.label })
        // ended 15 min ago → an ordinary span with from/to
        assertEquals(listOf(""), WeekOutageLabels.weekDayOutages(today, emptyList(), ms("2026-11-22T21:55")).map { it.label })
    }

    @Test fun shortSpansIgnored() {
        val d = day("2026-11-20", false, Triple("2026-11-20T05:00", "2026-11-20T05:00:59", "off"), Triple("2026-11-20T05:00:59", "2026-11-21T00:00", "on"))
        assertTrue(WeekOutageLabels.weekDayOutages(d, emptyList(), now).isEmpty())
    }

    @Test fun energyTexts() {
        fun t(wh: Double, a: Int?, b: Int?) = WeekOutageLabels.weekEnergyTexts(WeekOutageLabels.DayOutage(0f, 1f, "", "", "", wh, a, b)).map { nb(it) }
        assertEquals(listOf("0,42 кВт·год · 80→62 %", "0,42 кВт·год"), t(420.0, 80, 62))
        assertEquals(listOf("0,42 кВт·год"), t(420.0, null, null))
        assertEquals(listOf("80→62 %"), t(0.0, 80, 62))
        assertEquals(emptyList<String>(), t(0.0, null, 62))
    }

    @Test fun weekStatsToggleDefaultsPerModeAndExplicitOverride() {
        assertTrue(AppSettings(mode = ConnectionMode.SERVER).weekStatsEnabled)
        assertFalse(AppSettings(mode = ConnectionMode.DIRECT).weekStatsEnabled)
        assertTrue(AppSettings(mode = ConnectionMode.DIRECT, weekStats = true).weekStatsEnabled)
        assertFalse(AppSettings(mode = ConnectionMode.SERVER, weekStats = false).weekStatsEnabled)
    }
}
