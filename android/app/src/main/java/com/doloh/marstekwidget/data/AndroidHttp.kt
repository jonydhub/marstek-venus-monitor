package com.doloh.marstekwidget.data

import com.doloh.marstekwidget.outage.HttpCache
import com.doloh.marstekwidget.outage.HttpCacheEntry
import com.doloh.marstekwidget.outage.HttpFetcher
import com.doloh.marstekwidget.outage.HttpResponse
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Plain HttpURLConnection GET (HTTPS only for outage sources). */
class AndroidHttpFetcher(private val timeoutMs: Int = 15_000) : HttpFetcher {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else null
            return HttpResponse(code, body, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
        } finally {
            connection.disconnect()
        }
    }
}

/** Survives process death so WorkManager runs also respect the 10-minute politeness interval and ETags. */
class FileHttpCache(private val dir: File) : HttpCache {
    init { dir.mkdirs() }

    private fun file(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    override fun get(url: String): HttpCacheEntry? = runCatching {
        val j = JSONObject(file(url).readText())
        HttpCacheEntry(j.getString("body"), j.optString("etag").ifBlank { null }, j.optString("lm").ifBlank { null }, j.getLong("t"))
    }.getOrNull()

    override fun put(url: String, entry: HttpCacheEntry) {
        runCatching {
            file(url).writeText(JSONObject().put("body", entry.body).put("etag", entry.etag ?: "").put("lm", entry.lastModified ?: "")
                .put("t", entry.fetchedAtMillis).toString())
        }
    }
}
