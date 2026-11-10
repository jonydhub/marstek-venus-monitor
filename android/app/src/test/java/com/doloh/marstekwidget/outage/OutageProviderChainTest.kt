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
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val urls get() = calls.map { it.first }
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls += url to headers
            // longest prefix first, so ".../shutdowns/?next" is not served the ".../shutdowns/" page
            val route = routes.entries.sortedByDescending { it.key.length }.firstOrNull { url.startsWith(it.key) }
                ?: throw IOException("no route $url")
            return route.value()
        }
    }

    private fun ok(name: String) = { HttpResponse(200, Fixtures.text(name), null, null) }
    private val uaoRoutes = mapOf(UaOutagesParser.URL to ok("uao_now.json"))
    private val oblenergoRoutes = mapOf(
        OblenergoParsers.KHARKIV_URL to ok("oblenergo_kharkiv.json"),
        OblenergoParsers.POLTAVA_URL to ok("oblenergo_poltava.html"),
        OblenergoParsers.ZAPORIZHZHIA_URL to ok("oblenergo_zaporizhzhia.html"),
        OblenergoParsers.CHERNIVTSI_URL to ok("oblenergo_chernivtsi.html"),
    )

    private fun chain(fetcher: HttpFetcher) = OutageProviderChain.standard(fetcher)

    @Test
    fun officialLoeUsedWhenItHasASchedule() {
        val fetcher = FakeFetcher(uaoRoutes + (LoeParser.URL to ok("loe_schedule.json")))
        val result = chain(fetcher).fetch("lviv", "1.1", today)
        assertEquals(OutageSourceKind.LOE, result.schedule!!.source)
        assertTrue(fetcher.urls.none { it == UaOutagesParser.URL })
    }

    @Test
    fun emptyOfficialScheduleFallsBackToAggregator() {
        val fetcher = FakeFetcher(uaoRoutes + (LoeParser.URL to ok("loe_empty.json")))
        val result = chain(fetcher).fetch("lviv", "1.1", today)
        assertEquals(OutageSourceKind.UA_OUTAGES, result.schedule!!.source)
        assertTrue(result.schedule!!.isEmpty) // lviv has no days in the captured aggregate either
        assertEquals(listOf("LOE: empty", "ua-outages: empty"), result.notes)
    }

    @Test
    fun failingOfficialSourceFallsBackToAggregator() {
        val fetcher = FakeFetcher(uaoRoutes + (OfficialSource.Yasno(3, 301).url to { HttpResponse(503, null, null, null) }))
        val result = chain(fetcher).fetch("dnipro", "1.1", today)
        assertEquals(OutageSourceKind.UA_OUTAGES, result.schedule!!.source)
        assertTrue(result.notes.single().startsWith("YASNO:"))
    }

    @Test
    fun officialEmergencyIsKeptEvenWithoutWindows() {
        val fetcher = FakeFetcher(uaoRoutes + (OfficialSource.Yasno(25, 902).url to ok("yasno_25_902.json")))
        val result = chain(fetcher).fetch("kyiv", "1.1", today)
        assertEquals(OutageSourceKind.YASNO, result.schedule!!.source)
        assertTrue(result.schedule!!.emergency)
    }

    @Test
    fun regionsWithoutOfficialSourceUseUaOutages() {
        val fetcher = FakeFetcher(uaoRoutes)
        val result = chain(fetcher).fetch("mykolaiv", "1.1", today)
        assertEquals(OutageSourceKind.UA_OUTAGES, result.schedule!!.source)
        assertTrue(result.schedule!!.windows.any { it.maybe })
    }

    @Test
    fun oblenergoRegionsReadTheOperatorSiteDirectly() {
        val expected = mapOf(
            "kharkiv" to OutageSourceKind.KHARKIV, "poltava" to OutageSourceKind.POLTAVA,
            "zaporizhzhia" to OutageSourceKind.ZAPORIZHZHIA, "chernivtsi" to OutageSourceKind.CHERNIVTSI,
        )
        for ((region, kind) in expected) {
            val fetcher = FakeFetcher(uaoRoutes + oblenergoRoutes)
            val queue = if (region == "chernivtsi") "1" else "1.1"
            val result = chain(fetcher).fetch(region, queue, today)
            assertEquals(region, kind, result.schedule!!.source)
            assertEquals(region, listOf(today), result.schedule!!.publishedDates)
            assertTrue(region, fetcher.urls.none { it == UaOutagesParser.URL })
            assertTrue(region, fetcher.calls.all { it.second["Accept"] != null })
        }
        assertEquals("Харківобленерго", OutageSourceKind.KHARKIV.displayName)
    }

    @Test
    fun chernivtsiTomorrowFetchedOnlyWhenLinked() {
        val fetcher = FakeFetcher(uaoRoutes + oblenergoRoutes)
        chain(fetcher).fetch("chernivtsi", "1", today)
        assertEquals(listOf(OblenergoParsers.CHERNIVTSI_URL), fetcher.urls)
        val page = Fixtures.text("oblenergo_chernivtsi.html")
        val linked = FakeFetcher(uaoRoutes + mapOf(
            OblenergoParsers.CHERNIVTSI_URL to { HttpResponse(200, page.replace("</body>", "<a ${OblenergoParsers.CHERNIVTSI_NEXT_LINK}>Завтра</a></body>"), null, null) },
            OblenergoParsers.CHERNIVTSI_NEXT_URL to { HttpResponse(200, page.replace("10.11.2026", "11.11.2026"), null, null) },
        ))
        val result = chain(linked).fetch("chernivtsi", "1", today)
        assertEquals(listOf(today, today.plusDays(1)), result.schedule!!.publishedDates)
    }

    @Test
    fun unreachableOblenergoFallsBackToUaOutages() {
        val fetcher = FakeFetcher(uaoRoutes + (OblenergoParsers.KHARKIV_URL to { HttpResponse(403, null, null, null) }))
        val result = chain(fetcher).fetch("kharkiv", "1.1", today)
        assertNull(result.schedule) // kharkiv is ok:false in the captured aggregate too
        assertEquals(2, result.notes.size)
        assertTrue(result.notes[0].startsWith("Kharkiv: HTTP 403"))
    }

    @Test
    fun regionWithoutAnySourceMakesNoRequests() {
        val fetcher = FakeFetcher(uaoRoutes + oblenergoRoutes)
        val result = chain(fetcher).fetch("vinnytsia", "1.1", today)
        assertNull(result.schedule)
        assertEquals(listOf(OutageProviderChain.NO_SOURCE), result.notes)
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun unknownRegionIsAnError() {
        val result = chain(FakeFetcher(uaoRoutes)).fetch("atlantis", "1.1", today)
        assertNull(result.schedule)
    }

    @Test
    fun everythingFailingGivesErrorWithoutSchedule() {
        val result = chain(FakeFetcher(emptyMap())).fetch("lviv", "1.1", today)
        assertNull(result.schedule)
        assertEquals(2, result.notes.size)
    }

    @Test
    fun oblenergoGroupsFeedTheQueuePicker() {
        val provider = OfficialOutageProvider(FakeFetcher(oblenergoRoutes))
        assertEquals((1..12).map(Int::toString), provider.groups(OutageRegions.byId("chernivtsi")!!, today))
        assertEquals("6.2", provider.groups(OutageRegions.byId("poltava")!!, today).last())
        assertTrue(provider.groups(OutageRegions.byId("lviv")!!, today).isEmpty())
    }
}
