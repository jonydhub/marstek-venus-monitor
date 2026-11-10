package com.doloh.marstekwidget.outage

import org.junit.Assert.assertEquals
import org.junit.Test

class CachingHttpFetcherTest {
    private class Recorder(var next: HttpResponse) : HttpFetcher {
        val headers = mutableListOf<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse { this.headers += headers; return next }
    }

    @Test
    fun reusesBodyWithinMinimumIntervalWithoutNetwork() {
        var now = 0L
        val net = Recorder(HttpResponse(200, "A", "\"e1\"", null))
        val cache = CachingHttpFetcher(net, HttpCache.InMemory(), minIntervalMillis = 600_000, clock = { now })
        assertEquals("A", cache.get("u").body)
        now = 599_000
        assertEquals("A", cache.get("u").body)
        assertEquals(1, net.headers.size)
    }

    @Test
    fun sendsConditionalHeadersAndServesCachedBodyOn304() {
        var now = 0L
        val net = Recorder(HttpResponse(200, "A", "\"e1\"", "Tue, 10 Nov 2026 10:00:00 GMT"))
        val cache = CachingHttpFetcher(net, HttpCache.InMemory(), minIntervalMillis = 600_000, clock = { now })
        cache.get("u")
        now = 700_000
        net.next = HttpResponse(304, null, null, null)
        val second = cache.get("u")
        assertEquals(200, second.code)
        assertEquals("A", second.body)
        assertEquals("\"e1\"", net.headers[1]["If-None-Match"])
        assertEquals("Tue, 10 Nov 2026 10:00:00 GMT", net.headers[1]["If-Modified-Since"])
        assertEquals(true, net.headers.all { it["User-Agent"]!!.startsWith("MarstekEnergy/") })
    }

    @Test
    fun serverErrorIsPassedThroughAndNotCached() {
        var now = 0L
        val net = Recorder(HttpResponse(500, null, null, null))
        val cache = CachingHttpFetcher(net, HttpCache.InMemory(), minIntervalMillis = 600_000, clock = { now })
        assertEquals(500, cache.get("u").code)
        net.next = HttpResponse(200, "B", null, null)
        now = 1_000
        assertEquals("B", cache.get("u").body)
    }
}
