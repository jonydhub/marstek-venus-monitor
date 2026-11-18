package com.doloh.marstekwidget.widget

import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.LightKind
import com.doloh.marstekwidget.domain.LightStatusResolver
import com.doloh.marstekwidget.domain.PowerOutageFormatter
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * What the standalone «Світло» widget says, decided in pure Kotlin from the cached snapshot (no Android types).
 * Every list is ordered longest-first; the renderer picks the first candidate that fits the measured width,
 * so texts shorten instead of ending in «…».
 */
data class LightWidgetContent(
    val kind: LightKind,
    /** «Світла нема» / «Світло є» / «Немає зв'язку з батареєю», «Немає зв'язку» / «Стан світла невідомий». */
    val headlines: List<String>,
    /** «вже 2 год 15 хв», «2 год 15 хв»; empty when the start of the current state is unknown. */
    val durations: List<String>,
    /** One schedule sentence that follows the measured light (same formatter as the app). */
    val next: List<String>,
    /** «Черга 1.1 · Львівобленерго», «Черга 1.1»; empty without a configured schedule. */
    val group: List<String>,
    /** «Запас ≈ 2 д 20 год · вистачить» (the app's reserve text); null on a lost link or without a forecast. */
    val reserve: String?,
    val reserveCovers: Boolean?,
)

data class StripText(val primary: String, val secondary: String?)

object LightWidgetTexts {
    private const val NBSP = '\u00A0'
    private const val SEP = " · "
    const val UNKNOWN_HEADLINE = "Стан світла невідомий"
    const val RETURN_UNKNOWN = "Час повернення невідомий"

    /** «2 год 15 хв», «1 год», «45 хв», «1 д 3 год», «щойно» (< 1 min) — never «0 хв»; null when unknown/negative. */
    fun elapsed(seconds: Long?): String? {
        if (seconds == null || seconds < 0) return null
        val minutes = seconds / 60
        if (minutes < 1) return "щойно"
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val rest = minutes % 60
        return when {
            days > 0 -> if (hours == 0L) "$days${NBSP}д" else "$days${NBSP}д $hours${NBSP}год"
            hours > 0 -> if (rest == 0L) "$hours${NBSP}год" else "$hours${NBSP}год $rest${NBSP}хв"
            else -> "$minutes${NBSP}хв"
        }
    }

    fun content(snapshot: BatterySnapshot, linkLost: Boolean, now: ZonedDateTime): LightWidgetContent {
        val info = snapshot.powerOutage
        val light = LightStatusResolver.resolve(snapshot, linkLost, now)
        val headlines = when (light.kind) {
            LightKind.NO_LINK -> listOf(LightStatusResolver.NO_LINK, "Немає зв'язку")
            LightKind.UNKNOWN -> listOf(UNKNOWN_HEADLINE, "Світло: —")
            else -> listOf(light.headline)
        }
        val since = when (light.kind) {
            LightKind.OFF -> info?.outageSince
            LightKind.ON -> info?.lightSince
            else -> null
        }
        val duration = since?.let { runCatching { Duration.between(OffsetDateTime.parse(it).toInstant(), now.toInstant()).seconds }.getOrNull() }
            ?.let(::elapsed)
        val durations = when {
            duration == null -> emptyList()
            duration == "щойно" -> listOf(duration)
            else -> listOf("вже $duration", duration)
        }
        val next = when {
            info == null -> emptyList()
            light.kind == LightKind.NO_LINK -> listOfNotNull(
                light.hint?.replaceFirstChar { it.titlecase() } ?: PowerOutageFormatter.detail(info, now, GridState.UNKNOWN),
            )
            // Off: only the scheduled return (or the emergency note) — never «Відключення о …» while it is already off.
            light.kind == LightKind.OFF -> if (info.currentEnd != null || info.emergency || info.scheduleUnavailable)
                listOf(PowerOutageFormatter.detailBesideHeadline(info, now, GridState.OFF)) else listOf(RETURN_UNKNOWN)
            else -> listOfNotNull(
                PowerOutageFormatter.nextWindowRange(info, now, light.gridState),
                PowerOutageFormatter.detailBesideHeadline(info, now, light.gridState),
            )
        }
        val group = info?.takeIf { it.scheduleEnabled }?.group?.let { g ->
            listOfNotNull(info.source?.let { "Черга $g$SEP$it" }, "Черга $g")
        }.orEmpty()
        val reserve = info?.takeIf { light.kind == LightKind.ON || light.kind == LightKind.OFF }?.let(PowerOutageFormatter::reserve)
        return LightWidgetContent(
            kind = light.kind,
            headlines = headlines,
            durations = durations,
            next = next,
            group = group,
            reserve = reserve,
            reserveCovers = info?.coversOutage.takeIf { reserve != null },
        )
    }

    /**
     * 4×1 / 2×1 strip: «Світла нема · вже 2 год 15 хв» on one line while it fits (dropping «вже» first), the schedule
     * sentence under it when [twoLines]. Too narrow for both: headline alone, the duration moves to line 2.
     */
    fun strip(content: LightWidgetContent, widthDp: Float, twoLines: Boolean, measure: (String) -> Float): StripText {
        fun fit(candidates: List<String>) = candidates.firstOrNull { measure(it) <= widthDp }
        val combined = content.headlines.flatMap { h -> content.durations.map { d -> "$h$SEP$d" } }
        val joined = fit(combined)
        if (joined != null || content.durations.isEmpty()) {
            val primary = joined ?: fit(content.headlines) ?: content.headlines.last()
            return StripText(primary, if (twoLines) fit(content.next) else null)
        }
        val primary = fit(content.headlines) ?: content.headlines.last()
        return StripText(primary, if (twoLines) fit(content.durations) ?: content.durations.last() else null)
    }
}

object LightWidgetFit {
    /**
     * First candidate that fits [widthDp] at the largest size in [minSp]..[maxSp] (1 sp steps); a long candidate is
     * shrunk before a shorter one is tried. Null when there are no candidates; the last one at [minSp] when none fits.
     */
    fun fit(candidates: List<String>, widthDp: Float, maxSp: Float, minSp: Float, measure: (String, Float) -> Float): Pair<String, Float>? {
        if (candidates.isEmpty()) return null
        for (text in candidates) {
            var sp = maxSp
            while (sp >= minSp - 0.001f) {
                if (measure(text, sp) <= widthDp) return text to sp
                sp -= 1f
            }
        }
        return candidates.last() to minSp
    }
}
