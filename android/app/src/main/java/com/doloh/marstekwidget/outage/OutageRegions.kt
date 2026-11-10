package com.doloh.marstekwidget.outage

import java.text.Collator
import java.util.Locale

/**
 * Canonical region list (ids follow hukpo/ua-outages). [svitloSlug] maps to svitlo.live's `cpu` slug
 * (new-API spelling); [inUaOutages] = present in the ua-outages aggregate.
 */
data class OutageRegion(val id: String, val name: String, val svitloSlug: String, val inUaOutages: Boolean = true)

object OutageRegions {
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("uk-UA"))
    val nameOrder: Comparator<String> = Comparator { a, b -> collator.compare(a, b) }

    val all: List<OutageRegion> = listOf(
        OutageRegion("kyiv", "Київ", "kyiv"),
        OutageRegion("kyiv-region", "Київська область", "kiivska-oblast"),
        OutageRegion("dnipro", "Дніпропетровська область (ДТЕК)", "dnipro-dnem"),
        OutageRegion("dnipro-cek", "Дніпропетровська область (ЦЕК)", "dnipro-cek"),
        OutageRegion("odesa", "Одеська область", "odeska-oblast"),
        OutageRegion("donetsk", "Донецька область", "donetska-oblast"),
        OutageRegion("lviv", "Львівська область", "lvivska-oblast"),
        OutageRegion("ivano-frankivsk", "Івано-Франківська область", "ivano-frankivska-oblast"),
        OutageRegion("zakarpattia", "Закарпатська область", "zakarpatska-oblast"),
        OutageRegion("ternopil", "Тернопільська область", "ternopilska-oblast"),
        OutageRegion("rivne", "Рівненська область", "rivnenska-oblast"),
        OutageRegion("volyn", "Волинська область", "volinska-oblast"),
        OutageRegion("chernivtsi", "Чернівецька область", "chernivetska-oblast"),
        OutageRegion("khmelnytskyi", "Хмельницька область", "khmelnytska-oblast"),
        OutageRegion("chernihiv", "Чернігівська область", "chernihivska-oblast"),
        OutageRegion("zhytomyr", "Житомирська область", "zhytomyrska-oblast"),
        OutageRegion("cherkasy", "Черкаська область", "cherkaska-oblast"),
        OutageRegion("kirovohrad", "Кіровоградська область", "kirovogradska-oblast"),
        OutageRegion("sumy", "Сумська область", "sumska-oblast"),
        OutageRegion("kharkiv", "Харківська область", "kharkivska-oblast"),
        OutageRegion("poltava", "Полтавська область", "poltavska-oblast"),
        OutageRegion("zaporizhzhia", "Запорізька область", "zaporizka-oblast"),
        OutageRegion("mykolaiv", "Миколаївська область", "mikolaivska-oblast"),
        OutageRegion("vinnytsia", "Вінницька область", "vinnitska-oblast", inUaOutages = false),
    ).sortedWith(compareBy(nameOrder) { it.name })

    fun byId(id: String?): OutageRegion? = all.firstOrNull { it.id == id }

    fun fromSvitloSlug(slug: String): OutageRegion? {
        val normalized = SvitloSelector.API_REGION_MAP[slug] ?: slug
        return all.firstOrNull { it.svitloSlug == normalized }
    }

    fun search(query: String): List<OutageRegion> {
        val q = query.trim().lowercase(Locale.forLanguageTag("uk-UA"))
        if (q.isEmpty()) return all
        return all.filter { it.name.lowercase(Locale.forLanguageTag("uk-UA")).contains(q) || it.id.contains(q) }
    }
}
