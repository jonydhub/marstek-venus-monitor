package com.doloh.marstekwidget.domain

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Colour of the SOC ring: the web bar's red → yellow → green → teal gradient sampled at the SOC. */
object SocGradient {
    private val COLORS = intArrayOf(0xFFEF634D.toInt(), 0xFFF5BE28.toInt(), 0xFF8FD18A.toInt(), 0xFF35C9DA.toInt())
    private val STOPS = floatArrayOf(0f, 0.42f, 0.72f, 1f)

    fun colorAt(socPercent: Int): Int {
        val t = socPercent.coerceIn(0, 100) / 100f
        val i = (1 until STOPS.size).first { t <= STOPS[it] }
        val f = (t - STOPS[i - 1]) / (STOPS[i] - STOPS[i - 1])
        return lerp(COLORS[i - 1], COLORS[i], f)
    }

    private fun lerp(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).roundToInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Texts for the compact (2×2) widgets: every string is one line; callers try the candidates longest-first and keep the
 * first that fits the measured width ([TextFit]), so a narrow cell shortens labels instead of hiding numbers behind «…».
 */
object CompactWidgetTexts {
    private const val SEP = " · "
    private const val NBSP = '\u00A0'
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val uk: Locale = Locale.forLanguageTag("uk-UA")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    /** «27 °C» (compact, whole degrees) or «27,0 °C»; value and unit joined by a no-break space, never wraps. */
    fun temperature(celsius: Double?, compact: Boolean = true): String? {
        celsius?.takeIf(Double::isFinite) ?: return null
        return if (compact) "${celsius.roundToInt()}$NBSP°C" else String.format(uk, "%.1f$NBSP°C", celsius)
    }

    /** Header headline candidates: the full light state, then a shorter form for narrow cells. */
    fun headlineCandidates(headline: String): List<String> =
        if (headline == LightStatusResolver.NO_LINK) listOf(headline, "Немає зв'язку", "Нема зв'язку") else listOf(headline)

    /** Which header parts fit one line: the headline always; then temperature, time and the refresh button by priority. */
    data class HeaderChoice(val headline: String, val showTemperature: Boolean, val showTime: Boolean, val refreshButton: Boolean)

    /**
     * Header fit for «● Світло є  27 °C  18:30 ↻». Priority: headline > temperature > time > a separate refresh button
     * (without it the time carries a «↻» glyph and refreshes on tap). A warning time (stale / offline) beats temperature.
     */
    fun header(
        headlines: List<String>,
        hasTemperature: Boolean,
        hasTime: Boolean,
        timeImportant: Boolean,
        roomDp: Float,
        headlineDp: (String) -> Float,
        temperatureDp: Float,
        timeDp: Float,
        glyphDp: Float,
        refreshButtonDp: Float,
    ): HeaderChoice {
        data class Config(val temp: Boolean, val time: Boolean, val button: Boolean)
        val t = hasTemperature
        val time = hasTime
        val configs = if (timeImportant && time) listOf(Config(t, true, true), Config(t, true, false), Config(false, true, true), Config(false, true, false))
        else listOf(Config(t, time, true), Config(t, time, false), Config(t, false, true), Config(t, false, false), Config(false, time, true), Config(false, time, false), Config(false, false, false))
        for (c in configs.distinct()) {
            val fixed = (if (c.temp) temperatureDp else 0f) + (if (c.time) timeDp else 0f) + (if (c.button) refreshButtonDp else glyphDp)
            headlines.firstOrNull { headlineDp(it) <= roomDp - fixed }?.let { return HeaderChoice(it, c.temp, c.time, c.button) }
        }
        return HeaderChoice(headlines.last(), false, time && timeImportant, false)
    }

    /** «1 год 4 хв» / «57 хв» / «0» — the exact today duration with no-break spaces. */
    fun duration(seconds: Long?): String {
        val minutes = ((seconds ?: 0L) + 30) / 60
        if (minutes <= 0) return "0"
        val hours = minutes / 60
        return if (hours > 0) "$hours${NBSP}год ${minutes % 60}${NBSP}хв" else "$minutes${NBSP}хв"
    }

    private fun durationTight(seconds: Long?): String {
        val minutes = ((seconds ?: 0L) + 30) / 60
        if (minutes <= 0) return "0"
        val hours = minutes / 60
        return if (hours > 0) "${hours}г${NBSP}${minutes % 60}хв" else "${minutes}хв"
    }

    private fun durationRounded(seconds: Long?): String {
        val minutes = ((seconds ?: 0L) + 30) / 60
        if (minutes <= 0) return "0"
        return if (minutes >= 60) "${(minutes / 60.0).roundToLong()}г" else "${minutes}хв"
    }

