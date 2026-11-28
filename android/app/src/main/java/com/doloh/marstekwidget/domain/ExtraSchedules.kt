package com.doloh.marstekwidget.domain

import com.doloh.marstekwidget.outage.OutageRegions
import org.json.JSONArray
import org.json.JSONObject

/** One «Інші графіки» entry (робота, батьки, інше місце): display only, never used for alerts. */
data class ExtraSchedule(val name: String, val region: String, val queue: String) {
    val key: String get() = "$region|$queue"
}

/**
 * What is known about one extra schedule. [available] false = the region has no schedule source; [error] = the last
 * fetch failed; [published] = the source published at least one day (same as the server's `schedule_published`).
 */
data class ExtraScheduleData(
    val available: Boolean = true,
    val published: Boolean = false,
    val scheduleDates: List<String> = emptyList(),
    val windows: List<ScheduleWindow> = emptyList(),
    val emergency: Boolean = false,
    val error: String? = null,
)

/** Same ids as the web `extraRow().state` (CSS classes). */
enum class ExtraState(val id: String) { LOADING("loading"), NA("na"), OFF("off"), ON("on"), NONE("none") }

/** [days] = today + tomorrow, null when there is nothing to draw (loading, source unavailable). */
data class ExtraRow(val name: String, val sub: String, val state: ExtraState, val status: String, val days: List<DaySchedule>?)

/**
 * «Інші графіки» rules — same as the web `extraSchedulesParse` / `extraRow` in server/app.js (keep in sync,
 * server/tests/test_extra_schedules_web.py ↔ ExtraSchedulesTest): at most [MAX] entries, name trimmed then cut to
 * [NAME_MAX] chars (empty → «Графік»), entries without region/queue skipped, duplicates of region+queue dropped.
 */
object ExtraSchedules {
    const val MAX = 6
    const val NAME_MAX = 24
    const val DEFAULT_NAME = "Графік"

    fun parse(raw: String?): List<ExtraSchedule> {
        val list = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ExtraSchedule>()
        for (i in 0 until list.length()) {
            val e = list.opt(i) as? JSONObject ?: continue
            val region = e.opt("region") as? String
            val queue = e.opt("queue") as? String
            if (region.isNullOrEmpty() || queue.isNullOrEmpty()) continue
            if (!seen.add("$region|$queue")) continue
            val rawName = e.opt("name")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
            out += ExtraSchedule(rawName.trim().take(NAME_MAX).ifEmpty { DEFAULT_NAME }, region, queue)
            if (out.size >= MAX) break
        }
        return out
    }

    fun encode(list: List<ExtraSchedule>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("region", it.region).put("queue", it.queue)) }
    }.toString()

    /** The list with [entry] appended, cleaned by the same rules as [parse] (a duplicate or a 7th entry is dropped). */
    fun add(list: List<ExtraSchedule>, entry: ExtraSchedule): List<ExtraSchedule> = parse(encode(list + entry))

    /** «Львівська обл.», «Київ», «Дніпропетровська обл. (ДТЕК)»; an unknown id is shown as is. */
    fun regionLabel(regionId: String): String = (OutageRegions.byId(regionId)?.name ?: regionId).replaceFirst(" область", " обл.")

    /** «Львівська обл. · черга 2.1» */
    fun sub(entry: ExtraSchedule): String = "${regionLabel(entry.region)} · черга ${entry.queue}"

    /**
     * One row: the bar carries the times, the header only the name and the state by the schedule — «без світла»
     * (inside a definite window now), «світло є», «не опубліковано», «графік недоступний», «аварійні відключення».
     */
    fun row(entry: ExtraSchedule, data: ExtraScheduleData?, nowMillis: Long): ExtraRow {
        val sub = sub(entry)
        if (data == null) return ExtraRow(entry.name, sub, ExtraState.LOADING, "…", null)
        if (!data.available || (data.error != null && !data.published && data.windows.isEmpty())) {
            return ExtraRow(entry.name, sub, ExtraState.NA, "графік недоступний", null)
        }
        val days = ScheduleDays.days(data.windows, data.scheduleDates, data.published, nowMillis)
        if (data.emergency) return ExtraRow(entry.name, sub, ExtraState.OFF, "аварійні відключення", days)
        val off = data.windows.any { !it.maybe && it.startMillis <= nowMillis && it.endMillis > nowMillis }
        val state = when { off -> ExtraState.OFF; data.published -> ExtraState.ON; else -> ExtraState.NONE }
        val status = when (state) { ExtraState.OFF -> "без світла"; ExtraState.ON -> "світло є"; else -> "не опубліковано" }
        return ExtraRow(entry.name, sub, state, status, days)
    }

    /** The own schedule as the same kind of data (for the «Графіки відключень» widget); null = no own schedule set. */
    fun ownData(info: PowerOutageInfo?): ExtraScheduleData? {
        if (info == null || !info.scheduleEnabled) return null
        return ExtraScheduleData(
            available = !info.scheduleUnavailable,
            published = info.schedulePublished,
            scheduleDates = info.scheduleDates,
            windows = info.windows,
            emergency = info.emergency,
            error = info.sourceError,
        )
    }
}
