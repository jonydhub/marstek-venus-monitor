package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class OutageProviderChainTest {
    private val today = LocalDate.of(2026, 11, 10)

    private class FakeFetcher(private val routes: Map<String, () -> HttpResponse>) : HttpFetcher {
        val calls = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls += url
            val route = routes.entries.firstOrNull { url.startsWith(it.key) } ?: throw IOException("no route $url")
            return route.value()
        }
    }

    private fun ok(name: String) = { HttpResponse(200, Fixtures.text(name), null, null) }
    private val svitloRoutes = mapOf(
        SvitloSelector.PRIMARY_URL to ok("svitlo_primary.json"),
        SvitloSelector.FALLBACK_URL to ok("svitlo_fallback.json"),
    )
    private val uaoRoutes = mapOf(UaOutagesParser.URL to ok("uao_now.json"))

    private fun chain(fetcher: HttpFetcher) = OutageProviderChain.standard(fetcher)

    @Test
    fun officialLoeUsedWhenItHasASchedule() {
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes + (LoeParser.URL to ok("loe_schedule.json")))
        val result = chain(fetcher).fetch("lviv", "1.1", today)
        assertEquals(OutageSourceKind.LOE, result.schedule!!.source)
        assertTrue(fetcher.calls.none { it.startsWith(SvitloSelector.PRIMARY_URL) || it == UaOutagesParser.URL })
    }

    @Test
    fun emptyOfficialScheduleFallsBackToAggregator() {
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes + (LoeParser.URL to ok("loe_empty.json")))
        val result = chain(fetcher).fetch("lviv", "1.1", today)
        assertEquals(OutageSourceKind.SVITLO, result.schedule!!.source)
        assertEquals(listOf("LOE: empty", "ua-outages: empty", "svitlo.live: empty"), result.notes)
    }

    @Test
    fun failingOfficialSourceFallsBackToAggregator() {
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes + (OfficialSource.Yasno(3, 301).url to { HttpResponse(503, null, null, null) }))
        val result = chain(fetcher).fetch("dnipro", "1.1", today)
        assertEquals(OutageSourceKind.UA_OUTAGES, result.schedule!!.source)
        assertTrue(result.notes.single().startsWith("YASNO:"))
        assertTrue(fetcher.calls.none { it.startsWith(SvitloSelector.PRIMARY_URL) })
    }

    @Test
    fun officialEmergencyIsKeptEvenWithoutWindows() {
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes + (OfficialSource.Yasno(25, 902).url to ok("yasno_25_902.json")))
        val result = chain(fetcher).fetch("kyiv", "1.1", today)
        assertEquals(OutageSourceKind.YASNO, result.schedule!!.source)
        assertTrue(result.schedule!!.emergency)
    }

    @Test
    fun regionsWithoutOfficialApiUseUaOutagesFirst() {
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes)
        val result = chain(fetcher).fetch("mykolaiv", "1.1", today)
        assertEquals(OutageSourceKind.UA_OUTAGES, result.schedule!!.source)
        assertTrue(result.schedule!!.windows.any { it.maybe })
    }

    @Test
    fun uaOutagesFailedRegionFallsToSvitlo() {
        // kharkiv is ok:false in ua-outages (operator answered 403 to GitHub runners)
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes)
        val result = chain(fetcher).fetch("kharkiv", "1.1", today)
        assertEquals(OutageSourceKind.SVITLO, result.schedule!!.source)
        assertTrue(result.notes.single().startsWith("ua-outages:"))
    }

    @Test
    fun svitloOnlyRegionSkipsUaOutages() {
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes)
        val result = chain(fetcher).fetch("vinnytsia", "1.1", today)
        assertEquals(OutageSourceKind.SVITLO, result.schedule!!.source)
        assertTrue(fetcher.calls.none { it == UaOutagesParser.URL })
    }

    @Test
    fun unknownRegionIsAnError() {
        val result = chain(FakeFetcher(svitloRoutes + uaoRoutes)).fetch("atlantis", "1.1", today)
        assertNull(result.schedule)
    }

    @Test
    fun everythingFailingGivesErrorWithoutSchedule() {
        val result = chain(FakeFetcher(emptyMap())).fetch("lviv", "1.1", today)
        assertNull(result.schedule)
        assertEquals(3, result.notes.size)
    }

    @Test
    fun emptyAggregatorAfterEmptyOfficialStillReturnsAggregatorEmpty() {
        val fetcher = FakeFetcher(svitloRoutes + uaoRoutes + (LoeParser.URL to ok("loe_empty.json")))
        val result = chain(fetcher).fetch("lviv", "1.1", today)
        assertTrue(result.schedule!!.isEmpty)
    }
}
