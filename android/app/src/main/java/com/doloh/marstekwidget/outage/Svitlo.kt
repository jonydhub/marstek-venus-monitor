package com.doloh.marstekwidget.outage

import org.json.JSONObject
import java.time.LocalDate

enum class SvitloApi { PRIMARY, FALLBACK }

data class SvitloRegionEntry(val id: String, val name: String, val isNewApi: Boolean, val queues: List<String>)

data class SvitloPick(val api: SvitloApi, val schedule: OutageSchedule)

/** svitlo.live public proxies (format & selection logic from chaichuk/svitlo_live, MIT). */
object SvitloParser {
    /** The primary worker wraps the JSON in a `body` string. */
    fun parseRoot(text: String): JSONObject? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val body = root.opt("body")
        if (body is String) return runCatching { JSONObject(body) }.getOrNull()
        return root
    }

    internal fun slots(region: JSONObject, queue: String, date: LocalDate): Map<String, Int>? {
        val day = region.optJSONObject("schedule")?.optJSONObject(queue)?.optJSONObject(date.toString()) ?: return null
        return day.keys().asSequence().associateWith { day.optInt(it, 0) }
    }
}

object SvitloCatalog {
    val queueComparator: Comparator<String> = Comparator { a, b ->
        val pa = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val pb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x.all(Char::isDigit) && y.all(Char::isDigit)) x.toInt().compareTo(y.toInt()) else x.lowercase().compareTo(y.lowercase())
            if (c != 0) return@Comparator c
        }
        pa.size.compareTo(pb.size)
    }

    private fun normalizeName(name: String, slug: String) =
        if (slug.endsWith("-oblast") && !name.lowercase().contains("область")) "$name область" else name

    private fun queues(region: JSONObject): List<String> =
        region.optJSONObject("schedule")?.keys()?.asSequence()?.toList().orEmpty()

    fun merge(primary: JSONObject?, fallback: JSONObject?): List<SvitloRegionEntry> {
        val merged = linkedMapOf<String, SvitloRegionEntry>()
        primary?.optJSONArray("regions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val cpu = r.optString("cpu")
                if (cpu.isBlank()) continue
                if (!SvitloSelector.prefersPrimary(cpu)) continue
                val q = queues(r)
                if (q.isEmpty()) continue
                merged[cpu] = SvitloRegionEntry(cpu, normalizeName(r.optString("name_ua").ifBlank { cpu }, cpu), true, q.sortedWith(queueComparator))
            }
        }
        fallback?.optJSONArray("regions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val cpu = r.optString("cpu")
                if (cpu.isBlank()) continue
                val id = SvitloSelector.API_REGION_MAP[cpu] ?: cpu
                val q = queues(r)
                if (q.isEmpty()) continue
                val existing = merged[id]
                merged[id] = if (existing == null) {
                    SvitloRegionEntry(id, normalizeName(r.optString("name_ua").ifBlank { id }, id), false, q.sortedWith(queueComparator))
                } else existing.copy(queues = (existing.queues + q).distinct().sortedWith(queueComparator))
            }
        }
        return merged.values.sortedWith(compareBy(OutageRegions.nameOrder) { it.name })
    }
}

object SvitloSelector {
    const val PRIMARY_URL = "https://dtek-api.svitlo-proxy.workers.dev/"
    const val FALLBACK_URL = "https://svitlo-proxy.svitlo-proxy.workers.dev"

    val API_REGION_MAP = mapOf(
        "harkivska-oblast" to "kharkivska-oblast",
        "hmelnitska-oblast" to "khmelnytska-oblast",
        "chernigivska-oblast" to "chernihivska-oblast",
        "jitomirska-oblast" to "zhytomyrska-oblast",
    )
    private val REVERSE_MAP = API_REGION_MAP.entries.associate { (k, v) -> v to k }
    private val DNIPRO = setOf("dnipro-dnem", "dnipro-cek", "dnipro-city")
    private val NEW_API_REGIONS = setOf(
        "kyiv", "kiivska-oblast", "odeska-oblast", "dnipro-dnem", "dnipro-cek", "dnipro-city", "dnipropetrovska-oblast",
        "lvivska-oblast", "chernivetska-oblast", "kharkivska-oblast", "poltavska-oblast", "cherkaska-oblast",
        "chernihivska-oblast", "khmelnytska-oblast", "ivano-frankivska-oblast", "rivnenska-oblast", "ternopilska-oblast",
        "zakarpatska-oblast", "zhytomyrska-oblast", "sumska-oblast",
    )

    fun prefersPrimary(slug: String) = slug in NEW_API_REGIONS || API_REGION_MAP[slug] in NEW_API_REGIONS

    private fun candidates(slug: String, primaryApi: Boolean): List<String> = buildList {
        if (primaryApi) {
            API_REGION_MAP[slug]?.let(::add); add(slug)
        } else {
            REVERSE_MAP[slug]?.let(::add)
            if (slug in DNIPRO) add("dnipropetrovska-oblast")
            add(slug)
            API_REGION_MAP[slug]?.let(::add)
        }
    }.distinct()

    private fun find(root: JSONObject?, keys: List<String>): JSONObject? {
        val arr = root?.optJSONArray("regions") ?: return null
        for (key in keys) for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optString("cpu") == key) return r
        }
        return null
    }

    fun select(slug: String, queue: String, primary: JSONObject?, fallback: JSONObject?, today: LocalDate): SvitloPick? {
        val tomorrow = today.plusDays(1)
        val prefIsPrimary = prefersPrimary(slug)
        val prefRoot = if (prefIsPrimary) primary else fallback
        val altRoot = if (prefIsPrimary) fallback else primary
        val pref = find(prefRoot, candidates(slug, prefIsPrimary))
        val alt = find(altRoot, candidates(slug, !prefIsPrimary))
        val prefEmergency = pref?.optBoolean("emergency", false) == true
        val prefScore = pref?.let { HalfHourSlots.score(SvitloParser.slots(it, queue, today)) } ?: 0
        val altScore = alt?.let { HalfHourSlots.score(SvitloParser.slots(it, queue, today)) } ?: 0
        var usedIsPrimary = prefIsPrimary
        var used = pref
        var other = alt
        if (pref == null || (altScore > prefScore && !prefEmergency)) {
            if (alt != null) { used = alt; other = pref; usedIsPrimary = !prefIsPrimary }
        }
        used ?: return null
        val todaySlots = SvitloParser.slots(used, queue, today)
        var tomorrowSlots = SvitloParser.slots(used, queue, tomorrow)
        if (HalfHourSlots.score(tomorrowSlots) < 2 && !prefEmergency && other != null) {
            val o = SvitloParser.slots(other, queue, tomorrow)
            if (HalfHourSlots.score(o) == 2) tomorrowSlots = o
        }
        val emergency = listOfNotNull(pref, alt).any { it.optBoolean("emergency", false) }
        val windows = mutableListOf<OutageWindow>()
        val dates = mutableListOf<LocalDate>()
        if (HalfHourSlots.score(todaySlots) == 2) { dates += today; windows += HalfHourSlots.toWindows(today, todaySlots!!) }
        if (HalfHourSlots.score(tomorrowSlots) == 2) { dates += tomorrow; windows += HalfHourSlots.toWindows(tomorrow, tomorrowSlots!!) }
        return SvitloPick(
            if (usedIsPrimary) SvitloApi.PRIMARY else SvitloApi.FALLBACK,
            OutageSchedule(OutageSourceKind.SVITLO, queue, OutageWindowMerger.merge(windows), dates, emergency),
        )
    }
}
