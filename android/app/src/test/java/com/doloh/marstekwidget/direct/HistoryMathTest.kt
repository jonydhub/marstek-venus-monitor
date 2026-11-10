package com.doloh.marstekwidget.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryMathTest {
    private val now = 1_800_000_000.0

    private fun series(fromAgoS: Int, minutes: Int, offgrid: Double, stepS: Int = 60, soc: Int = 80) =
        (0..minutes * 60 / stepS).map { i ->
            HistorySample(ts = now - fromAgoS + i * stepS, soc = soc, batWh = 4000.0, flowW = null, flowDir = null,
                offgridW = offgrid, ongridW = 0.0, tempC = 25.0)
        }

    @Test
    fun measuredBackupLoadNeedsThirtyMinutesOfDraw() {
        assertNull(HistoryMath.measuredBackupLoad(series(3600, 20, 300.0), now))
    }

    @Test
    fun measuredBackupLoadAveragesOnlyDrawIntervals() {
        val draw = series(7200, 40, 300.0)
        val idle = series(4000, 60, 0.0)
        assertEquals(300.0, HistoryMath.measuredBackupLoad(draw + idle, now)!!, 0.01)
    }

    @Test
    fun gapsLongerThanMaxGapAreNotBridged() {
        val a = series(7200, 20, 200.0)
        val b = series(3000, 20, 200.0)
        // two separate 20-min draws with a ~50 min gap: 40 min total, still counted separately
        assertEquals(200.0, HistoryMath.measuredBackupLoad(a + b, now)!!, 0.01)
        val sparse = (0..3).map { i -> HistorySample(now - 7200 + i * 2400.0, 80, 4000.0, null, null, 300.0, 0.0, null) }
        assertNull(HistoryMath.measuredBackupLoad(sparse, now))
    }

    @Test
    fun samplesOlderThan24hIgnored() {
        assertNull(HistoryMath.measuredBackupLoad(series(30 * 3600, 120, 300.0), now))
    }

    @Test
    fun socHistoryBucketsToHalfHoursKeepingLatest() {
        val rows = (0..11).map { i -> HistorySample(now - 3 * 3600 + i * 900.0, 50 + i, null, null, null, null, null, null) }
        val points = HistoryMath.socHistory(rows, now)
        assertEquals(points.size, points.map { it.first }.distinct().size)
        assertEquals(61, points.last().second)
        assertEquals(true, points.size in 6..7)
    }

    @Test
    fun insightsJsonHasServerKeys() {
        val rows = series(7200, 90, 120.0)
        val json = HistoryMath.insights(rows, now, currentOffgridW = 118.0, temperatureC = 24.0)
        assertEquals(118.0, json.getDouble("backup_output_w"), 0.0)
        assertEquals(120.0, json.getDouble("backup_avg_w_24h"), 0.01)
        assertEquals(24.0, json.getDouble("temperature_c"), 0.0)
        assertEquals(true, json.getJSONArray("soc_history").length() >= 2)
    }

    @Test
    fun csvCodecRoundTrips() {
        val sample = HistorySample(now, 77, 3942.5, -310.2, "discharging", 305.0, -2.0, 26.5)
        assertEquals(sample, HistorySample.decode(sample.encode()))
        val sparse = HistorySample(now, null, null, null, null, null, null, null)
        assertEquals(sparse, HistorySample.decode(sparse.encode()))
        assertNull(HistorySample.decode("garbage"))
    }
}
