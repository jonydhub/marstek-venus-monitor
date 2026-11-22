package com.doloh.marstekwidget.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Світло за 7 днів» outage labels (port of the web `weekDayOutages` / `weekEnergyTexts` / `weekEnergyLabels` /
 * `weekSkippedLine`, keep in sync):
 * - above each day's bar: the time of every off span ≥ 60 s of that day, coral, placed by [ScheduleTimeLabels]
 *   (separate «from»/«to», else «HH:MM–HH:MM»); a span from the day's start → «до HH:MM», today's still running span
 *   (last timeline span, off, ended < 10 min ago) → «з HH:MM»;
 * - under the bar: how much the battery gave («0,42 кВт·год · 80→62 %», else «0,42 кВт·год», else skipped). Each
 *   span takes the stats outage it overlaps most; the energy is shown only on the day holding the outage's longest
 *   piece (split at Kyiv midnights), so an outage across midnight is labelled once. Placement: biggest energy first
 *   (tie: bigger SOC drop, then earlier), each text centre → flush left → flush right, no overlaps; the skipped ones
 *   and the spans without a time label above go to one muted line «07:47–08:49 — 0,16 кВт·год · 100→96 %; …» in time
 *   order. Time labels above are placed longest outage first (segment priority = minutes).
 */
object WeekOutageLabels {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private const val NBSP = '\u00A0'
    private const val MIN_SPAN_MILLIS = 60_000L
    private const val RUNNING_MILLIS = 10 * 60_000L

    /** One off span of a day: bar fractions [a]..[b], times, fixed [label] («до …» / «з …» / ""), battery used. */
    data class DayOutage(
        val a: Float,
        val b: Float,
        val from: String,
        val to: String,
        val label: String,
        val energyWh: Double = 0.0,
        val socStart: Int? = null,
        val socEnd: Int? = null,
        /** Duration in minutes: longer outages get their time label above the bar first. */
        val priority: Int = 0,
    ) {
        /** The span as a label segment above the bar (same as the «Фактично» row). */
        val segment: ScheduleBarModel.Segment get() = ScheduleBarModel.Segment(a, b, false, from, to, label, priority)
    }

    data class EnergyLayout(val labels: List<ScheduleTimeLabels.Label>, val skipped: List<DayOutage>)

    private fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(kyiv).format(clock)
    private fun midnight(t: Long) = Instant.ofEpochMilli(t).atZone(kyiv).toLocalDate().atStartOfDay(kyiv).toInstant().toEpochMilli()
    private fun nextMidnight(d: Long) = Instant.ofEpochMilli(d).atZone(kyiv).toLocalDate().plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli()
    private fun overlap(a: Long, b: Long, x: Long, y: Long) = maxOf(0L, minOf(b, y) - maxOf(a, x))

    fun weekDayOutages(day: WeekDay, statsOutages: List<OutageDetail> = emptyList(), nowMillis: Long = System.currentTimeMillis()): List<DayOutage> {
        val start = day.startMillis
        val end = day.endMillis
        val span = (end - start).toDouble().coerceAtLeast(1.0)
        val spans = day.timeline.filter { it.state == "off" }
            .map { maxOf(it.startMillis, start) to minOf(it.endMillis, end) }
            .filter { (a, b) -> b - a >= MIN_SPAN_MILLIS }
        val last = day.timeline.lastOrNull()
        return spans.mapIndexed { i, (a, b) ->
            val running = day.isToday && i == spans.lastIndex && last?.state == "off" &&
                kotlin.math.abs(last.endMillis - b) < 1000 && nowMillis - b < RUNNING_MILLIS
            val label = when {
                running -> "з ${hm(a)}"
                a <= start -> "до ${hm(b)}"
                else -> ""
            }
            // the stats outage this span belongs to, labelled only where most of it lies
            var match: Triple<OutageDetail, Long, Long>? = null
            var best = 0L
            for (o in statsOutages) {
                val ob = if (o.ongoing) nowMillis else o.endMillis
                val ov = overlap(a, b, o.startMillis, ob)
                if (ov > best) { best = ov; match = Triple(o, o.startMillis, ob) }
            }
            var energy = 0.0
            var socStart: Int? = null
            var socEnd: Int? = null
            match?.let { (o, oa, ob) ->
                var longest = 0L
                var longestDay: Long? = null
                var d = midnight(oa)
                while (d < ob) {
                    val next = nextMidnight(d)
                    val piece = overlap(oa, ob, d, next)
                    if (piece > longest) { longest = piece; longestDay = d }
                    d = next
                }
                if (longestDay == start) { energy = o.energyWh ?: 0.0; socStart = o.socStart; socEnd = o.socEnd }
            }
            DayOutage(((a - start) / span).toFloat(), ((b - start) / span).toFloat(), hm(a), hm(b), label, energy, socStart, socEnd,
                ((b - a) / 60_000L).toInt())
        }
    }

