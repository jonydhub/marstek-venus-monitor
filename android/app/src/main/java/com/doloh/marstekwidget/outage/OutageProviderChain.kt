package com.doloh.marstekwidget.outage

import java.time.LocalDate

interface OutageProvider {
    val tag: String
    fun appliesTo(region: OutageRegion): Boolean
    /** Throws on transport/format failure; may return an empty schedule. */
    fun fetch(region: OutageRegion, queue: String, today: LocalDate): OutageSchedule
}

class OfficialOutageProvider(private val fetcher: HttpFetcher) : OutageProvider {
    override val tag = "official"
    override fun appliesTo(region: OutageRegion) = OfficialSources.forRegion(region.id) != null
    fun tagFor(region: OutageRegion) = OfficialSources.forRegion(region.id)?.kind?.tag ?: tag

    override fun fetch(region: OutageRegion, queue: String, today: LocalDate): OutageSchedule =
        when (val source = OfficialSources.forRegion(region.id)) {
            OfficialSource.Loe -> LoeParser.parseMenus(fetcher.fetchText(LoeParser.URL, "application/ld+json"), queue)
            is OfficialSource.Yasno -> YasnoParser.parse(fetcher.fetchText(source.url), queue)
            is OfficialSource.Oblenergo -> oblenergoDays(source.kind, today).toSchedule(source.kind, queue, today)
            null -> throw OutageSourceException("no official source")
        }

    /** The operator's group list (for the queue picker); throws when the site is unreachable. */
    fun groups(region: OutageRegion, today: LocalDate): List<String> {
        val source = OfficialSources.forRegion(region.id) as? OfficialSource.Oblenergo ?: return emptyList()
        return oblenergoDays(source.kind, today).groups
    }

    private fun oblenergoDays(kind: OutageSourceKind, today: LocalDate): RegionDays = when (kind) {
        OutageSourceKind.KHARKIV ->
            OblenergoParsers.kharkiv(fetcher.fetchText(OblenergoParsers.KHARKIV_URL, "application/vnd.api+json"), today)
        OutageSourceKind.POLTAVA -> OblenergoParsers.poltava(fetcher.fetchText(OblenergoParsers.POLTAVA_URL, HTML), today)
        OutageSourceKind.ZAPORIZHZHIA ->
            OblenergoParsers.zaporizhzhia(fetcher.fetchText(OblenergoParsers.ZAPORIZHZHIA_URL, HTML), today)
        OutageSourceKind.CHERNIVTSI -> {
            val page = fetcher.fetchText(OblenergoParsers.CHERNIVTSI_URL, HTML)
            // the link to tomorrow appears only once it is published
            val pages = if (page.contains(OblenergoParsers.CHERNIVTSI_NEXT_LINK)) {
                listOf(page, fetcher.fetchText(OblenergoParsers.CHERNIVTSI_NEXT_URL, HTML))
            } else listOf(page)
            OblenergoParsers.chernivtsi(pages)
        }
        else -> throw OutageSourceException("not an oblenergo source: $kind")
    }

    private companion object {
        const val HTML = "text/html"
    }
}

class UaOutagesProvider(private val fetcher: HttpFetcher) : OutageProvider {
    override val tag = OutageSourceKind.UA_OUTAGES.tag
    override fun appliesTo(region: OutageRegion) = region.inUaOutages
    override fun fetch(region: OutageRegion, queue: String, today: LocalDate) =
        UaOutagesParser.parse(fetcher.fetchText(UaOutagesParser.URL), region.id, queue)
}

data class OutageChainResult(val schedule: OutageSchedule?, val notes: List<String>)

/** Per-region chain: direct official source → ua-outages (official data, aggregated); first non-empty wins. */
class OutageProviderChain(private val providers: List<OutageProvider>) {
    fun fetch(regionId: String, queue: String, today: LocalDate): OutageChainResult {
        val region = OutageRegions.byId(regionId) ?: return OutageChainResult(null, listOf("unknown region $regionId"))
        val notes = mutableListOf<String>()
        var lastEmpty: OutageSchedule? = null
        for (provider in providers) {
            if (!provider.appliesTo(region)) continue
            val tag = (provider as? OfficialOutageProvider)?.tagFor(region) ?: provider.tag
            try {
                val schedule = provider.fetch(region, queue, today)
                if (!schedule.isEmpty) return OutageChainResult(schedule, notes)
                lastEmpty = schedule
                notes += "$tag: empty"
            } catch (e: Exception) {
                notes += "$tag: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (notes.isEmpty()) notes += NO_SOURCE
        return OutageChainResult(lastEmpty, notes)
    }

    companion object {
        const val NO_SOURCE = "no schedule source for region"

        fun standard(fetcher: HttpFetcher) = OutageProviderChain(listOf(OfficialOutageProvider(fetcher), UaOutagesProvider(fetcher)))
    }
}
