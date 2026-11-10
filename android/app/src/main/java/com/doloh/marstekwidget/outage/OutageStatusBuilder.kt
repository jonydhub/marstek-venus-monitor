package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.direct.AutonomyCalculator
import com.doloh.marstekwidget.direct.BatteryFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Builds the `power_outage` block in the same shape the server returns (plus source/emergency/maybe). */
object OutageStatusBuilder {
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    private fun window(w: OutageWindow, now: ZonedDateTime): JSONObject {
        val from = if (w.start.isAfter(now)) w.start else now
        return JSONObject()
            .put("kind", if (w.maybe) "maybe" else "schedule")
            .put("start", w.start.withZoneSameInstant(KYIV).format(iso))
            .put("end", w.end.withZoneSameInstant(KYIV).format(iso))
            .put("duration_seconds", Duration.between(w.start, w.end).seconds)
            .put("remaining_seconds", maxOf(0L, Duration.between(from, w.end).seconds))
    }

    fun build(
        config: OutageConfig?,
        schedule: OutageSchedule?,
        error: String?,
        now: ZonedDateTime,
        gridState: String,
        storedWh: Double?,
        flow: BatteryFlow?,
        expectedLoadW: Double?,
        measuredLoadW: Double?,
        fetchedAt: String?,
    ): JSONObject {
        val windows = schedule?.windows.orEmpty().filter { it.end.isAfter(now) }
        val definite = windows.filter { !it.maybe }
        val current = definite.firstOrNull { !it.start.isAfter(now) }
        val next = definite.firstOrNull { it.start.isAfter(now) }
        val maybe = windows.firstOrNull { it.maybe }
        val outage = current ?: next
        val remaining = outage?.let { window(it, now).getLong("remaining_seconds") }
        val autonomy = AutonomyCalculator.calculate(gridState, storedWh, flow, expectedLoadW, measuredLoadW, remaining)
        return JSONObject()
            .put("available", true)
            .put("provider", schedule?.source?.displayName ?: JSONObject.NULL)
            .put("source_id", schedule?.source?.name ?: JSONObject.NULL)
            .put("region", config?.regionId ?: JSONObject.NULL)
            .put("group", config?.queue ?: JSONObject.NULL)
            .put("schedule_enabled", config != null)
            .put("grid_state", gridState)
            .put("grid_state_basis", "backup_output_power")
            .put("current_outage", current?.let { window(it, now) } ?: JSONObject.NULL)
            .put("next_outage", next?.let { window(it, now) } ?: JSONObject.NULL)
            .put("maybe_outage", maybe?.let { window(it, now) } ?: JSONObject.NULL)
            .put("emergency", schedule?.emergency == true)
            .put("schedule_published", schedule?.publishedDates?.isNotEmpty() == true)
            .put("schedule_dates", JSONArray(schedule?.publishedDates.orEmpty().map { it.toString() }))
            .put("schedule_updated_at", schedule?.updatedAt ?: JSONObject.NULL)
            .put("autonomy", autonomy.toJson())
            .put("fetched_at", fetchedAt ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
    }
}
