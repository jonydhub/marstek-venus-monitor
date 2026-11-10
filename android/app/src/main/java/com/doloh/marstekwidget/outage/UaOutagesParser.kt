package com.doloh.marstekwidget.outage

import org.json.JSONObject
import java.time.LocalDate

/**
 * hukpo/ua-outages (MIT) static aggregate of official operator data, rebuilt every ~15 min:
 * days.{date}.{group} = {s: s|n|e|u, i: [[startMin, endMin, kind]]}, kind 0 = off, 1 = maybe.
 */
object UaOutagesParser {
    const val URL = "https://hukpo.github.io/ua-outages/data/now.json"

    private fun region(text: String, regionId: String): JSONObject {
        val root = runCatching { JSONObject(text) }.getOrElse { throw OutageSourceException("bad JSON") }
        val regions = root.optJSONArray("regions") ?: throw OutageSourceException("no regions")
        for (i in 0 until regions.length()) {
            val r = regions.optJSONObject(i) ?: continue
            if (r.optString("id") == regionId) return r
        }
        throw OutageSourceException("region $regionId missing")
    }

    fun parse(text: String, regionId: String, queue: String): OutageSchedule {
        val r = region(text, regionId)
        if (!r.optBoolean("ok", false)) throw OutageSourceException(r.optString("message").ifBlank { "source failed" })
        val days = r.optJSONObject("days") ?: JSONObject()
        val windows = mutableListOf<OutageWindow>()
        val dates = mutableListOf<LocalDate>()
        var emergency = false
        days.keys().forEach { key ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
            val day = days.optJSONObject(key)?.optJSONObject(queue) ?: return@forEach
            when (day.optString("s")) {
                "s" -> {
                    dates += date
                    val list = day.optJSONArray("i")
                    if (list != null) for (i in 0 until list.length()) {
                        val iv = list.optJSONArray(i) ?: continue
                        windows += OutageWindow(kyivAt(date, iv.optInt(0)), kyivAt(date, iv.optInt(1)), maybe = iv.optInt(2) == 1)
                    }
                }
                "n" -> dates += date
                "e" -> emergency = true
            }
        }
        return OutageSchedule(
            source = OutageSourceKind.UA_OUTAGES,
            queue = queue,
            windows = OutageWindowMerger.merge(windows),
            publishedDates = dates.distinct().sorted(),
            emergency = emergency,
            updatedAt = r.optString("updatedAt").takeIf { it.isNotBlank() && !r.isNull("updatedAt") },
        )
    }

    fun groups(text: String, regionId: String): List<String> = runCatching {
        val arr = region(text, regionId).optJSONArray("groups") ?: return emptyList()
        (0 until arr.length()).map { arr.getString(it) }.sortedWith(SvitloCatalog.queueComparator)
    }.getOrDefault(emptyList())
}
