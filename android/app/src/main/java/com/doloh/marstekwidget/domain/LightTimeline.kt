package com.doloh.marstekwidget.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Фактично» row under today's schedule (port of the web `actualDay`, keep in sync): today's confirmed light timeline
 * as fractions of the day. on = light was on (green), off = none (coral), unknown = no data (dark); after [nowFraction]
 * the track stays grey (future). [text] = «Фактично: Світла нема (з 21:51)» / «Фактично: Світло є» / «Фактично: немає даних».
 */
data class ActualDay(val segments: List<Segment>, val nowFraction: Float, val state: String, val text: String) {
    data class Segment(val start: Float, val end: Float, val state: String)
}

object ActualDays {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    /** The newest span must reach this close to now to name the current state (else «немає даних»). */
    private const val FRESH_MILLIS = 10 * 60_000L

    fun day(timeline: List<LightSpan>?, nowMillis: Long): ActualDay? {
        timeline ?: return null
        val date = Instant.ofEpochMilli(nowMillis).atZone(kyiv).toLocalDate()
        val start = date.atStartOfDay(kyiv).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(kyiv).toInstant().toEpochMilli()
        val span = (end - start).toDouble()
        val clipped = timeline.mapNotNull { s ->
            val a = maxOf(s.startMillis, start)
            val b = minOf(s.endMillis, nowMillis, end)
            if (b > a) Triple(a, b, s.state) else null
        }.sortedBy { it.first }
        val last = clipped.lastOrNull()
        val known = last?.takeIf { it.third != "unknown" && nowMillis - it.second < FRESH_MILLIS }
        val word = when (known?.third) {
            "on" -> "Світло є"
            "off" -> "Світла нема"
            else -> "немає даних"
        }
        val since = known?.takeIf { it.first > start }?.let { " (з ${Instant.ofEpochMilli(it.first).atZone(kyiv).format(clock)})" }.orEmpty()
        return ActualDay(
            segments = clipped.map { (a, b, state) -> ActualDay.Segment(((a - start) / span).toFloat(), ((b - start) / span).toFloat(), state) },
            nowFraction = ((nowMillis - start) / span).coerceIn(0.0, 1.0).toFloat(),
            state = known?.third ?: "unknown",
            text = "Фактично: $word$since",
        )
    }

    /** «За графіком: …» for today's row: the schedule's verdict for right now (never the measured light). */
    fun scheduleNowText(info: PowerOutageInfo?, now: ZonedDateTime): String? {
        info ?: return null
        if (!info.scheduleEnabled || info.scheduleUnavailable) return null
        if (info.emergency) return "За графіком: аварійні відключення"
        fun parse(v: String?) = v?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(kyiv) }.getOrNull() }
        val curStart = parse(info.currentStart)
        val curEnd = parse(info.currentEnd)
        if (curEnd != null && (curStart == null || !now.isBefore(curStart)) && now.isBefore(curEnd)) {
            return "За графіком: відключення до ${curEnd.format(clock)}"
        }
        if (!info.schedulePublished) return "За графіком: графік не опубліковано"
        val next = parse(info.nextStart)
        val today = now.withZoneSameInstant(kyiv).toLocalDate()
        return if (next != null && next.toLocalDate() == today) "За графіком: світло є, відключення о ${next.format(clock)}"
        else "За графіком: світло є"
    }
}

/**
 * Chart markers (port of the web `clusterChartEvents`, keep in sync): an event closer than [minGapMillis] (~20 min) to the
 * first event of the current cluster, or whose marker would overlap that cluster's marker ([markerPx] at `x(t)`), joins
 * it; the marker sits at the cluster's first event and shows the count. The kind shown is the most important one
 * (trip › light lost › light back › charging › full).
 */
data class ChartCluster(val epochMillis: Long, val kind: String, val events: List<ChartEvent>) {
    val count: Int get() = events.size
}

object ChartEventClusters {
    val PRIORITY = listOf("anomaly_overload_trip", "light_lost", "light_returned", "charging_started", "charged_full")
    const val MIN_GAP_MILLIS = 20 * 60_000L

    fun cluster(events: List<ChartEvent>, markerPx: Float, minGapMillis: Long = MIN_GAP_MILLIS, x: (Long) -> Float): List<ChartCluster> {
        val groups = mutableListOf<MutableList<ChartEvent>>()
        events.filter { it.kind in PRIORITY }.sortedBy { it.epochMillis }.forEach { e ->
            val last = groups.lastOrNull()?.first()
            if (last != null && (e.epochMillis - last.epochMillis < minGapMillis || x(e.epochMillis) - x(last.epochMillis) < markerPx)) {
                groups.last() += e
            } else {
                groups += mutableListOf(e)
            }
        }
        return groups.map { g ->
            ChartCluster(g.first().epochMillis, PRIORITY.first { k -> g.any { it.kind == k } }, g)
        }
    }

    fun title(kind: String): String = when (kind) {
        "light_lost" -> "світло зникло"
        "light_returned" -> "світло повернулось"
        "charging_started" -> "почалось заряджання"
        "charged_full" -> "заряджено"
        "anomaly_overload_trip" -> "захист від перевантаження?"
        else -> kind
    }

    /** «21:46–22:15 · 5 подій (захист від перевантаження?)» / «22:15 · світло повернулось» — legend lines under the chart. */
    fun legend(clusters: List<ChartCluster>, zone: ZoneId = ZoneId.of("Europe/Kyiv")): List<String> {
        val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(zone).format(clock)
        return clusters.filter { it.count > 1 }.map { c ->
            "${hm(c.events.first().epochMillis)}–${hm(c.events.last().epochMillis)} · ${c.count} ${plural(c.count)}: " +
                c.events.joinToString(", ") { title(it.kind) }
        }
    }

    private fun plural(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "подія"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "події"
        else -> "подій"
    }
}
