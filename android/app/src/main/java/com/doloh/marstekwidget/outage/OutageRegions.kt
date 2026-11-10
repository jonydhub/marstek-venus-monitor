package com.doloh.marstekwidget.outage

import java.text.Collator
import java.util.Locale

/**
 * Canonical region list (ids follow hukpo/ua-outages). [inUaOutages] = present in the ua-outages aggregate;
 * [aliases] = legacy region slugs from earlier configurations, still accepted; [defaultQueues] = queue list
 * used when no live list is available.
 */
data class OutageRegion(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val inUaOutages: Boolean = true,
    val defaultQueues: List<String> = OutageRegions.STANDARD_QUEUES,
) {
    /** Some source (direct official or ua-outages) covers the region; otherwise only the backup output tells. */
    val hasScheduleSource: Boolean get() = inUaOutages || OfficialSources.forRegion(id) != null
}

object OutageRegions {
    val STANDARD_QUEUES: List<String> = (1..6).flatMap { listOf("$it.1", "$it.2") }
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("uk-UA"))
    val nameOrder: Comparator<String> = Comparator { a, b -> collator.compare(a, b) }

    /** Natural queue order: «1.1» < «1.2» < «2.1» < «10.1». */
    val queueOrder: Comparator<String> = Comparator { a, b ->
        val pa = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val pb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x.all(Char::isDigit) && y.all(Char::isDigit)) x.toInt().compareTo(y.toInt()) else x.lowercase().compareTo(y.lowercase())
            if (c != 0) return@Comparator c
        }
        pa.size.compareTo(pb.size)
    }

    val all: List<OutageRegion> = listOf(
        OutageRegion("kyiv", "Київ"),
        OutageRegion("kyiv-region", "Київська область", listOf("kiivska-oblast")),
        OutageRegion("dnipro", "Дніпропетровська область (ДТЕК)", listOf("dnipro-dnem", "dnipropetrovska-oblast", "dnipro-city")),
        OutageRegion("dnipro-cek", "Дніпропетровська область (ЦЕК)"),
        OutageRegion("odesa", "Одеська область", listOf("odeska-oblast")),
        OutageRegion("donetsk", "Донецька область", listOf("donetska-oblast")),
        OutageRegion("lviv", "Львівська область", listOf("lvivska-oblast")),
        OutageRegion("ivano-frankivsk", "Івано-Франківська область", listOf("ivano-frankivska-oblast")),
        OutageRegion("zakarpattia", "Закарпатська область", listOf("zakarpatska-oblast")),
        OutageRegion("ternopil", "Тернопільська область", listOf("ternopilska-oblast")),
        OutageRegion("rivne", "Рівненська область", listOf("rivnenska-oblast")),
        OutageRegion("volyn", "Волинська область", listOf("volinska-oblast")),
        OutageRegion("chernivtsi", "Чернівецька область", listOf("chernivetska-oblast"), defaultQueues = (1..12).map(Int::toString)),
        OutageRegion("khmelnytskyi", "Хмельницька область", listOf("khmelnytska-oblast", "hmelnitska-oblast")),
        OutageRegion("chernihiv", "Чернігівська область", listOf("chernihivska-oblast", "chernigivska-oblast")),
        OutageRegion("zhytomyr", "Житомирська область", listOf("zhytomyrska-oblast", "jitomirska-oblast")),
        OutageRegion("cherkasy", "Черкаська область", listOf("cherkaska-oblast")),
        OutageRegion("kirovohrad", "Кіровоградська область", listOf("kirovogradska-oblast")),
        OutageRegion("sumy", "Сумська область", listOf("sumska-oblast")),
        OutageRegion("kharkiv", "Харківська область", listOf("kharkivska-oblast", "harkivska-oblast")),
        OutageRegion("poltava", "Полтавська область", listOf("poltavska-oblast")),
        OutageRegion("zaporizhzhia", "Запорізька область", listOf("zaporizka-oblast")),
        OutageRegion("mykolaiv", "Миколаївська область", listOf("mikolaivska-oblast")),
        OutageRegion("vinnytsia", "Вінницька область", listOf("vinnitska-oblast"), inUaOutages = false),
    ).sortedWith(compareBy(nameOrder) { it.name })

    fun byId(id: String?): OutageRegion? = all.firstOrNull { it.id == id }

    /** Canonical region for a canonical id or a legacy slug (e.g. «lvivska-oblast»). */
    fun resolve(idOrSlug: String?): OutageRegion? {
        val value = idOrSlug?.trim()?.lowercase(Locale.ROOT) ?: return null
        return byId(value) ?: all.firstOrNull { value in it.aliases }
    }

    fun search(query: String): List<OutageRegion> {
        val q = query.trim().lowercase(Locale.forLanguageTag("uk-UA"))
        if (q.isEmpty()) return all
        return all.filter { it.name.lowercase(Locale.forLanguageTag("uk-UA")).contains(q) || it.id.contains(q) }
    }
}
