package com.doloh.marstekwidget.outage

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class LoeDay(val date: LocalDate, val updatedAt: String?, val groups: Map<String, List<OutageWindow>>)

/** Lvivoblenergo open API (GPV text in `rawHtml` of the Today/Tomorrow menu items). Port of server outage.py. */
object LoeParser {
    const val URL = "https://api.loe.lviv.ua/api/menus?page=1&type=photo-grafic"
    private val dateRe = Regex("""відключень\s+на\s+(\d{2})\.(\d{2})\.(\d{4})""")
    private val updatedRe = Regex("""станом\s+на\s+(\d{1,2}):(\d{2})\s+(\d{2})\.(\d{2})\.(\d{4})""")
    private val groupRe = Regex("""Група\s+(\d+\.\d+)\.\s*([^<]*)""")
    private val intervalRe = Regex("""з\s+(\d{1,2}):(\d{2})\s+до\s+(\d{1,2}):(\d{2})""")

    fun parseGpvHtml(rawHtml: String?): LoeDay? {
        if (rawHtml.isNullOrBlank()) return null
        val text = rawHtml.replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        val d = dateRe.find(text) ?: return null
        val date = runCatching { LocalDate.of(d.groupValues[3].toInt(), d.groupValues[2].toInt(), d.groupValues[1].toInt()) }.getOrNull()
            ?: return null
        val updatedAt = updatedRe.find(text)?.let { m ->
            val (h, min, day, month, year) = m.destructured
            runCatching {
                LocalDate.of(year.toInt(), month.toInt(), day.toInt()).atTime(h.toInt(), min.toInt()).atZone(KYIV).toOffsetDateTime().toString()
            }.getOrNull()
        }
        val groups = linkedMapOf<String, List<OutageWindow>>()
        groupRe.findAll(text).forEach { g ->
            val windows = intervalRe.findAll(g.groupValues[2]).mapNotNull { m ->
                val (h1, m1, h2, m2) = m.destructured
                val start = kyivAt(date, h1.toInt() * 60 + m1.toInt())
                val end = kyivAt(date, h2.toInt() * 60 + m2.toInt())
                if (end.isAfter(start)) OutageWindow(start, end) else null
            }.toList()
            groups[g.groupValues[1]] = windows
        }
        if (groups.isEmpty()) return null
        return LoeDay(date, updatedAt, groups)
    }

    fun parseMenus(text: String, queue: String): OutageSchedule {
        val menus: JSONArray = runCatching {
            val trimmed = text.trimStart()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("hydra:member") ?: JSONArray()
        }.getOrElse { throw OutageSourceException("bad JSON") }
        val days = mutableListOf<LoeDay>()
        for (i in 0 until menus.length()) {
            val items = menus.optJSONObject(i)?.optJSONArray("menuItems") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                if (item.optString("name") !in setOf("Today", "Tomorrow")) continue
                parseGpvHtml(item.optString("rawHtml").takeIf { !item.isNull("rawHtml") })?.let(days::add)
            }
        }
        val distinct = days.distinctBy { it.date }.sortedBy { it.date }
        return OutageSchedule(
            source = OutageSourceKind.LOE,
            queue = queue,
            windows = OutageWindowMerger.merge(distinct.flatMap { it.groups[queue].orEmpty() }),
            publishedDates = distinct.map { it.date },
            updatedAt = distinct.mapNotNull { it.updatedAt }.maxOrNull(),
        )
    }
}
