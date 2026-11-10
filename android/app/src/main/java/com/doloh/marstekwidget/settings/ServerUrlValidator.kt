package com.doloh.marstekwidget.settings

import com.doloh.marstekwidget.direct.SubnetScanPlanner
import java.net.URI

/** Server-mode URL rules: HTTPS anywhere, plain HTTP only to LAN hosts (private IPv4, *.local, localhost). */
object ServerUrlValidator {
    fun normalize(raw: String): String? {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        when (scheme) {
            "https" -> Unit
            "http" -> if (!isLanHost(host)) return null
            else -> return null
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$host$port$path"
    }

    fun isLanHost(host: String) = SubnetScanPlanner.isPrivateIpv4(host) || host.endsWith(".local") || host == "localhost"

    fun statusUrl(base: String) = "${base.trimEnd('/')}/api/status"

    fun host(base: String): String? = runCatching { URI(base).host?.lowercase() }.getOrNull()

    fun trustedHosts(base: String): Set<String> = setOfNotNull(host(base))

    /** WebView navigation allowlist: the configured server + Cloudflare Access login pages, HTTPS only. */
    fun isTrusted(url: String, allowed: Set<String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host in allowed || host.endsWith(".cloudflareaccess.com")
    }
}