    /** Today's charge / discharge / no-light durations, all three always present: «З 31 хв · Р 36 хв · Без світла 0». */
    fun todayCandidates(today: TodayDurations?): List<String> {
        today ?: return emptyList()
        val c = today.chargeSeconds
        val d = today.dischargeSeconds
        val o = today.outageSeconds
        return listOf(
            "З ${duration(c)}${SEP}Р ${duration(d)}${SEP}Без світла ${duration(o)}",
            "З ${durationTight(c)}${SEP}Р ${durationTight(d)}${SEP}Без світла ${durationTight(o)}",
            "З ${durationTight(c)}${SEP}Р ${durationTight(d)}${SEP}Без св. ${durationTight(o)}",
            "З ${durationRounded(c)}${SEP}Р ${durationRounded(d)}${SEP}Без св. ${durationRounded(o)}",
        ).distinct()
    }

    /** «14 год» — a runtime rounded to whole hours (≥ 1 h) for the shortest candidates. */
    private fun roughly(seconds: Long): String {
        val minutes = (seconds + 59) / 60
        return when {
            minutes >= 24 * 60 -> EtaTextFormatter.formatSeconds(seconds).replace(' ', NBSP)
            minutes >= 60 -> "${(minutes / 60.0).roundToLong()}${NBSP}год"
            else -> "$minutes${NBSP}хв"
        }
    }

    private fun exact(seconds: Long?): String = EtaTextFormatter.formatSeconds(seconds)
        .replace(Regex("(\\d) (год|хв|д)")) { "${it.groupValues[1]}$NBSP${it.groupValues[2]}" }

    /**
     * State + runtime/ETA line under the ring: «Очікування · Заряджено», «Вистачить ≈ 22 год», «Заряд · 100% за 1 год».
     * Ordered longest-first; the last candidate is the shortest that still carries the number.
     */
    fun stateCandidates(summary: WidgetSummary, snapshot: BatterySnapshot, now: ZonedDateTime = ZonedDateTime.now(kyiv)): List<String> {
        val info = snapshot.powerOutage
        return when (summary.mode) {
            SummaryMode.OUTAGE -> {
                val runtime = info?.runtimeSeconds ?: WidgetSummaryResolver.dischargeEtaSeconds(snapshot)
                    ?.takeIf { snapshot.direction == BatteryDirection.DISCHARGING }
                    ?: return listOf("Запас: оцінюю розряд…", "Запас: оцінюю…")
                val remaining = info?.currentEnd?.let { runCatching { java.time.Duration.between(now, OffsetDateTime.parse(it)).seconds }.getOrNull() }
                    ?.takeIf { it > 0 }
                val covers = info?.coversOutage ?: remaining?.let { runtime >= it }
                val verdict = when (covers) { true -> "Вистачить"; false -> "Не вистачить"; null -> "Запас" }
                listOf("$verdict ≈ ${exact(runtime)}", "$verdict ≈ ${roughly(runtime)}", "$verdict ${roughly(runtime)}")
            }
            SummaryMode.CHARGING, SummaryMode.DISCHARGING -> {
                val charging = summary.mode == SummaryMode.CHARGING
                val word = if (charging) "Заряд" else "Розряд"
                val power = snapshot.batteryPowerWatts?.takeIf(Double::isFinite)?.let { WidgetSummaryResolver.formatKw(it).replace(' ', NBSP) }
                val eta = if (charging) snapshot.etaSeconds else WidgetSummaryResolver.dischargeEtaSeconds(snapshot)
                val target = if (charging) "100%" else WidgetSummaryResolver.floorTarget(info)
                if (eta == null || eta <= 0) return listOfNotNull(power?.let { "$word ≈ $it" }, word)
                listOfNotNull(
                    power?.let { "$word $it$SEP$target за ${exact(eta)}" },
                    "$word$SEP$target за ${exact(eta)}",
                    "До $target$SEP${exact(eta)}",
                    "$word$SEP$target за ≈ ${roughly(eta)}",
                    "До $target$SEP≈ ${roughly(eta)}",
                )
            }
            SummaryMode.IDLE, SummaryMode.UNKNOWN -> {
                val state = if (summary.mode == SummaryMode.IDLE) "Очікування" else "Стан невідомий"
                val full = summary.mode == SummaryMode.IDLE && (snapshot.socPercent ?: 0) >= 99
                val runtime = info?.runtimeSeconds
                when {
                    full -> listOf("$state${SEP}Заряджено", "Заряджено")
                    runtime != null -> listOf("$state${SEP}запас ≈ ${exact(runtime)}", "$state$SEP≈ ${roughly(runtime)}", "Запас ≈ ${roughly(runtime)}")
                    else -> listOf(state)
                }
            }
        }
    }

    /** What the schedule row of a 2×2 shows: a today bar with a short caption, or a single short text line. */
    data class ScheduleRow(val showBar: Boolean, val caption: String?)

