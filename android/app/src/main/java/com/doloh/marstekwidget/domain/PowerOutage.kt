package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class GridState { ON, OFF, UNKNOWN }

/** Outage schedule (official source or aggregator) + local backup-output evidence; same block in server and direct mode. */
data class PowerOutageInfo(
    val gridState: GridState,
    val group: String?,
    val currentKind: String?,
    val currentEnd: String?,
    val nextStart: String?,
    val nextEnd: String?,
    val nextDurationSeconds: Long?,
    val schedulePublished: Boolean,
    val runtimeSeconds: Long?,
    val coversOutage: Boolean?,
    val loadBasis: String?,
    val loadWatts: Double?,
    val sourceError: String?,
    /** Display name of the schedule source, e.g. «Львівобленерго», «YASNO». */
    val source: String? = null,
    val emergency: Boolean = false,
    val scheduleEnabled: Boolean = true,
    val maybeStart: String? = null,
    val maybeEnd: String? = null,
    /** A region is set but no source publishes its schedule (grid state still comes from the backup output). */
    val scheduleUnavailable: Boolean = false,
    val currentStart: String? = null,
    /** Today + tomorrow schedule windows (definite and «можливе») for the schedule bar. */
    val windows: List<ScheduleWindow> = emptyList(),
    /** Discharge floor (% SOC) the reserve runtime is computed above; null = to 0 %. */
    val floorPct: Double? = null,
    val usableWh: Double? = null,
    val storedWh: Double? = null,
    /** Dates (yyyy-MM-dd, Kyiv) the source has published a schedule for; tells «завтра ще не опубліковано» apart from «без відключень». */
    val scheduleDates: List<String> = emptyList(),
    val scheduleUpdatedAt: String? = null,
    val fetchedAt: String? = null,
    /** Machine id of the schedule source (loe, yasno, kharkiv, LOE, YASNO, …) for the official-site link. */
    val sourceId: String? = null,
    val regionId: String? = null,
    /** Light hysteresis: the light is still off but the backup output reads ~0 W now (a load pause, or a trip). */
    val pendingReturn: Boolean = false,
    /** Suspected overload trip in this outage: last backup reading (W) and when it was detected (ISO). */
    val overloadTripWatts: Double? = null,
    val overloadTripAt: String? = null,
    /** Start of the current outage (ISO) per the hysteresis. */
    val outageSince: String? = null,
)

data class ScheduleWindow(val startMillis: Long, val endMillis: Long, val maybe: Boolean)

