package com.doloh.marstekwidget.domain

import com.doloh.marstekwidget.direct.HistoryMath
import com.doloh.marstekwidget.direct.HistorySample
import com.doloh.marstekwidget.direct.LoadStatsAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class V137TextsAndStatsTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val day = ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, kyiv)
    private fun ms(h: Double) = day.toInstant().toEpochMilli() + (h * 3600_000).toLong()

    @Test
    fun actualRowSegmentsAndHeader() {
        val timeline = listOf(LightSpan(ms(0.0), ms(21.6), "unknown"), LightSpan(ms(21.6), ms(21.78), "off"),
            LightSpan(ms(21.78), ms(21.86), "on"), LightSpan(ms(21.86), ms(22.25), "off"))
        val actual = ActualDays.day(timeline, ms(22.25))!!
        assertEquals("Фактично: Світла нема (з 21:51)", actual.text)
        assertEquals("off", actual.state)
        assertEquals(4, actual.segments.size)
        assertEquals(22.25f / 24f, actual.nowFraction, 1e-4f)
        assertEquals(21.86f / 24f, actual.segments[3].start, 1e-4f)
    }

    @Test
    fun actualRowWithoutFreshDataSaysSo() {
        assertEquals("Фактично: немає даних", ActualDays.day(listOf(LightSpan(ms(8.0), ms(9.0), "on")), ms(22.0))!!.text)
        assertEquals("Фактично: Світло є", ActualDays.day(listOf(LightSpan(ms(0.0), ms(9.0), "on")), ms(9.0))!!.text)
        assertNull(ActualDays.day(null, ms(9.0)))
    }

    @Test
    fun scheduleNowText() {
        val now = day.withHour(10).withMinute(42)
        fun info(current: Pair<Double, Double>? = null, next: Double? = null, published: Boolean = true) = PowerOutageInfo(
            gridState = GridState.ON, group = "1.1", currentKind = current?.let { "schedule" },
            currentStart = current?.let { day.plusMinutes((it.first * 60).toLong()).toOffsetDateTime().toString() },
            currentEnd = current?.let { day.plusMinutes((it.second * 60).toLong()).toOffsetDateTime().toString() },
            nextStart = next?.let { day.plusMinutes((it * 60).toLong()).toOffsetDateTime().toString() }, nextEnd = null,
            nextDurationSeconds = null, schedulePublished = published, runtimeSeconds = null, coversOutage = null, loadBasis = null,
            loadWatts = null, sourceError = null)
        assertEquals("За графіком: відключення до 13:00", ActualDays.scheduleNowText(info(current = 9.0 to 13.0), now))
        assertEquals("За графіком: світло є, відключення о 15:00", ActualDays.scheduleNowText(info(next = 15.0), now))
        assertEquals("За графіком: світло є", ActualDays.scheduleNowText(info(), now))
        assertEquals("За графіком: графік не опубліковано", ActualDays.scheduleNowText(info(published = false), now))
    }

    @Test
    fun chartEventsWithinTwentyMinutesMergeIntoOneCountedMarker() {
        val t0 = ms(21.7792)
        val events = listOf("light_lost" to 0.0, "light_returned" to 4.5, "light_lost" to 5.2, "anomaly_overload_trip" to 21.8,
            "light_returned" to 28.3, "charged_full" to 300.0).map { (k, m) -> ChartEvent(t0 + (m * 60_000).toLong(), k) }
        val x: (Long) -> Float = { ((it - t0).toFloat() / (24 * 3600_000f)) * 282f }
        val clusters = ChartEventClusters.cluster(events, 14f, x = x)
        assertEquals(listOf(5 to "anomaly_overload_trip", 1 to "charged_full"), clusters.map { it.count to it.kind })
        assertEquals(listOf("21:46–22:15 · 5 подій: світло зникло, світло повернулось, світло зникло, захист від перевантаження?, світло повернулось"),
            ChartEventClusters.legend(clusters))
        val wide: (Long) -> Float = { ((it - t0).toFloat() / (24 * 3600_000f)) * 2000f }
        assertEquals(2, ChartEventClusters.cluster(listOf(ChartEvent(t0, "light_lost"), ChartEvent(t0 + 30 * 60_000, "light_returned")), 14f, x = wide).size)
    }

    @Test
    fun todayEnergyInUkrainianUnits() {
        val today = TodayDurations(3600, 600, 0, chargeWh = 810.0, dischargeWh = 150.0, dischargeEstimated = true)
        assertEquals("0,81\u00A0кВт·год", TodayFormatter.energy(810.0))
        assertEquals("Заряджено 0,81\u00A0кВт·год", TodayFormatter.chargedText(today))
        assertEquals("Розряджено ≈\u00A00,15\u00A0кВт·год", TodayFormatter.dischargedText(today))
        assertEquals("Сьогодні: Заряд 1\u00A0год (0,81\u00A0кВт·год) · Розряд 10\u00A0хв (≈\u00A00,15\u00A0кВт·год)",
            TodayFormatter.candidates(today).first())
        assertNull(TodayFormatter.energy(null))
    }

    @Test
    fun measuredEnergyAndEstimateFlag() {
        val midnight = day.toEpochSecond().toDouble()
        val rows = (0 until 2400 step 30).map { HistorySample(midnight + 3600 + it, 80, 4000.0, 1215.0, "charging", 0.0, -1215.0, 25.0) } +
            (0 until 1800 step 30).map { HistorySample(midnight + 6000 + it, 80, 4000.0, -200.0, "discharging", 0.0, 0.0, 25.0) } +
            listOf(HistorySample(midnight + 7800, 80, 4000.0, 0.0, "idle", 0.0, 0.0, 25.0))
        val stats = HistoryMath.todayStats(rows, midnight + 12 * 3600)
        assertEquals(810.0, stats.chargeWh, 1.0)
        assertFalse(stats.chargeEstimated)
        assertEquals(100.0, stats.dischargeWh, 1.0)
        assertTrue(stats.dischargeEstimated)
    }

    @Test
    fun sevenDayLoadStatsExcludeZeroLoadAndOldDays() {
        var raw: String? = null
        raw = LoadStatsAccumulator.add(raw, "2026-11-01", 2000.0, 3600.0, "2026-11-01")
        raw = LoadStatsAccumulator.add(raw, "2026-11-09", 300.0, 3600.0, "2026-11-05")
        raw = LoadStatsAccumulator.add(raw, "2026-11-11", 300.0, 1800.0, "2026-11-05")
        val (mean, covered) = LoadStatsAccumulator.stats(raw, LoadStatsAccumulator.windowStart(java.time.LocalDate.of(2026, 11, 11)))
        assertEquals(400.0, mean!!, 0.5)  // 600 Wh / 1.5 h
        assertEquals(5400.0, covered, 0.1)
        val a = HistorySample(0.0, 50, null, null, null, 300.0, 0.0, null)
        assertEquals(300.0 * 15 / 3600.0, HistoryMath.drawIncrement(a, a.copy(ts = 15.0))!!.first, 1e-6)
        assertNull("zero load is excluded", HistoryMath.drawIncrement(a, a.copy(ts = 15.0, offgridW = 0.0)))
    }

    @Test
    fun smoothedLoadNeedsTwoSamples() {
        val s = HistorySample(100.0, 50, null, null, null, 300.0, 0.0, null)
        assertNull(HistoryMath.smoothedBackupLoad(listOf(s), 100.0, 100.0))
        assertEquals(300.0, HistoryMath.smoothedBackupLoad(listOf(s, s.copy(ts = 115.0)), 115.0, 100.0)!!, 1e-6)
    }
}