    /**
     * Today's schedule row: not published → one short line «Графік: немає» (no bar), otherwise the bar plus a short
     * caption — «до 21:00» while the light is off, «відкл. 14:00» before today's next window, «без відкл.» otherwise.
     * Null when no schedule is configured (the row is dropped).
     */
    fun scheduleRow(info: PowerOutageInfo?, today: DaySchedule?, now: ZonedDateTime = ZonedDateTime.now(kyiv)): ScheduleRow? {
        info ?: return null
        if (!info.scheduleEnabled) return null
        if (info.scheduleUnavailable) return ScheduleRow(false, "Графік: недоступний")
        if (info.emergency) return ScheduleRow(false, "Графік: аварійні")
        if (today == null || !today.published) return ScheduleRow(false, "Графік: немає")
        fun at(value: String?) = value?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(kyiv) }.getOrNull() }
        val end = at(info.currentEnd)
        val next = at(info.nextStart)
        val todayDate = now.withZoneSameInstant(kyiv).toLocalDate()
        val caption = when {
            end != null && info.gridState == GridState.OFF -> "до ${end.format(clock)}"
            end != null -> "відкл. до ${end.format(clock)}"
            next != null && next.toLocalDate() == todayDate -> "відкл. ${next.format(clock)}"
            today.definite.isEmpty() -> "без відкл."
            else -> null
        }
        return ScheduleRow(true, caption)
    }
}

/** Picks the first candidate whose measured width fits; the last one otherwise (the view still ellipsizes). */
object TextFit {
    fun pick(candidates: List<String>, maxWidth: Float, measure: (String) -> Float): String? =
        candidates.firstOrNull { measure(it) <= maxWidth } ?: candidates.lastOrNull()

    fun fits(text: String, maxWidth: Float, measure: (String) -> Float): Boolean = measure(text) <= maxWidth
}

/**
 * Vertical budget of the compact 2×2 card in dp: header, ring, then up to three one-line rows (state, today, schedule).
 * Type scales with the real cell height (from launcher options) and the ring takes what is left, so the card is
 * filled top to bottom with even gaps instead of leaving an empty band.
 */
data class CompactPlan(
    val ringDp: Float,
    val headerSp: Float,
    val stateSp: Float,
    val todaySp: Float,
    val captionSp: Float,
    val showToday: Boolean,
    val showSchedule: Boolean,
    /** Height the fixed content takes (header + ring + rows + gaps), for the fill check. */
    val contentDp: Float,
    val innerHeightDp: Float,
)

object CompactPlanner {
    const val PAD_V = 10f
    const val PAD_H = 22f
    const val HEADER_MIN = 24f
    const val ROW_GAP = 5f
    private const val LINE = 1.2f
    private const val RING_MAX = 150f
    private const val RING_MIN = 52f

    fun lineDp(sp: Float, fontScale: Float = 1f) = sp * fontScale * LINE

    fun plan(widthDp: Float, heightDp: Float, hasToday: Boolean, hasSchedule: Boolean, scheduleIsBar: Boolean = true, fontScale: Float = 1f): CompactPlan {
        val k = minOf((heightDp / 200f).coerceIn(0.8f, 1.35f), (widthDp / 165f).coerceAtLeast(0.8f))
        // Rows grow at half the rate of the cell so a taller cell spends its height on the ring, not on longer lines
        // that would then have to drop words.
        val t = 1f + (k - 1f) * 0.5f
        val headerSp = (13f * t).coerceIn(11f, 16f)
        val stateSp = (12.5f * t).coerceIn(10.5f, 15f)
        val todaySp = (11f * t).coerceIn(9.5f, 13f)
        val captionSp = (10.5f * t).coerceIn(9f, 12f)
        val inner = heightDp - 2 * PAD_V
        val header = maxOf(HEADER_MIN, lineDp(headerSp, fontScale))
        fun rows(today: Boolean, schedule: Boolean): Float {
            var h = lineDp(stateSp, fontScale)
            if (today) h += ROW_GAP + lineDp(todaySp, fontScale)
            if (schedule) h += ROW_GAP + if (scheduleIsBar) maxOf(9f, lineDp(captionSp, fontScale)) else lineDp(captionSp, fontScale)
            return h
        }
        // Gaps around the ring: header → ring and ring → rows.
        val ringGaps = 2 * 6f * k
        var today = hasToday
        var schedule = hasSchedule
        fun ringFor() = minOf(inner - header - rows(today, schedule) - ringGaps, widthDp - PAD_H, RING_MAX)
        if (ringFor() < RING_MIN && schedule) schedule = false
        if (ringFor() < RING_MIN && today) today = false
        val ring = ringFor().coerceAtLeast(40f)
        val content = header + ring + rows(today, schedule) + ringGaps
        return CompactPlan(ring, headerSp, stateSp, todaySp, captionSp, today, schedule, content, inner)
    }
}
