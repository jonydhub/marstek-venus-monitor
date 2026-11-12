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

/** Today since local midnight: durations (s), battery energy in/out (Wh; `*Estimated` = partly the capacity-trend
 * estimate) and the confirmed light timeline (see [GridHysteresis.timeline]). */
data class TodayStats(
    val chargeSeconds: Long,
    val dischargeSeconds: Long,
    val outageSeconds: Long,
    val chargeWh: Double,
    val dischargeWh: Double,
    val chargeEstimated: Boolean,
    val dischargeEstimated: Boolean,
    val timeline: List<GridSpan>,
)

/**
 * Port of server history.py. Differences: MAX_GAP is 20 min (not 5) because a phone polls every ~15 min in the
 * background; longer gaps are still never bridged. A poll the battery did not answer is stored as a sample with
 * only `ts` (and the ES powers missing) — it breaks the light-return run of [GridHysteresis].
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
    const val SMOOTH_WINDOW_SECONDS = 600.0
    const val MEASURED_MIN_W = 10.0
    /** Run continuity for the idle rule: 15 s foreground polls (2.5 × 15 s → 120 s); background polls are too sparse
     * for it, so after an outage in the background only grid charging confirms the light. */
    val CONFIRM_GAP_SECONDS = GridHysteresis.confirmGapSeconds(15.0)
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
        list.forEach { energyWs += (it.v0.coerceAtLeast(0.0) + it.v1.coerceAtLeast(0.0)) / 2.0 * (it.t1 - it.t0); covered += it.t1 - it.t0 }
        return energyWs to covered
    }

    /** Mean backup-output draw over 24 h, only while it was actually drawing; null before 30 min of draw. */
    fun measuredBackupLoad(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): Double? {
        val draw = intervals(rows, now - WINDOW_SECONDS).filter { it.v0 >= thresholdW && it.v1 >= thresholdW }
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

    private fun outage(sample: HistorySample, thresholdW: Double) = sample.offgridW?.takeIf(Double::isFinite)?.let { it >= thresholdW } == true

    fun localMidnight(now: Double): Double = ZonedDateTime.ofInstant(Instant.ofEpochMilli((now * 1000).toLong()), kyiv)
        .toLocalDate().atStartOfDay(kyiv).toEpochSecond().toDouble()

    fun gridPoints(rows: List<HistorySample>): List<GridPoint> = rows.map { GridPoint(it.ts, it.offgridW, it.ongridW) }

    /** Confirmed light on/off/unknown spans over [start, now] (the «Фактично» row; same rules as the server). */
    fun gridTimeline(rows: List<HistorySample>, start: Double, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W,
                     confirmGap: Double = CONFIRM_GAP_SECONDS): List<GridSpan> =
        GridHysteresis.timeline(gridPoints(rows), start, now, thresholdW, confirmGap, MAX_GAP_SECONDS)

    /** Battery power from the AC ports (+ charging, − discharging), or null when both read ~0 (no measured flow). */
    private fun measuredBatteryW(row: HistorySample): Double? {
        val ongrid = row.ongridW?.takeIf { it.isFinite() && abs(it) >= MEASURED_MIN_W } ?: 0.0
        val offgrid = row.offgridW?.takeIf { it.isFinite() && it >= MEASURED_MIN_W } ?: 0.0
        if (ongrid == 0.0 && offgrid == 0.0) return null
        return -ongrid - offgrid
    }

    /** Since local midnight: charging / discharging / no-light seconds, energy in/out and the light timeline. */
    fun todayStats(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W,
                   confirmGap: Double = CONFIRM_GAP_SECONDS): TodayStats {
        var charge = 0.0
        var discharge = 0.0
        var inWh = 0.0
        var outWh = 0.0
        var inEst = 0.0
        var outEst = 0.0
        val midnight = localMidnight(now)
        stateSpans(rows, midnight, now).forEach { s ->
            val span = s.t1 - s.t0
            when (s.sample.flowDir) {
                "charging" -> charge += span
                "discharging" -> discharge += span
            }
            var power = measuredBatteryW(s.sample)
            var estimated = false
            if (power == null && (s.sample.flowDir == "charging" || s.sample.flowDir == "discharging")) {
                power = s.sample.flowW?.takeIf(Double::isFinite)
                estimated = true
            }
            if (power != null && power != 0.0) {
                val wh = abs(power) * span / 3600.0
                if (power > 0) { inWh += wh; if (estimated) inEst += wh } else { outWh += wh; if (estimated) outEst += wh }
            }
        }
        val timeline = gridTimeline(rows, midnight, now, thresholdW, confirmGap)
        val off = timeline.filter { it.state == "off" }.sumOf { it.end - it.start }
        return TodayStats(Math.round(charge), Math.round(discharge), Math.round(off), Math.round(inWh * 10.0) / 10.0,
            Math.round(outWh * 10.0) / 10.0, inEst > 1.0, outEst > 1.0, timeline)
    }

    /** Seconds since local midnight charging / discharging / without light (confirmed timeline). Gaps count as nothing. */
    fun todayDurations(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): Triple<Long, Long, Long> =
        todayStats(rows, now, thresholdW).let { Triple(it.chargeSeconds, it.dischargeSeconds, it.outageSeconds) }

    /** Confirmed no-light periods in the last 24 h as (startEpochS, endEpochS); unknown gaps split periods. */
    fun outagePeriods(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): List<Pair<Long, Long>> =
        gridTimeline(rows, now - WINDOW_SECONDS, now, thresholdW).filter { it.state == "off" && it.end - it.start >= 60 }
            .map { Math.round(it.start) to Math.round(it.end) }

    /** Energy-weighted mean backup draw (W) over the last 10 min of the outage that began at [since]; ≥ 2 samples. */
    fun smoothedBackupLoad(rows: List<HistorySample>, now: Double, since: Double?, window: Double = SMOOTH_WINDOW_SECONDS): Double? {
        since ?: return null
        val start = maxOf(since, now - window)
        val points = rows.filter { it.ts in start..now && it.offgridW?.isFinite() == true }
            .map { it.ts to it.offgridW!!.coerceAtLeast(0.0) }.sortedBy { it.first }
        if (points.size < 2) return null
        var energy = 0.0
        var covered = 0.0
        points.zipWithNext().forEach { (a, b) ->
            val dt = b.first - a.first
            if (dt > 0 && dt <= MAX_GAP_SECONDS) { energy += (a.second + b.second) / 2.0 * dt; covered += dt }
        }
        if (covered <= 0) return null
        return Math.round(energy / covered * 10.0) / 10.0
    }

    /** Last poll in 24 h that charged from the grid (UPS top-up / recharge): (watts, epochS). */
    fun lastGridCharge(rows: List<HistorySample>, now: Double, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): Pair<Long, Long>? =
        rows.filter { it.ts in (now - WINDOW_SECONDS)..now }.sortedByDescending { it.ts }.firstOrNull {
            val ongrid = it.ongridW?.takeIf(Double::isFinite)
            ongrid != null && ongrid < GridHysteresis.GRID_CHARGE_W && (it.offgridW == null || it.offgridW < thresholdW)
        }?.let { Math.round(-it.ongridW!!) to Math.round(it.ts) }

    /** Wh and seconds of real backup draw (both samples ≥ threshold) between two consecutive samples, for the 7-day stats. */
    fun drawIncrement(prev: HistorySample?, cur: HistorySample, thresholdW: Double = BACKUP_DRAW_THRESHOLD_W): Pair<Double, Double>? {
        prev ?: return null
        val a = prev.offgridW?.takeIf(Double::isFinite) ?: return null
        val b = cur.offgridW?.takeIf(Double::isFinite) ?: return null
        val dt = cur.ts - prev.ts
        if (dt <= 0 || dt > MAX_GAP_SECONDS || a < thresholdW || b < thresholdW) return null
        return (a + b) / 2.0 * dt / 3600.0 to dt
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
        /** 7-day backup draw (mean W, covered s), see [LoadStatsAccumulator]. */
        loadStats: Pair<Double?, Double> = null to 0.0,
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
            .put("today", todayStats(rows, now, thresholdW).let { t ->
                JSONObject().put("charge_seconds", t.chargeSeconds).put("discharge_seconds", t.dischargeSeconds)
                    .put("outage_seconds", t.outageSeconds).put("charge_wh", t.chargeWh).put("discharge_wh", t.dischargeWh)
                    .put("charge_estimated", t.chargeEstimated).put("discharge_estimated", t.dischargeEstimated)
                    .put("grid_timeline", JSONArray().apply {
                        t.timeline.forEach { s ->
                            put(JSONObject().put("start", isoAt(Math.round(s.start))).put("end", isoAt(Math.round(s.end))).put("state", s.state))
                        }
                    })
            })
            .put("outage_periods", JSONArray().apply {
                outagePeriods(rows, now, thresholdW).forEach { (a, b) -> put(JSONObject().put("start", isoAt(a)).put("end", isoAt(b))) }
            })
            .put("chart_events", JSONArray().apply {
                chartEvents.forEach { (millis, kind) -> put(JSONObject().put("t", isoAt(millis / 1000)).put("kind", kind)) }
            })
            .put("standby_w", standbyConsumption(rows, now, thresholdW) ?: JSONObject.NULL)
            .put("last_grid_charge", lastGridCharge(rows, now, thresholdW)?.let { (w, t) -> JSONObject().put("w", w).put("t", isoAt(t)) }
                ?: JSONObject.NULL)
            .put("backup_load_mean_w_7d", loadStats.first ?: JSONObject.NULL)
            .put("backup_load_coverage_seconds_7d", Math.round(loadStats.second))
    }

    fun prune(rows: List<HistorySample>, now: Double): List<HistorySample> = rows.filter { it.ts >= now - RETENTION_SECONDS }
}

