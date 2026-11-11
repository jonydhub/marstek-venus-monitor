package com.doloh.marstekwidget.direct

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** One stored poll; [ts] = epoch seconds. Mirrors the server's SQLite `samples` row. */
data class HistorySample(
    val ts: Double,
    val soc: Int?,
    val batWh: Double?,
    val flowW: Double?,
    val flowDir: String?,
    val offgridW: Double?,
    val ongridW: Double?,
    val tempC: Double?,
) {
    fun encode(): String = listOf(ts, soc, batWh, flowW, flowDir, offgridW, ongridW, tempC)
        .joinToString(",") { it?.toString() ?: "" }

    companion object {
        fun decode(line: String): HistorySample? {
            val p = line.split(',')
            if (p.size != 8) return null
            val ts = p[0].toDoubleOrNull() ?: return null
            return HistorySample(ts, p[1].toIntOrNull(), p[2].toDoubleOrNull(), p[3].toDoubleOrNull(),
                p[4].ifBlank { null }, p[5].toDoubleOrNull(), p[6].toDoubleOrNull(), p[7].toDoubleOrNull())
        }
    }
}

/**
 * Port of server history.py. Differences: MAX_GAP is 20 min (not 5) because a phone polls every ~15 min in the
 * background; longer gaps are still never bridged.
 */
object HistoryMath {
    const val RETENTION_SECONDS = 48 * 3600.0
    const val WINDOW_SECONDS = 24 * 3600.0
    const val MAX_GAP_SECONDS = 1200.0
    const val MIN_AVG_COVERAGE_SECONDS = 3600.0
    const val MIN_DRAW_COVERAGE_SECONDS = 1800.0
    const val BACKUP_DRAW_THRESHOLD_W = 5.0
    const val SOC_BUCKET_SECONDS = 1800.0
    const val SOC_MAX_POINTS = 48
    const val STANDBY_MAX_INTERVAL_SECONDS = 6 * 3600.0
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    private data class Interval(val t0: Double, val t1: Double, val v0: Double, val v1: Double)

