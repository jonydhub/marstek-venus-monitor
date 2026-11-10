package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.direct.BatteryFlow
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.PowerOutageFormatter
import com.doloh.marstekwidget.domain.PowerOutageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class OutageStatusBuilderTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 10, h, m, 0, 0, kyiv)
    private val schedule = OutageSchedule(
        source = OutageSourceKind.YASNO, queue = "1.1",
        windows = listOf(OutageWindow(at(4, 30), at(8, 30)), OutageWindow(at(15), at(22, 30))),
        publishedDates = listOf(LocalDate.of(2026, 11, 10)),
    )

    private fun build(now: ZonedDateTime, grid: String = "on", sched: OutageSchedule? = schedule, load: Double? = null) =
        OutageStatusBuilder.build(
            config = OutageConfig("dnipro", "1.1"), schedule = sched, error = null, now = now, gridState = grid,
            storedWh = 2560.0, flow = null, expectedLoadW = load, measuredLoadW = null, fetchedAt = "2026-11-10T10:00:00+02:00",
        )

    @Test
    fun nextOutageAndSourceAreReported() {
        val info = PowerOutageParser.parse(build(at(10)))!!
        assertEquals(GridState.ON, info.gridState)
        assertEquals("2026-11-10T15:00:00+02:00", info.nextStart)
        assertEquals(27000L, info.nextDurationSeconds)
        assertNull(info.currentEnd)
        assertTrue(info.schedulePublished)
        assertEquals("YASNO", info.source)
        assertEquals("1.1", info.group)
        assertEquals("Відключення 15:00–22:30", PowerOutageFormatter.detail(info, at(10)))
    }

    @Test
    fun currentOutageWhenInsideWindow() {
        val info = PowerOutageParser.parse(build(at(16), grid = "off", load = 640.0))!!
        assertEquals("2026-11-10T22:30:00+02:00", info.currentEnd)
        assertEquals(14400L, info.runtimeSeconds)
        assertEquals(false, info.coversOutage) // 6.5 h left, 4 h reserve
    }

    @Test
    fun maybeWindowsAreReportedSeparately() {
        val withMaybe = schedule.copy(windows = listOf(OutageWindow(at(14, 30), at(15), maybe = true), OutageWindow(at(15), at(22, 30))))
        val info = PowerOutageParser.parse(build(at(10), sched = withMaybe))!!
        assertEquals("2026-11-10T15:00:00+02:00", info.nextStart)
        assertEquals("2026-11-10T14:30:00+02:00", info.maybeStart)
        assertEquals("Можливе відключення 14:30–15:00", PowerOutageFormatter.maybe(info, at(10)))
        val onlyMaybe = PowerOutageParser.parse(build(at(10), sched = schedule.copy(windows = listOf(OutageWindow(at(14, 30), at(15), maybe = true)))))!!
        assertNull(onlyMaybe.nextStart)
        assertEquals("Можливе відключення 14:30–15:00", PowerOutageFormatter.detail(onlyMaybe, at(10)))
    }

    @Test
    fun emergencyIsExposed() {
        val info = PowerOutageParser.parse(build(at(10), sched = schedule.copy(windows = emptyList(), publishedDates = emptyList(), emergency = true)))!!
        assertTrue(info.emergency)
        assertEquals("Аварійні відключення, графік не діє", PowerOutageFormatter.detail(info, at(10)))
    }

    @Test
    fun withoutConfiguredRegionOnlyBackupOutputDecidesLight() {
        val block = OutageStatusBuilder.build(null, null, null, at(10), "off", 2000.0, null, null, null, null)
        val info = PowerOutageParser.parse(block)!!
        assertEquals(GridState.OFF, info.gridState)
        assertFalse(info.scheduleEnabled)
        assertNull(info.source)
        assertEquals("Графік відключень не налаштовано", PowerOutageFormatter.detail(info, at(10)))
    }

    @Test
    fun regionWithoutSourceSaysScheduleUnavailable() {
        val block = OutageStatusBuilder.build(OutageConfig("vinnytsia", "1.1"), null, "no schedule source for region", at(10), "on", 2000.0, null, null, null, null)
        val info = PowerOutageParser.parse(block)!!
        assertTrue(info.scheduleUnavailable)
        assertEquals("Графік недоступний — світло визначається за резервним виходом", PowerOutageFormatter.detail(info, at(10)))
        assertEquals("Вінницька область · черга 1.1", PowerOutageFormatter.groupLine(info, "Вінницька область"))
    }

    @Test
    fun disabledScheduleShowsOneLineOnly() {
        val info = PowerOutageParser.parse(OutageStatusBuilder.build(null, null, null, at(10), "on", 2000.0, null, null, null, null))!!
        assertEquals("Графік відключень не налаштовано", PowerOutageFormatter.detail(info, at(10)))
        assertNull(PowerOutageFormatter.groupLine(info, null))
        val withSource = PowerOutageParser.parse(build(at(10)))!!
        assertEquals("Харківська область · черга 1.1 · Джерело: YASNO", PowerOutageFormatter.groupLine(withSource, "Харківська область"))
    }

    @Test
    fun serverBlockWithoutSourceIsParsed() {
        val block = org.json.JSONObject().put("available", false).put("schedule_unavailable", true).put("grid_state", "off")
        val info = PowerOutageParser.parse(block)!!
        assertEquals(GridState.OFF, info.gridState)
        assertEquals(PowerOutageFormatter.SCHEDULE_UNAVAILABLE, PowerOutageFormatter.detail(info, at(10)))
        assertNull(PowerOutageParser.parse(org.json.JSONObject().put("available", false)))
    }

    @Test
    fun measuredDischargeUsedDuringOutage() {
        val flow = BatteryFlow(true, -500.0, "discharging", 300, 10, null, null, 18000.0)
        val block = OutageStatusBuilder.build(OutageConfig("x", "1.1"), schedule, null, at(16), "off", 2500.0, flow, null, null, null)
        assertEquals("measured_discharge", block.getJSONObject("autonomy").getString("load_basis"))
    }

    @Test
    fun scheduleCodecRoundTrips() {
        val again = OutageScheduleCodec.decode(OutageScheduleCodec.encode(schedule.copy(emergency = true, updatedAt = "u")))
        assertEquals(schedule.copy(emergency = true, updatedAt = "u"), again)
        assertNull(OutageScheduleCodec.decode("{"))
    }
}