object PowerOutageParser {
    fun parse(block: JSONObject?): PowerOutageInfo? {
        if (block == null) return null
        val unavailable = block.optBoolean("schedule_unavailable", false)
        if (!block.optBoolean("available", false) && !unavailable) return null
        val current = block.optJSONObject("current_outage")
        val next = block.optJSONObject("next_outage")
        val autonomy = block.optJSONObject("autonomy") ?: JSONObject()
        // direct mode emits one `maybe_outage`, the server a `maybe_outages` list
        val maybe = block.optJSONObject("maybe_outage") ?: block.optJSONArray("maybe_outages")?.optJSONObject(0)
        val windows = buildList {
            val arr = block.optJSONArray("windows")
            if (arr != null) for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                val start = w.optStringOrNull("start")?.let(::millis) ?: continue
                val end = w.optStringOrNull("end")?.let(::millis) ?: continue
                if (end > start) add(ScheduleWindow(start, end, w.optString("kind") == "maybe"))
            }
        }.sortedBy { it.startMillis }
        return PowerOutageInfo(
            gridState = when (block.optString("grid_state")) {
                "on" -> GridState.ON
                "off" -> GridState.OFF
                else -> GridState.UNKNOWN
            },
            group = block.optStringOrNull("group"),
            currentKind = current?.optStringOrNull("kind"),
            currentEnd = current?.optStringOrNull("end"),
            nextStart = next?.optStringOrNull("start"),
            nextEnd = next?.optStringOrNull("end"),
            nextDurationSeconds = next?.optLongOrNull("duration_seconds"),
            schedulePublished = block.optBoolean("schedule_published", false),
            runtimeSeconds = autonomy.optLongOrNull("runtime_seconds"),
            coversOutage = if (autonomy.has("covers_next_outage") && !autonomy.isNull("covers_next_outage")) {
                autonomy.optBoolean("covers_next_outage")
            } else {
                null
            },
            loadBasis = autonomy.optStringOrNull("load_basis"),
            loadWatts = autonomy.optDouble("load_w", Double.NaN).takeIf(Double::isFinite),
            sourceError = block.optStringOrNull("error"),
            // server: provider = auto/loe/…, source_name = display name; direct mode: provider = display name
            source = block.optStringOrNull("source_name") ?: block.optStringOrNull("provider")?.takeIf { it !in setOf("auto", "off", "unavailable") },
            emergency = block.optBoolean("emergency", false),
            scheduleEnabled = block.optBoolean("schedule_enabled", true),
            maybeStart = maybe?.optStringOrNull("start"),
            maybeEnd = maybe?.optStringOrNull("end"),
            scheduleUnavailable = unavailable,
            currentStart = current?.optStringOrNull("start"),
            windows = windows,
            floorPct = autonomy.optDouble("floor_pct", Double.NaN).takeIf { it.isFinite() && it > 0 },
            usableWh = autonomy.optDouble("usable_wh", Double.NaN).takeIf(Double::isFinite),
            storedWh = autonomy.optDouble("stored_wh", Double.NaN).takeIf(Double::isFinite),
            scheduleDates = block.optJSONArray("schedule_dates")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) } }.orEmpty(),
            scheduleUpdatedAt = block.optStringOrNull("schedule_updated_at"),
            fetchedAt = block.optStringOrNull("fetched_at"),
            sourceId = block.optStringOrNull("source_id") ?: block.optStringOrNull("source"),
            regionId = block.optStringOrNull("region"),
            pendingReturn = block.optJSONObject("grid_detail")?.optBoolean("pending_return", false) == true,
            overloadTripWatts = block.optJSONObject("grid_detail")?.optJSONObject("overload_trip")?.optDouble("last_w", Double.NaN)
                ?.takeIf(Double::isFinite),
            overloadTripAt = block.optJSONObject("grid_detail")?.optJSONObject("overload_trip")?.optStringOrNull("at"),
            outageSince = block.optJSONObject("grid_detail")?.optStringOrNull("outage_since"),
        )
    }

    private fun millis(value: String): Long? = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { it.isFinite() && it >= 0.0 }?.toLong()
    }
}

object PowerOutageFormatter {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val dayMonth = DateTimeFormatter.ofPattern("dd.MM")

    fun headline(info: PowerOutageInfo): String = when (info.gridState) {
        GridState.ON -> "Світло є"
        GridState.OFF -> "Світла нема"
        GridState.UNKNOWN -> "Світло: —"
    }

    /**
     * One schedule sentence that never contradicts the measured light state:
     * grid OFF + window → «Повернення о 13:00»; grid ON + window → «За графіком відключення до 13:00 · світло є»;
     * no active window → «Відключення о 15:00» (today) / «Відключення завтра о 07:00».
     */
    fun detail(info: PowerOutageInfo, now: ZonedDateTime = ZonedDateTime.now(kyiv), gridState: GridState = info.gridState): String {
        if (!info.scheduleEnabled) return "Графік відключень не налаштовано"
        if (info.scheduleUnavailable) return SCHEDULE_UNAVAILABLE
        if (info.emergency) return "Аварійні відключення, графік не діє"
        info.currentEnd?.let(::toKyiv)?.let { end ->
            return when (gridState) {
                GridState.OFF -> if (info.currentKind == "schedule" || info.currentKind == null) "Світло за графіком о ${stamp(end, now)}" else "Повернення о ${stamp(end, now)}"
                GridState.ON -> "За графіком відключення до ${stamp(end, now)}$LIGHT_ON_SUFFIX"
                GridState.UNKNOWN -> "За графіком відключення до ${stamp(end, now)}"
            }
        }
        info.nextStart?.let(::toKyiv)?.let { start -> return "Відключення ${dayAt(start, now)}" }
        maybe(info, now)?.let { return it }
        return if (info.schedulePublished) "За графіком без відключень" else "Графік не опубліковано"
    }

