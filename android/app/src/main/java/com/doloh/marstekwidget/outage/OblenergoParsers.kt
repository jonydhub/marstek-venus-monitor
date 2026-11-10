package com.doloh.marstekwidget.outage

import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/*
 * Direct official schedules from the Kharkiv, Poltava, Zaporizhzhia and Chernivtsi oblenergo sites.
 *
 * Parsers ported from hukpo/ua-outages (MIT License, Copyright (c) Pavlo Huk,
 * https://github.com/hukpo/ua-outages): src/providers/{kharkiv,poltava,zaporizhzhia,chernivtsi}.ts and the
 * shared helpers src/providers/posts.ts, src/providers/ua-text.ts, src/schedule.ts. That project's hosted
 * aggregate cannot reach these sites from abroad; from a Ukrainian connection they answer normally.
 */

/** A span inside one day in minutes from Kyiv midnight (end may be 1440). */
data class DaySlot(val start: Int, val end: Int, val maybe: Boolean = false)

/** [status]: 's' scheduled, 'n' no outages that day, 'u' not published for this group. */
data class DayEntry(val status: Char, val slots: List<DaySlot>)

data class RegionDays(val groups: List<String>, val days: Map<LocalDate, Map<String, DayEntry>>, val updatedAt: String?) {
    /** The queue's schedule from [today] on; throws when the operator lists groups and [queue] is not one. */
    fun toSchedule(source: OutageSourceKind, queue: String, today: LocalDate): OutageSchedule {
        if (groups.isNotEmpty() && queue !in groups) throw OutageSourceException("queue $queue not found")
        val windows = mutableListOf<OutageWindow>()
        val dates = mutableListOf<LocalDate>()
        for ((date, perGroup) in days) {
            if (date.isBefore(today)) continue
            val day = perGroup[queue] ?: continue
            if (day.status == 'u') continue
            dates += date
            day.slots.forEach { windows += OutageWindow(kyivAt(date, it.start), kyivAt(date, it.end), it.maybe) }
        }
        return OutageSchedule(source, queue, OutageWindowMerger.merge(windows), dates.distinct().sorted(), updatedAt = updatedAt)
    }
}

/** Ukrainian prose helpers: «5 листопада 2026», «1.1: 08:00 – 12:00», «Група 1.1 … з 08:00 до 12:00». */
object UaScheduleText {
    private val MONTHS = linkedMapOf(
        "січня" to 1, "лютого" to 2, "березня" to 3, "квітня" to 4, "травня" to 5, "червня" to 6,
        "липня" to 7, "серпня" to 8, "вересня" to 9, "жовтня" to 10, "листопада" to 11, "грудня" to 12,
    )
    private val uk = Locale.forLanguageTag("uk-UA")
    private val named = Regex("(\\d{1,2})\\s+(${MONTHS.keys.joinToString("|")})(?:\\s+(\\d{4}))?")
    private val numeric = Regex("\\b(\\d{1,2})\\.(\\d{2})(?:\\.(\\d{4}))?\\b")
    private const val TIME = "(\\d{1,2}[:.]\\d{2})"
    private val range = Regex("(?:з\\s*)?$TIME\\s*(?:[-–—−]|до|по)\\s*$TIME", RegexOption.IGNORE_CASE)
    private val firstTime = Regex("\\d{1,2}[:.]\\d{2}\\s*(?:[-–—−]|до|по)", RegexOption.IGNORE_CASE)
    private val group = Regex("(?<![\\d:.])(\\d{1,2})\\s*[.,]\\s*([12])(?!\\d)")
    private val lineSplit = Regex("\\n|<br\\s*/?>|</p>|</li>|</div>", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]+>")

    fun stripTags(s: String) = tag.replace(s, " ")

    /** First date in the text; a year-less one goes to the year that puts it closest to [today]. */
    fun findDate(text: String, today: LocalDate): LocalDate? {
        val lower = text.lowercase(uk)
        val n = named.find(lower)
        val d = numeric.findAll(lower).firstOrNull { m ->
            m.groupValues[2].toInt() in 1..12 && m.groupValues[1].toInt() in 1..31
        }
        val (day, month, year) = when {
            n != null && (d == null || n.range.first <= d.range.first) ->
                Triple(n.groupValues[1].toInt(), MONTHS.getValue(n.groupValues[2]), n.groupValues[3].toIntOrNull())
            d != null -> Triple(d.groupValues[1].toInt(), d.groupValues[2].toInt(), d.groupValues[3].toIntOrNull())
            else -> return null
        }
        val years = if (year != null) listOf(year) else listOf(today.year - 1, today.year, today.year + 1)
        return years.mapNotNull { runCatching { LocalDate.of(it, month, day) }.getOrNull() }
            .minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
    }

