package com.doloh.marstekwidget.outage

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

/** Where a schedule came from; [displayName] is shown in the UI («Джерело: …»). */
enum class OutageSourceKind(val displayName: String, val tag: String) {
    LOE("Львівобленерго", "LOE"),
    YASNO("YASNO", "YASNO"),
    KHARKIV("Харківобленерго", "Kharkiv"),
    POLTAVA("Полтаваобленерго", "Poltava"),
    ZAPORIZHZHIA("Запоріжжяобленерго", "Zaporizhzhia"),
    CHERNIVTSI("Чернівціобленерго", "Chernivtsi"),
    UA_OUTAGES("ua-outages (офіційні дані обленерго)", "ua-outages"),
}

/** [maybe] = «можливе відключення» (depends on the grid situation), otherwise a definite outage. */
data class OutageWindow(val start: ZonedDateTime, val end: ZonedDateTime, val maybe: Boolean = false)

data class OutageSchedule(
    val source: OutageSourceKind,
    val queue: String,
    val windows: List<OutageWindow>,
    val publishedDates: List<LocalDate>,
    val emergency: Boolean = false,
    val updatedAt: String? = null,
) {
    /** Nothing usable: no published day and no emergency notice → the chain tries the next source. */
    val isEmpty: Boolean get() = publishedDates.isEmpty() && !emergency
}

data class OutageConfig(val regionId: String, val queue: String)

class OutageSourceException(message: String) : Exception(message)

object OutageWindowMerger {
    /** Sort and merge touching/overlapping windows of the same kind (definite vs maybe). */
    fun merge(windows: List<OutageWindow>): List<OutageWindow> {
        val out = mutableListOf<OutageWindow>()
        for (kind in listOf(false, true)) {
            val sorted = windows.filter { it.maybe == kind && it.end.isAfter(it.start) }.sortedBy { it.start.toInstant() }
            val merged = mutableListOf<OutageWindow>()
            for (w in sorted) {
                val last = merged.lastOrNull()
                if (last != null && !w.start.toInstant().isAfter(last.end.toInstant())) {
                    if (w.end.toInstant().isAfter(last.end.toInstant())) merged[merged.size - 1] = last.copy(end = w.end)
                } else merged += w
            }
            out += merged
        }
        return out.sortedWith(compareBy({ it.start.toInstant() }, { it.maybe }))
    }
}

/** Wall-clock minutes from local midnight → zoned time (DST-safe: local time first, then zone). */
internal fun kyivAt(date: LocalDate, minutes: Int): ZonedDateTime =
    date.atStartOfDay().plusMinutes(minutes.toLong()).atZone(KYIV)

object OutageScheduleCodec {
    fun encode(s: OutageSchedule): String = JSONObject()
        .put("source", s.source.name)
        .put("queue", s.queue)
        .put("windows", JSONArray().apply {
            s.windows.forEach { put(JSONObject().put("start", it.start.toString()).put("end", it.end.toString()).put("maybe", it.maybe)) }
        })
        .put("dates", JSONArray(s.publishedDates.map(LocalDate::toString)))
        .put("emergency", s.emergency)
        .put("updatedAt", s.updatedAt ?: JSONObject.NULL)
        .toString()

    fun decode(raw: String?): OutageSchedule? = runCatching {
        val j = JSONObject(raw!!)
        val windows = j.getJSONArray("windows")
        val dates = j.getJSONArray("dates")
        OutageSchedule(
            source = OutageSourceKind.valueOf(j.getString("source")),
            queue = j.getString("queue"),
            windows = (0 until windows.length()).map { i ->
                val w = windows.getJSONObject(i)
                OutageWindow(ZonedDateTime.parse(w.getString("start")), ZonedDateTime.parse(w.getString("end")), w.optBoolean("maybe"))
            },
            publishedDates = (0 until dates.length()).map { LocalDate.parse(dates.getString(it)) },
            emergency = j.optBoolean("emergency"),
            updatedAt = if (j.isNull("updatedAt")) null else j.getString("updatedAt"),
        )
    }.getOrNull()
}
