package com.doloh.marstekwidget.outage

import org.json.JSONObject
import java.time.OffsetDateTime

sealed interface OfficialSource {
    data object Loe : OfficialSource
    data class Yasno(val regionId: Int, val dsoId: Int) : OfficialSource {
        val url: String get() = "https://app.yasno.ua/api/blackout-service/public/shutdowns/regions/$regionId/dsos/$dsoId/planned-outages"
    }
}

/**
 * Direct official sources with a stable open API. DTEK regional sites are behind bot protection and are
 * deliberately not scraped; those regions use the aggregators.
 */
object OfficialSources {
    fun forRegion(regionId: String): OfficialSource? = when (regionId) {
        "lviv" -> OfficialSource.Loe
        "kyiv" -> OfficialSource.Yasno(25, 902)
        "dnipro" -> OfficialSource.Yasno(3, 301)
        "dnipro-cek" -> OfficialSource.Yasno(3, 303)
        else -> null
    }
}

/** YASNO public planned-outages JSON: {queue: {today|tomorrow: {slots:[{start,end,type}], date, status}, updatedOn}}. */
object YasnoParser {
    fun parse(text: String, queue: String): OutageSchedule {
        val root = runCatching { JSONObject(text) }.getOrElse { throw OutageSourceException("bad JSON") }
        val group = root.optJSONObject(queue)
            ?: return OutageSchedule(OutageSourceKind.YASNO, queue, emptyList(), emptyList())
        val windows = mutableListOf<OutageWindow>()
        val dates = mutableListOf<java.time.LocalDate>()
        var emergency = false
        for (key in listOf("today", "tomorrow")) {
            val day = group.optJSONObject(key) ?: continue
            val date = runCatching { OffsetDateTime.parse(day.getString("date")).toLocalDate() }.getOrNull() ?: continue
            when (day.optString("status")) {
                "EmergencyShutdowns" -> emergency = true
                "ScheduleApplies" -> {
                    dates += date
                    val slots = day.optJSONArray("slots")
                    if (slots != null) for (i in 0 until slots.length()) {
                        val slot = slots.optJSONObject(i) ?: continue
                        val type = slot.optString("type")
                        val maybe = type.contains("Possible", ignoreCase = true)
                        if (type != "Definite" && !maybe) continue
                        windows += OutageWindow(kyivAt(date, slot.optInt("start")), kyivAt(date, slot.optInt("end")), maybe)
                    }
                }
            }
        }
        return OutageSchedule(
            source = OutageSourceKind.YASNO,
            queue = queue,
            windows = OutageWindowMerger.merge(windows),
            publishedDates = dates.distinct().sorted(),
            emergency = emergency,
            updatedAt = group.optString("updatedOn").takeIf { it.isNotBlank() },
        )
    }
}