/**
 * 7-day backup-load statistics without keeping 7 days of samples on the phone: per local day, the Wh and seconds of
 * real draw (zero-load excluded). Encoded as one small JSON string in preferences.
 */
object LoadStatsAccumulator {
    private const val DAYS = 7

    fun add(raw: String?, day: String, wh: Double, seconds: Double, keepFrom: String): String {
        val json = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
        val cur = json.optJSONArray(day)
        val total = JSONArray().put((cur?.optDouble(0) ?: 0.0) + wh).put((cur?.optDouble(1) ?: 0.0) + seconds)
        json.put(day, total)
        json.keys().asSequence().toList().filter { it < keepFrom }.forEach(json::remove)
        return json.toString()
    }

    /** (mean W, covered s) over the days ≥ [fromDay] (yyyy-MM-dd). */
    fun stats(raw: String?, fromDay: String): Pair<Double?, Double> {
        val json = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
        var wh = 0.0
        var seconds = 0.0
        json.keys().forEach { k ->
            if (k >= fromDay) json.optJSONArray(k)?.let { wh += it.optDouble(0); seconds += it.optDouble(1) }
        }
        if (seconds <= 0) return null to 0.0
        return Math.round(wh / (seconds / 3600.0) * 10.0) / 10.0 to seconds
    }

    fun windowStart(today: java.time.LocalDate): String = today.minusDays((DAYS - 1).toLong()).toString()

}
