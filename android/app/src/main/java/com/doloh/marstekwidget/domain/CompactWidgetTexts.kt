package com.doloh.marstekwidget.domain

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
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

    /** «1 год», «2 год 5 хв», «57 хв», «0 хв» — the exact today duration with no-break spaces (full unit words). */
    fun duration(seconds: Long?): String = TodayFormatter.short(seconds ?: 0L)

    /** 2×2 today row: only the no-light total, «Сьогодні без світла 1 хв» (never «З 1г · Р 2г»). */
    fun todayCandidates(today: TodayDurations?): List<String> = TodayFormatter.outageCandidates(today)

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
     * Power row under the ring — the current power is always there, joined with the runtime/ETA while it fits:
     * outage «1330 Вт · вистачить 3 год 22 хв» → «1330 Вт · 3 год 22 хв» → «Споживання 1330 Вт»; charging
     * «Заряд ≈ 1,2 кВт · 100% за 1 год» → «Заряд ≈ 1,2 кВт»; discharging with grid «Розряд ≈ 300 Вт»; idle «Очікування».
     * Ordered longest-first; priority power > runtime, so the last candidates keep the power and drop the time.
     */
    fun stateCandidates(summary: WidgetSummary, snapshot: BatterySnapshot, now: ZonedDateTime = ZonedDateTime.now(kyiv)): List<String> {
        val info = snapshot.powerOutage
        return when (summary.mode) {
            SummaryMode.OUTAGE -> {
                val backup = WidgetSummaryResolver.backupLoadWatts(snapshot)?.let { WidgetSummaryResolver.formatWatts(it) }
                val runtime = info?.runtimeSeconds ?: WidgetSummaryResolver.dischargeEtaSeconds(snapshot)
                    ?.takeIf { snapshot.direction == BatteryDirection.DISCHARGING }
                if (runtime == null) {
                    return backup?.let { listOf("Споживання $it$SEP" + "оцінюю запас…", "Споживання $it", it) }
                        ?: listOf("Запас: оцінюю розряд…", "Запас: оцінюю…")
                }
                val remaining = info?.currentEnd?.let { runCatching { java.time.Duration.between(now, OffsetDateTime.parse(it)).seconds }.getOrNull() }
                    ?.takeIf { it > 0 }
                val covers = info?.coversOutage ?: remaining?.let { runtime >= it }
                val word = if (covers == false) "лише" else "вистачить"
                if (backup == null) {
                    val verdict = when (covers) { true -> "Вистачить"; false -> "Не вистачить"; null -> "Запас" }
                    return listOf("$verdict ≈ ${exact(runtime)}", "$verdict ≈ ${roughly(runtime)}", "$verdict ${roughly(runtime)}")
                }
                listOf(
                    "Споживання $backup$SEP$word ${exact(runtime)}",
                    "$backup$SEP$word ${exact(runtime)}",
                    "$backup$SEP${exact(runtime)}",
                    "$backup$SEP≈ ${roughly(runtime)}",
                    "Споживання $backup",
                    backup,
                ).distinct()
            }
            SummaryMode.CHARGING, SummaryMode.DISCHARGING -> {
                val charging = summary.mode == SummaryMode.CHARGING
                val word = if (charging) "Заряд" else "Розряд"
                val approx = if (snapshot.batteryPowerEstimated) " ≈" else ""
                val power = snapshot.batteryPowerWatts?.takeIf(Double::isFinite)?.let { WidgetSummaryResolver.formatKw(it) }
                val eta = (if (charging) snapshot.etaSeconds else WidgetSummaryResolver.dischargeEtaSeconds(snapshot))?.takeIf { it > 0 }
                val target = if (charging) "100%" else WidgetSummaryResolver.floorTarget(info)
                val head = power?.let { "$word$approx $it" }
                listOfNotNull(
                    if (head != null && eta != null) "$head$SEP$target за ${exact(eta)}" else null,
                    if (head != null && eta != null) "$head$SEP${exact(eta)}" else null,
                    if (head != null && eta != null) "$head$SEP≈ ${roughly(eta)}" else null,
                    head,
                    power?.let { "$word $it" },
                    if (head == null && eta != null) "$word$SEP$target за ${exact(eta)}" else null,
                    if (head == null && eta != null) "До $target$SEP≈ ${roughly(eta)}" else null,
                    if (head == null) word else null,
                ).distinct()
            }
            SummaryMode.IDLE, SummaryMode.UNKNOWN -> {
                val state = if (summary.mode == SummaryMode.IDLE) "Очікування" else "Стан невідомий"
                val full = summary.mode == SummaryMode.IDLE && (snapshot.socPercent ?: 0) >= 99
                val runtime = info?.runtimeSeconds
                when {
                    full -> listOf("$state${SEP}Заряджено", state)
                    runtime != null -> listOf("$state${SEP}запас ≈ ${exact(runtime)}", "$state$SEP≈ ${roughly(runtime)}", state)
                    else -> listOf(state)
                }
            }
        }
    }

    /** What the schedule row of a 2×2 shows: a today bar with a short caption, or one text line (longest-first [captions]). */
    data class ScheduleRow(val showBar: Boolean, val captions: List<String>) {
        val caption: String? get() = captions.firstOrNull()
    }

    /**
     * Today's schedule row. Text only: not published «Графік не опубліковано» → «Графіка нема»; while the light is off
     * and the schedule names the end, «Світло за графіком о 21:00». Otherwise the bar plus a short caption — «відкл. до 21:00»
     * during a window with the light on, «відкл. 14:00» before today's next window, «без відкл.» otherwise.
     * Null when no schedule is configured (the row is dropped).
     */
    fun scheduleRow(info: PowerOutageInfo?, today: DaySchedule?, now: ZonedDateTime = ZonedDateTime.now(kyiv)): ScheduleRow? {
        info ?: return null
        if (!info.scheduleEnabled) return null
        if (info.scheduleUnavailable) return ScheduleRow(false, listOf("Графік недоступний", "Графіка нема"))
        if (info.emergency) return ScheduleRow(false, listOf("Аварійні відключення", "Аварійні"))
        fun at(value: String?) = value?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(kyiv) }.getOrNull() }
        val end = at(info.currentEnd)
        if (end != null && info.gridState == GridState.OFF) {
            val hhmm = end.format(clock)
            return ScheduleRow(false, listOf("Світло за графіком о $hhmm", "За графіком о $hhmm", "Світло о $hhmm"))
        }
        if (today == null || !today.published) return ScheduleRow(false, listOf("Графік не опубліковано", "Графіка нема"))
        val next = at(info.nextStart)
        val todayDate = now.withZoneSameInstant(kyiv).toLocalDate()
        val caption = when {
            end != null -> "відкл. до ${end.format(clock)}"
            next != null && next.toLocalDate() == todayDate -> "відкл. ${next.format(clock)}"
            today.definite.isEmpty() -> "без відкл."
            else -> null
        }
        return ScheduleRow(true, listOfNotNull(caption))
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
