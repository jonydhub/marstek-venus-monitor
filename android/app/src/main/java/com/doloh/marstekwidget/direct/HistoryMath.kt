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

    fun insights(rows: List<HistorySample>, now: Double, currentOffgridW: Double?, temperatureC: Double?): JSONObject {
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
    }

    fun prune(rows: List<HistorySample>, now: Double): List<HistorySample> = rows.filter { it.ts >= now - RETENTION_SECONDS }

    @Suppress("unused")
    private fun Double.roundInt() = roundToInt()
}