    /** [long, short] («0,42 кВт·год · 80→62 %», «0,42 кВт·год») / [«80→62 %»] without energy / [] without anything. */
    fun weekEnergyTexts(o: DayOutage): List<String> {
        val soc = if (o.socStart != null && o.socEnd != null) "${o.socStart}→${o.socEnd}$NBSP%" else null
        val kwh = o.energyWh.takeIf { it.isFinite() && it > 0 }?.let { TodayFormatter.energy(it) }
        return when {
            kwh != null && soc != null -> listOf("$kwh · $soc", kwh)
            kwh != null -> listOf(kwh)
            soc != null -> listOf(soc)
            else -> emptyList()
        }
    }

    /** Energy labels in a row [width] wide (bar from x = 0) + the outages left without one (time order). */
    fun weekEnergyLabels(outages: List<DayOutage>, width: Float, gap: Float = 4f, measure: (String) -> Float): EnergyLayout =
        weekEnergyLabels(outages, 0f, width, width, gap, measure)

    /** [barLeft]/[barWidth] = the bar's x range inside a row [totalWidth] wide (the view's side insets). */
    fun weekEnergyLabels(outages: List<DayOutage>, barLeft: Float, barWidth: Float, totalWidth: Float, gap: Float,
                         measure: (String) -> Float): EnergyLayout {
        val placed = mutableListOf<ScheduleTimeLabels.Label>()
        val skipped = mutableListOf<DayOutage>()
        fun x(f: Float) = barLeft + f.coerceIn(0f, 1f) * barWidth
        fun free(l: ScheduleTimeLabels.Label) = placed.none { it.left < l.right + gap && l.left < it.right + gap }
        fun drop(o: DayOutage) = (o.socStart ?: 0) - (o.socEnd ?: 0)
        // the biggest draw gets its place first; a tiny outage's long label must not push it off
        val order = outages.filter { weekEnergyTexts(it).isNotEmpty() }
            .sortedWith(compareByDescending<DayOutage> { it.energyWh }.thenByDescending { drop(it) }.thenBy { it.a })
        for (o in order) {
            var pick: ScheduleTimeLabels.Label? = null
            for (text in weekEnergyTexts(o)) {
                val w = measure(text)
                fun at(leftOf: Float) = ScheduleTimeLabels.Label(text, leftOf.coerceIn(0f, maxOf(0f, totalWidth - w)), w,
                    ScheduleTimeLabels.Kind.WINDOW)
                pick = listOf(at((x(o.a) + x(o.b)) / 2f - w / 2f), at(x(o.a)), at(x(o.b) - w)).firstOrNull { free(it) }
                if (pick != null) break
            }
            if (pick != null) placed += pick else skipped += o
        }
        return EnergyLayout(placed.sortedBy { it.left }, skipped.sortedBy { it.a })
    }

    /**
     * Outages missing their time label above the bar ([timeUnlabeled], from [ScheduleTimeLabels.unlabeled]) or their
     * energy label under it ([energySkipped]), in time order.
     */
    fun weekMissing(outages: List<DayOutage>, timeUnlabeled: List<ScheduleBarModel.Segment>, energySkipped: List<DayOutage>): List<DayOutage> =
        outages.filter { it.segment in timeUnlabeled || it in energySkipped }.sortedBy { it.a }

    /**
     * One muted line under the bar, no prefix: «07:47–08:49 — 0,16 кВт·год · 100→96 %; до 00:40 — 0,09 кВт·год · 100→97 %»
     * (energy part omitted without energy/SOC); null when nothing is missing.
     */
    fun weekMissingLine(missing: List<DayOutage>): String? {
        if (missing.isEmpty()) return null
        return missing.sortedBy { it.a }.joinToString("; ") { o ->
            val time = o.label.ifEmpty { "${o.from}–${o.to}" }
            weekEnergyTexts(o).firstOrNull()?.let { "$time — $it" } ?: time
        }
    }
}