    fun parseClock(text: String): Int? {
        val m = Regex("\\s*(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\s*").matchEntire(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (min > 59 || h > 24 || (h == 24 && min != 0)) return null
        return h * 60 + min
    }

    /** Every «HH:MM–HH:MM» / «з HH:MM до HH:MM»; an end of 23:59, 00:00 or before the start means midnight. */
    fun timeRanges(text: String): List<Pair<Int, Int>> = range.findAll(text).mapNotNull { m ->
        val a = parseClock(m.groupValues[1]) ?: return@mapNotNull null
        var b = parseClock(m.groupValues[2]) ?: return@mapNotNull null
        if (b == 1439 || b == 0 || b <= a) b = 1440
        a to b
    }.toList()

    /** «GPV1.1», «Черга 1.1», «1 черга 2 підчерга», «4» → «1.1», «1.1», «1.2», «4». */
    fun normalizeGroup(raw: String): String? {
        val s = raw.lowercase(uk)
        Regex("(\\d+)\\s*(?:черга|queue)?\\D{0,12}?(\\d+)\\s*під").find(s)?.let {
            return "${it.groupValues[1].toInt()}.${it.groupValues[2].toInt()}"
        }
        Regex("(\\d+)\\s*[.,_-]\\s*(\\d+)").find(s)?.let { return "${it.groupValues[1].toInt()}.${it.groupValues[2].toInt()}" }
        return Regex("(\\d+)").find(s)?.let { it.groupValues[1].toInt().toString() }
    }

    /** Lines like «1.1 00:00-03:00; 08:00-13:30» → group → off slots (several groups on one line share them). */
    fun groupLines(text: String, maybe: Boolean = false): Map<String, List<DaySlot>> {
        val out = linkedMapOf<String, MutableList<DaySlot>>()
        for (raw in text.split(lineSplit)) {
            val line = stripTags(raw).replace("&nbsp;", " ").trim()
            if (line.isEmpty()) continue
            val first = firstTime.find(line)
            val head = if (first == null) line else line.substring(0, first.range.first)
            val groups = group.findAll(head).mapNotNull { normalizeGroup("${it.groupValues[1]}.${it.groupValues[2]}") }.toList()
            if (groups.isEmpty()) continue
            val ranges = if (first == null) emptyList() else timeRanges(line.substring(first.range.first))
            for (g in groups) out.getOrPut(g) { mutableListOf() } += ranges.map { DaySlot(it.first, it.second, maybe) }
        }
        return out
    }

    /** Sort «1.1» < «1.2» < «2.1» < «10.1». */
    val groupOrder: Comparator<String> = Comparator { a, b ->
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return@Comparator c
        }
        0
    }

    /** Merge slots of one day; on overlap a definite outage beats a possible one. */
    fun merge(slots: List<DaySlot>): List<DaySlot> {
        val level = IntArray(1440)
        for (s in slots) {
            val v = if (s.maybe) 1 else 2
            for (i in s.start.coerceIn(0, 1440) until s.end.coerceIn(0, 1440)) if (level[i] < v) level[i] = v
        }
        val out = mutableListOf<DaySlot>()
        var i = 0
        while (i < 1440) {
            if (level[i] == 0) { i++; continue }
            var j = i
            while (j < 1440 && level[j] == level[i]) j++
            out += DaySlot(i, j, maybe = level[i] == 1)
            i = j
        }
        return out
    }

    /** Equal cells (24 hourly or 48 half-hourly): null = power on, false = off, true = maybe. */
    fun cells(cells: List<Boolean?>): List<DaySlot> {
        val size = 1440 / cells.size
        return merge(cells.mapIndexedNotNull { i, c -> c?.let { DaySlot(i * size, (i + 1) * size, maybe = it) } })
    }
}

