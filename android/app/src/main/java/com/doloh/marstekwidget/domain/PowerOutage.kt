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
)

object PowerOutageParser {
    fun parse(block: JSONObject?): PowerOutageInfo? {
        if (block == null) return null
        val unavailable = block.optBoolean("schedule_unavailable", false)
        if (!block.optBoolean("available", false) && !unavailable) return null
        val current = block.optJSONObject("current_outage")
        val next = block.optJSONObject("next_outage")
        val autonomy = block.optJSONObject("autonomy") ?: JSONObject()
        val maybe = block.optJSONObject("maybe_outage")
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
            source = block.optStringOrNull("provider"),
            emergency = block.optBoolean("emergency", false),
            scheduleEnabled = block.optBoolean("schedule_enabled", true),
            maybeStart = maybe?.optStringOrNull("start"),
            maybeEnd = maybe?.optStringOrNull("end"),
            scheduleUnavailable = unavailable,
        )
    }

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

    fun detail(info: PowerOutageInfo, now: ZonedDateTime = ZonedDateTime.now(kyiv)): String {
        if (!info.scheduleEnabled) return "Графік відключень не налаштовано"
        if (info.scheduleUnavailable) return SCHEDULE_UNAVAILABLE
        if (info.emergency) return "Аварійні відключення, графік не діє"
        info.currentEnd?.let(::toKyiv)?.let { end ->
            return "Повернення о ${stamp(end, now)}"
        }
        val start = info.nextStart?.let(::toKyiv)
        val end = info.nextEnd?.let(::toKyiv)
        if (start != null && end != null) {
            val datePrefix = if (start.toLocalDate() == now.withZoneSameInstant(kyiv).toLocalDate()) "" else "${start.format(dayMonth)} "
            return "Відключення $datePrefix${start.format(clock)}–${end.format(clock)}"
        }
        maybe(info, now)?.let { return it }
        return if (info.schedulePublished) "За графіком без відключень" else "Графік не опубліковано"
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

    const val SCHEDULE_UNAVAILABLE = "Графік недоступний — світло визначається за резервним виходом"

    fun shortDetail(info: PowerOutageInfo, now: ZonedDateTime = ZonedDateTime.now(kyiv)): String? {
        info.currentEnd?.let(::toKyiv)?.let { end -> return "до ${stamp(end, now)}" }
        val start = info.nextStart?.let(::toKyiv) ?: return null
        if (start.toLocalDate() != now.withZoneSameInstant(kyiv).toLocalDate()) return null
        return "відкл. ${start.format(clock)}"
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