    /** «о 15:00» today, «завтра о 07:00», otherwise «19.10 о 07:00». */
    fun dayAt(value: ZonedDateTime, now: ZonedDateTime): String {
        val today = now.withZoneSameInstant(kyiv).toLocalDate()
        val day = value.withZoneSameInstant(kyiv).toLocalDate()
        val time = value.withZoneSameInstant(kyiv).format(clock)
        return when (day) {
            today -> "о $time"
            today.plusDays(1) -> "завтра о $time"
            else -> "${value.format(dayMonth)} о $time"
        }
    }

    /** «Можливе відключення …» — kept apart from definite outages. */
    fun maybe(info: PowerOutageInfo, now: ZonedDateTime = ZonedDateTime.now(kyiv)): String? {
        val start = info.maybeStart?.let(::toKyiv) ?: return null
        val end = info.maybeEnd?.let(::toKyiv) ?: return null
        val datePrefix = if (start.toLocalDate() == now.withZoneSameInstant(kyiv).toLocalDate()) "" else "${start.format(dayMonth)} "
        return "Можливе відключення $datePrefix${start.format(clock)}–${end.format(clock)}"
    }

    fun source(info: PowerOutageInfo): String? = info.source?.let { "Джерело: $it" }

    /** «Область · черга · джерело» under the headline; none when no schedule is configured (the detail line says so). */
    fun groupLine(info: PowerOutageInfo, regionName: String?): String? {
        if (!info.scheduleEnabled) return null
        return listOfNotNull(
            if (regionName != null) "$regionName · черга ${info.group ?: "—"}" else info.group?.let { "Черга $it" },
            source(info),
        ).joinToString(" · ").ifEmpty { null }
    }

    /** Tail of [detail] while the grid is on during a scheduled window; dropped where «Світло є» is already shown. */
    const val LIGHT_ON_SUFFIX = " · світло є"

    /** [detail] for places that already show the light headline next to it (no «світло є» twice). */
    fun detailBesideHeadline(info: PowerOutageInfo, now: ZonedDateTime = ZonedDateTime.now(kyiv), gridState: GridState = info.gridState): String =
        detail(info, now, gridState).removeSuffix(LIGHT_ON_SUFFIX)

    const val SCHEDULE_UNAVAILABLE = "Графік недоступний — світло визначається за резервним виходом"

    fun shortDetail(info: PowerOutageInfo, now: ZonedDateTime = ZonedDateTime.now(kyiv)): String? {
        info.currentEnd?.let(::toKyiv)?.let { end ->
            // «до 13:00» means «light returns at 13:00» only while the light is really off.
            return if (info.gridState == GridState.OFF) "до ${stamp(end, now)}" else "графік: відкл. до ${stamp(end, now)}"
        }
        val start = info.nextStart?.let(::toKyiv) ?: return null
        if (start.toLocalDate() != now.withZoneSameInstant(kyiv).toLocalDate()) return null
        return "відкл. ${start.format(clock)}"
    }

    /** True while the schedule (not the battery) says an outage is going on right now. */
    fun scheduledNow(info: PowerOutageInfo, now: ZonedDateTime = ZonedDateTime.now(kyiv)): Boolean {
        val start = info.currentStart?.let(::toKyiv) ?: return false
        val end = info.currentEnd?.let(::toKyiv) ?: return false
        return !now.isBefore(start) && now.isBefore(end)
    }

    fun reserve(info: PowerOutageInfo): String? {
        val runtime = info.runtimeSeconds ?: return null
        val verdict = when (info.coversOutage) {
            true -> " · вистачить"
            false -> " · не вистачить"
            null -> ""
        }
        return "Запас ≈ ${EtaTextFormatter.formatSeconds(runtime)}$verdict"
    }

    private fun stamp(value: ZonedDateTime, now: ZonedDateTime): String =
        if (value.toLocalDate() == now.withZoneSameInstant(kyiv).toLocalDate()) value.format(clock)
        else "${value.format(dayMonth)} ${value.format(clock)}"

    private fun toKyiv(value: String): ZonedDateTime? =
        runCatching { OffsetDateTime.parse(value).atZoneSameInstant(kyiv) }.getOrNull()
}