object OblenergoParsers {
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    val KHARKIV_URL = "https://oblenergo.kharkiv.ua/uk/jsonapi/node/newsforlenta?" + listOf(
        "sort" to "-created",
        "page[limit]" to "10",
        "fields[node--newsforlenta]" to "title,created,changed,body",
        "filter[t][condition][path]" to "title",
        "filter[t][condition][operator]" to "CONTAINS",
        "filter[t][condition][value]" to "погодинн",
    ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    const val POLTAVA_URL = "https://www.poe.pl.ua/customs/dynamicgpv-info.php"
    const val ZAPORIZHZHIA_URL = "https://www.zoe.com.ua/outage/"
    const val CHERNIVTSI_URL = "https://oblenergo.cv.ua/shutdowns/"
    const val CHERNIVTSI_NEXT_URL = "https://oblenergo.cv.ua/shutdowns/?next"
    const val CHERNIVTSI_NEXT_LINK = "href=\"/shutdowns/?next\""

    /** A news post: [rank] orders versions (higher = newer), [ref] is the day it was published. */
    data class Post(val title: String, val body: String, val rank: Long, val ref: LocalDate, val updatedAt: String?)

    /** Newest post per date wins; posts without group lines are ignored; only yesterday and later is kept. */
    fun postsToDays(posts: List<Post>, today: LocalDate): RegionDays {
        val byDate = sortedMapOf<LocalDate, Pair<Post, Map<String, List<DaySlot>>>>()
        for (p in posts) {
            val date = UaScheduleText.findDate(p.title, p.ref)
                ?: UaScheduleText.findDate(UaScheduleText.stripTags(p.body).take(600), p.ref) ?: continue
            if (date.isBefore(today.minusDays(1)) || abs(ChronoUnit.DAYS.between(p.ref, date)) > 3) continue
            val groups = UaScheduleText.groupLines(p.body)
            if (groups.isEmpty()) continue
            val prev = byDate[date]
            if (prev == null || p.rank > prev.first.rank) byDate[date] = p to groups
        }
        val all = byDate.values.flatMap { it.second.keys }.distinct().sortedWith(UaScheduleText.groupOrder)
        val days = byDate.mapValues { (_, v) ->
            all.associateWith { g -> v.second[g]?.let { DayEntry('s', UaScheduleText.merge(it)) } ?: DayEntry('u', emptyList()) }
        }
        val newest = byDate.values.map { it.first }.filter { it.updatedAt != null }.maxByOrNull { it.rank }?.updatedAt
        return RegionDays(all, days, newest)
    }

    /** Kharkivoblenergo: Drupal JSON:API list of «погодинні відключення» news posts. */
    fun kharkiv(text: String, today: LocalDate): RegionDays {
        val data = runCatching { JSONObject(text).getJSONArray("data") }.getOrElse { throw OutageSourceException("Kharkiv: no data list") }
        val posts = (0 until data.length()).map { i ->
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: JSONObject()
            val stamp = a.optString("changed").ifBlank { a.optString("created") }
            val at = runCatching { OffsetDateTime.parse(stamp).atZoneSameInstant(KYIV) }.getOrNull()
            Post(
                title = a.optString("title"),
                body = a.optJSONObject("body")?.optString("value").orEmpty(),
                rank = at?.toEpochSecond() ?: Long.MIN_VALUE,
                ref = at?.toLocalDate() ?: today,
                updatedAt = at?.format(iso),
            )
        }
        return postsToDays(posts, today)
    }

    private fun decodeEntities(s: String) = s
        .replace(Regex("&#8211;|&ndash;"), "–").replace(Regex("&#8212;|&mdash;"), "—")
        .replace(Regex("&nbsp;|&#160;"), " ").replace(Regex("&#039;|&#8217;|&rsquo;"), "’").replace("&amp;", "&")

    /** Zaporizhzhiaoblenergo: WordPress archive, newest post first; corrections are new posts. */
    fun zaporizhzhia(html: String, today: LocalDate): RegionDays {
        val articles = Regex("<article\\b[^>]*>([\\s\\S]*?)</article>").findAll(html).map { it.groupValues[1] }.toList()
        if (articles.isEmpty()) throw OutageSourceException("Zaporizhzhia: no posts on the page")
        val posts = articles.mapIndexed { i, body ->
            val title = Regex("<h2[^>]*>([\\s\\S]*?)</h2>").find(body)?.groupValues?.get(1).orEmpty()
            Post(decodeEntities(title), decodeEntities(body), rank = -i.toLong(), ref = today, updatedAt = null)
        }
        return postsToDays(posts, today).copy(updatedAt = null) // the page gives no edit times
    }

    /** Poltavaoblenergo: HTML fragment, a 48-cell table per group and day (gpv-s1 on, s2 off, s3 switching). */
    fun poltava(html: String, today: LocalDate): RegionDays {
        val blocks = html.split("<div class=\"gpvinfodetail\"").drop(1)
        if (blocks.isEmpty() && Regex("gpv-table|turnoff-scheduleui-table").containsMatchIn(html)) {
            throw OutageSourceException("Poltava: schedule table without day blocks")
        }
        val days = sortedMapOf<LocalDate, MutableMap<String, DayEntry>>()
        val groups = mutableSetOf<String>()
        for (block in blocks) {
            val date = UaScheduleText.findDate(UaScheduleText.stripTags(block.take(2000)), today)
                ?: throw OutageSourceException("Poltava: no date in a schedule block")
            val table = Regex("<table[^>]*class=\"gpv-table\"[^>]*>([\\s\\S]*?)</table>").find(block)?.groupValues?.get(1) ?: continue
            for (row in Regex("<tr[^>]*>([\\s\\S]*?)</tr>").findAll(table)) {
                val inner = row.groupValues[1]
                val name = Regex("<td class=\"gpv-q\"[^>]*>([^<]+)</td>").find(inner)?.groupValues?.get(1) ?: continue
                val group = UaScheduleText.normalizeGroup(name) ?: continue
                val cells = Regex("<td class=\"gpv-c ([^\"]*)\"").findAll(inner).map { c ->
                    when (Regex("\\bgpv-(s\\d)\\b").find(c.groupValues[1])?.groupValues?.get(1)) {
                        "s2" -> false
                        "s3" -> true
                        else -> null
                    }
                }.toList()
                if (cells.size != 48 && cells.size != 24) throw OutageSourceException("Poltava: group $group has ${cells.size} cells")
                days.getOrPut(date) { linkedMapOf() }[group] = DayEntry('s', UaScheduleText.cells(cells))
                groups += group
            }
        }
        return RegionDays(groups.sortedWith(UaScheduleText.groupOrder), days, null)
    }

    /** One Chernivtsioblenergo page (`/shutdowns/` today, `?next` tomorrow): groups 1–12, 48 cells each. */
    fun chernivtsiPage(html: String): Triple<LocalDate, Map<String, DayEntry>, String?> {
        val start = html.indexOf("<div id=\"gsv\"")
        if (start < 0) throw OutageSourceException("Chernivtsi: no schedule block")
        val block = html.substring(start)
        val d = Regex("<ul>\\s*<p>\\s*(\\d{2})\\.(\\d{2})\\.(\\d{4})").find(block)
            ?: throw OutageSourceException("Chernivtsi: no date in the schedule block")
        val date = LocalDate.of(d.groupValues[3].toInt(), d.groupValues[2].toInt(), d.groupValues[1].toInt())
        val rows = Regex("<div id=\"inf(\\d+)\"[^>]*>([\\s\\S]*?)</div>").findAll(block).toList()
        if (rows.isEmpty()) throw OutageSourceException("Chernivtsi: no group rows")
        var anyOutage = false
        val groups = linkedMapOf<String, DayEntry>()
        for (row in rows) {
            val cells = Regex("<(u|o|s)>[^<]*</\\1>").findAll(row.groupValues[2]).map {
                when (it.groupValues[1]) { "o" -> false; "s" -> true; else -> null }
            }.toList()
            if (cells.size != 48) throw OutageSourceException("Chernivtsi: group ${row.groupValues[1]} has ${cells.size} cells")
            if (cells.any { it != null }) anyOutage = true
            groups[row.groupValues[1].toInt().toString()] = DayEntry('s', UaScheduleText.cells(cells))
        }
        val result = if (anyOutage) groups else groups.mapValues { DayEntry('n', emptyList()) }
        return Triple(date, result, chernivtsiUpdatedAt(html))
    }

    /** «актуальний станом на <b>23:30</b>» + «Сторінку відкрито 05.11.2026 о 23:46»; a later time is the day before. */
    private fun chernivtsiUpdatedAt(html: String): String? {
        val at = Regex("актуальний станом на\\s*<b>\\s*(\\d{1,2}):(\\d{2})\\s*</b>").find(html) ?: return null
        val opened = Regex("Сторінку відкрито\\s*(\\d{2})\\.(\\d{2})\\.(\\d{4})\\s*о\\s*(\\d{1,2}):(\\d{2})").find(html) ?: return null
        val minutes = at.groupValues[1].toInt() * 60 + at.groupValues[2].toInt()
        var day = LocalDate.of(opened.groupValues[3].toInt(), opened.groupValues[2].toInt(), opened.groupValues[1].toInt())
        if (minutes > opened.groupValues[4].toInt() * 60 + opened.groupValues[5].toInt()) day = day.minusDays(1)
        return kyivAt(day, minutes).format(iso)
    }

    fun chernivtsi(pages: List<String>): RegionDays {
        val days = sortedMapOf<LocalDate, Map<String, DayEntry>>()
        val groups = mutableSetOf<String>()
        var updated: String? = null
        for (page in pages) {
            val (date, perGroup, at) = chernivtsiPage(page)
            days[date] = perGroup
            groups += perGroup.keys
            if (at != null && (updated == null || at > updated)) updated = at
        }
        return RegionDays(groups.sortedWith(UaScheduleText.groupOrder), days, updated)
    }
}