    private fun intervals(rows: List<HistorySample>, start: Double): List<Interval> {
        val points = rows.filter { it.ts >= start && it.offgridW?.isFinite() == true }
            .map { it.ts to it.offgridW!! }.sortedBy { it.first }
        return points.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.first - a.first
            if (dt > 0 && dt <= MAX_GAP_SECONDS) Interval(a.first, b.first, a.second, b.second) else null
        }
    }

    private fun integral(list: List<Interval>): Pair<Double, Double> {
        var energyWs = 0.0
        var covered = 0.0
        list.forEach { energyWs += (abs(it.v0) + abs(it.v1)) / 2.0 * (it.t1 - it.t0); covered += it.t1 - it.t0 }
        return energyWs to covered
    }

    /** Mean backup-output draw over 24 h, only while it was actually drawing; null before 30 min of draw. */
    fun measuredBackupLoad(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): Double? {
        val draw = intervals(rows, now - WINDOW_SECONDS).filter { abs(it.v0) >= thresholdW && abs(it.v1) >= thresholdW }
        val (energy, covered) = integral(draw)
        if (covered < MIN_DRAW_COVERAGE_SECONDS) return null
        return Math.round(energy / covered * 10.0) / 10.0
    }

    /** (epochSeconds, soc) – latest sample per 30-min bucket over the last 24 h. */
    fun socHistory(rows: List<HistorySample>, now: Double): List<Pair<Long, Int>> {
        val start = now - WINDOW_SECONDS
        val buckets = sortedMapOf<Long, Pair<Double, Int>>()
        rows.forEach { r ->
            val soc = r.soc ?: return@forEach
            if (r.ts < start || r.ts > now) return@forEach
            val key = floor(r.ts / SOC_BUCKET_SECONDS).toLong()
            val existing = buckets[key]
            if (existing == null || r.ts >= existing.first) buckets[key] = r.ts to soc
        }
        return buckets.values.toList().takeLast(SOC_MAX_POINTS).map { Math.round(it.first) to it.second }
    }

    private data class StateSpan(val t0: Double, val t1: Double, val sample: HistorySample)

    /** Consecutive samples ≤ MAX_GAP apart, clipped to [start, now]; the state is the earlier sample's. */
    private fun stateSpans(rows: List<HistorySample>, start: Double, now: Double): List<StateSpan> =
        rows.sortedBy { it.ts }.zipWithNext().mapNotNull { (a, b) ->
            val dt = b.ts - a.ts
            if (dt <= 0 || dt > MAX_GAP_SECONDS) return@mapNotNull null
            val t0 = maxOf(a.ts, start)
            val t1 = minOf(b.ts, now)
            if (t1 > t0) StateSpan(t0, t1, a) else null
        }

    private fun outage(sample: HistorySample, thresholdW: Double) = sample.offgridW?.takeIf(Double::isFinite)?.let { abs(it) >= thresholdW } == true

    fun localMidnight(now: Double): Double = ZonedDateTime.ofInstant(Instant.ofEpochMilli((now * 1000).toLong()), kyiv)
        .toLocalDate().atStartOfDay(kyiv).toEpochSecond().toDouble()

    /** Seconds since local midnight charging / discharging / with backup output drawing (= no grid). Gaps count as nothing. */
    fun todayDurations(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): Triple<Long, Long, Long> {
        var charge = 0.0
        var discharge = 0.0
        var off = 0.0
        stateSpans(rows, localMidnight(now), now).forEach { s ->
            val span = s.t1 - s.t0
            when (s.sample.flowDir) {
                "charging" -> charge += span
                "discharging" -> discharge += span
            }
            if (outage(s.sample, thresholdW)) off += span
        }
        return Triple(Math.round(charge), Math.round(discharge), Math.round(off))
    }

    /** Backup-output draw periods in the last 24 h as (startEpochS, endEpochS); gaps split periods. */
    fun outagePeriods(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): List<Pair<Long, Long>> {
        val periods = mutableListOf<DoubleArray>()
        stateSpans(rows, now - WINDOW_SECONDS, now).filter { outage(it.sample, thresholdW) }.forEach { s ->
            val last = periods.lastOrNull()
            if (last != null && abs(last[1] - s.t0) < 1e-6) last[1] = s.t1 else periods += doubleArrayOf(s.t0, s.t1)
        }
        return periods.filter { it[1] - it[0] >= 60 }.map { Math.round(it[0]) to Math.round(it[1]) }
    }

    /**
     * Standby self-consumption (W) from the top-up cadence while held at 100 % with the grid present: one SOC step
     * (Δ bat_wh) is lost between the end of a top-up (back at 100 %) and the next drop to 99 %. Median over 24 h.
     */
    fun standbyConsumption(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): Int? {
        val points = rows.filter { it.ts >= now - WINDOW_SECONDS && it.ts <= now }.sortedBy { it.ts }
        val estimates = mutableListOf<Double>()
        var backAt: Double? = null
        var backWh: Double? = null
        points.zipWithNext().forEach { (prev, row) ->
            val soc = row.soc
            val prevSoc = prev.soc
            when {
                soc == null || prevSoc == null || outage(row, thresholdW) -> backAt = null
                prevSoc >= 100 && soc == 99 -> {
                    val whNow = row.batWh
                    val start = backAt
                    val full = backWh
                    if (start != null && whNow != null && full != null && full > whNow) {
                        val span = row.ts - start
                        if (span > 0 && span <= STANDBY_MAX_INTERVAL_SECONDS) estimates += (full - whNow) / (span / 3600.0)
                    }
                    backAt = null
                }
                prevSoc == 99 && soc >= 100 -> { backAt = row.ts; backWh = row.batWh }
                soc < 99 -> backAt = null
            }
        }
        if (estimates.isEmpty()) return null
        val sorted = estimates.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        return median.roundToInt()
    }

    private fun isoAt(epochS: Long): String = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochS), kyiv).format(iso)

    fun insights(
        rows: List<HistorySample>,
        now: Double,
        currentOffgridW: Double?,
        temperatureC: Double?,
        thresholdW: Double = BACKUP_DRAW_THRESHOLD_W,
        chartEvents: List<Pair<Long, String>> = emptyList(),
    ): JSONObject {
        val (energy, covered) = integral(intervals(rows, now - WINDOW_SECONDS))
        val average = if (covered >= MIN_AVG_COVERAGE_SECONDS) Math.round(energy / covered * 10.0) / 10.0 else null
        val midnight = ZonedDateTime.ofInstant(Instant.ofEpochMilli((now * 1000).toLong()), kyiv)
            .toLocalDate().atStartOfDay(kyiv).toEpochSecond().toDouble()
        val today = intervals(rows, midnight)
        val (todayWs, _) = integral(today)
        val history = JSONArray()
        socHistory(rows, now).forEach { (t, soc) ->
            history.put(JSONObject().put("t", ZonedDateTime.ofInstant(Instant.ofEpochSecond(t), kyiv).format(iso)).put("soc", soc))
        }
        return JSONObject()
            .put("backup_output_w", currentOffgridW?.takeIf(Double::isFinite) ?: JSONObject.NULL)
            .put("backup_avg_w_24h", average ?: JSONObject.NULL)
            .put("backup_avg_coverage_hours", Math.round(covered / 3600.0 * 100.0) / 100.0)
            .put("backup_energy_today_wh", if (today.isNotEmpty()) Math.round(todayWs / 3600.0 * 10.0) / 10.0 else JSONObject.NULL)
            .put("temperature_c", temperatureC?.takeIf(Double::isFinite) ?: JSONObject.NULL)
            .put("soc_history", history)
            .put("today", todayDurations(rows, now, thresholdW).let { (c, d, o) ->
                JSONObject().put("charge_seconds", c).put("discharge_seconds", d).put("outage_seconds", o)
            })
            .put("outage_periods", JSONArray().apply {
                outagePeriods(rows, now, thresholdW).forEach { (a, b) -> put(JSONObject().put("start", isoAt(a)).put("end", isoAt(b))) }
            })
            .put("chart_events", JSONArray().apply {
                chartEvents.forEach { (millis, kind) -> put(JSONObject().put("t", isoAt(millis / 1000)).put("kind", kind)) }
            })
            .put("standby_w", standbyConsumption(rows, now, thresholdW) ?: JSONObject.NULL)
    }

    fun prune(rows: List<HistorySample>, now: Double): List<HistorySample> = rows.filter { it.ts >= now - RETENTION_SECONDS }

    @Suppress("unused")
    private fun Double.roundInt() = roundToInt()
}
