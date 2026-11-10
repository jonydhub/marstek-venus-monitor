package com.doloh.marstekwidget.outage

data class HttpResponse(val code: Int, val body: String?, val etag: String?, val lastModified: String?)

fun interface HttpFetcher {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

fun HttpFetcher.get(url: String): HttpResponse = get(url, emptyMap())

data class HttpCacheEntry(val body: String, val etag: String?, val lastModified: String?, val fetchedAtMillis: Long)

interface HttpCache {
    fun get(url: String): HttpCacheEntry?
    fun put(url: String, entry: HttpCacheEntry)

    class InMemory : HttpCache {
        private val map = mutableMapOf<String, HttpCacheEntry>()
        override fun get(url: String) = synchronized(map) { map[url] }
        override fun put(url: String, entry: HttpCacheEntry) { synchronized(map) { map[url] = entry } }
    }
}

/**
 * Polite fetching for public outage sources: one network request per URL per [minIntervalMillis] (≥ 10 min),
 * conditional requests with ETag / If-Modified-Since, and an identifying User-Agent.
 */
class CachingHttpFetcher(
    private val delegate: HttpFetcher,
    private val cache: HttpCache,
    private val minIntervalMillis: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpFetcher {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val now = clock()
        val cached = cache.get(url)
        if (cached != null && now - cached.fetchedAtMillis in 0 until minIntervalMillis) {
            return HttpResponse(200, cached.body, cached.etag, cached.lastModified)
        }
        val request = LinkedHashMap(headers)
        request["User-Agent"] = userAgent
        cached?.etag?.let { request["If-None-Match"] = it }
        cached?.lastModified?.let { request["If-Modified-Since"] = it }
        val response = delegate.get(url, request)
        if (response.code == 304 && cached != null) {
            cache.put(url, cached.copy(fetchedAtMillis = now))
            return HttpResponse(200, cached.body, cached.etag, cached.lastModified)
        }
        if (response.code == 200 && response.body != null) {
            cache.put(url, HttpCacheEntry(response.body, response.etag, response.lastModified, now))
        }
        return response
    }

    companion object {
        const val DEFAULT_USER_AGENT = "MarstekEnergy/1.0.0 (Android; +https://github.com/jonydhub)"
    }
}

fun HttpFetcher.fetchTextPublic(url: String): String = fetchText(url)

internal fun HttpFetcher.fetchText(url: String, accept: String = "application/json"): String {
    val response = get(url, mapOf("Accept" to accept))
    if (response.code != 200 || response.body == null) throw OutageSourceException("HTTP ${response.code}")
    return response.body
}
