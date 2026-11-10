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
    fun tagFor(region: OutageRegion) = when (OfficialSources.forRegion(region.id)) {
        OfficialSource.Loe -> OutageSourceKind.LOE.tag
        is OfficialSource.Yasno -> OutageSourceKind.YASNO.tag
        null -> tag
    }
    override fun fetch(region: OutageRegion, queue: String, today: LocalDate): OutageSchedule =
        when (val source = OfficialSources.forRegion(region.id)) {
            OfficialSource.Loe -> LoeParser.parseMenus(fetcher.fetchText(LoeParser.URL, "application/ld+json"), queue)
            is OfficialSource.Yasno -> YasnoParser.parse(fetcher.fetchText(source.url), queue)
            null -> throw OutageSourceException("no official source")
        }
}

class UaOutagesProvider(private val fetcher: HttpFetcher) : OutageProvider {
    override val tag = OutageSourceKind.UA_OUTAGES.tag
    override fun appliesTo(region: OutageRegion) = region.inUaOutages
    override fun fetch(region: OutageRegion, queue: String, today: LocalDate) =
        UaOutagesParser.parse(fetcher.fetchText(UaOutagesParser.URL), region.id, queue)
}

class SvitloProvider(private val fetcher: HttpFetcher) : OutageProvider {
    override val tag = OutageSourceKind.SVITLO.tag
    override fun appliesTo(region: OutageRegion) = true

    private fun load(url: String) = runCatching { SvitloParser.parseRoot(fetcher.fetchText(url)) }.getOrNull()

    override fun fetch(region: OutageRegion, queue: String, today: LocalDate): OutageSchedule {
        val slug = region.svitloSlug
        val preferPrimary = SvitloSelector.prefersPrimary(slug)
        val first = load(if (preferPrimary) SvitloSelector.PRIMARY_URL else SvitloSelector.FALLBACK_URL)
        val quick = SvitloSelector.select(slug, queue, if (preferPrimary) first else null, if (preferPrimary) null else first, today)
        if (quick != null && (quick.schedule.emergency || quick.schedule.publishedDates.contains(today))) return quick.schedule
        val second = load(if (preferPrimary) SvitloSelector.FALLBACK_URL else SvitloSelector.PRIMARY_URL)
        if (first == null && second == null) throw OutageSourceException("unreachable")
        val primary = if (preferPrimary) first else second
        val fallback = if (preferPrimary) second else first
        return SvitloSelector.select(slug, queue, primary, fallback, today)?.schedule
            ?: throw OutageSourceException("region or queue not found")
    }
}

data class OutageChainResult(val schedule: OutageSchedule?, val notes: List<String>)

/** Per-region chain: direct official → ua-outages (official data) → svitlo.live; first non-empty wins. */
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
        return OutageChainResult(lastEmpty, notes)
    }

    companion object {
        fun standard(fetcher: HttpFetcher) = OutageProviderChain(listOf(
            OfficialOutageProvider(fetcher), UaOutagesProvider(fetcher), SvitloProvider(fetcher),
        ))
    }
}
