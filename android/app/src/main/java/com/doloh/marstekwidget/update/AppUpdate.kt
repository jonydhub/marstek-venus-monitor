package com.doloh.marstekwidget.update

import org.json.JSONObject

/** Latest published GitHub release, reduced to what the banner / notification / About screen need. */
data class ReleaseInfo(
    /** Normalised version without the «v» prefix, e.g. «1.4.2». */
    val version: String,
    val htmlUrl: String,
    /** browser_download_url of the asset named exactly [AppUpdate.APK_ASSET]; null when the release has none. */
    val apkUrl: String?,
    /** First «Що нового» bullet, Markdown stripped, ≤ [AppUpdate.NOTE_MAX_CHARS] chars; null when there is none. */
    val note: String?,
) {
    /** «Оновити» opens the APK itself, or the release page when the APK is missing. */
    val downloadUrl: String get() = apkUrl ?: htmlUrl
}

/** Pure rules of the «Вийшла нова версія» feature (no Android types — unit-tested on the JVM). */
object AppUpdate {
    const val APK_ASSET = "Marstek-Venus-Monitor.apk"
    const val NOTE_MAX_CHARS = 90
    const val CHECK_INTERVAL_MS = 12 * 3600_000L
    /** After a failed request (offline, GitHub down) try again at most once an hour; the last good result is kept. */
    const val RETRY_AFTER_FAILURE_MS = 3600_000L
    private const val MAX_PARTS = 4

    /** «v1.4.2» / «1.4.2» / «1.5» → [1, 4, 2]; anything else (suffixes, letters, empty) → null. */
    fun parseVersion(raw: String?): List<Int>? {
        val s = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (!Regex("""\d{1,6}(\.\d{1,6}){0,${MAX_PARTS - 1}}""").matches(s)) return null
        return s.split('.').map { it.toInt() }
    }

    /** Numeric compare with missing parts = 0 (1.5 == 1.5.0); null when either side is not a version. */
    fun compare(a: String?, b: String?): Int? {
        val x = parseVersion(a) ?: return null
        val y = parseVersion(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** True only when [latest] is a valid version strictly above [installed]; garbage on either side → false. */
    fun isNewer(latest: String?, installed: String?): Boolean = (compare(latest, installed) ?: 0) > 0

    fun normalize(raw: String?): String? = parseVersion(raw)?.joinToString(".")

    /**
     * Only links the app may open from a release: https on github.com (release page) or its download hosts.
     * A tampered/mocked answer must not make the «Оновити» button open an arbitrary site.
     */
    fun trustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && u.userInfo == null &&
            (host == "github.com" || host == "objects.githubusercontent.com" || host == "release-assets.githubusercontent.com")
    }

    /** GitHub `releases/latest` JSON → [ReleaseInfo]; null for drafts, prereleases, bad tags or malformed JSON. */
    fun parseRelease(json: String?): ReleaseInfo? = runCatching {
        val o = JSONObject(json ?: return null)
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) return null
        val version = normalize(o.optString("tag_name")) ?: return null
        val html = o.optString("html_url").takeIf(::trustedUrl) ?: return null
        val assets = o.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == APK_ASSET) {
                apk = a.optString("browser_download_url").takeIf(::trustedUrl)
                if (apk != null) break
            }
        }
        val body = if (o.isNull("body")) null else o.optString("body")
        ReleaseInfo(version, html, apk, releaseNoteLine(body))
    }.getOrNull()

    /**
     * First bullet under the «**Що нового**» heading (or the first bullet of the body when there is no heading),
     * Markdown stripped, cut to [NOTE_MAX_CHARS] with «…». Null when the body has no bullet.
     */
    fun releaseNoteLine(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val lines = body.replace("\r\n", "\n").split('\n')
        val heading = lines.indexOfFirst { stripMarkdown(it).trimEnd(':').trim().equals("Що нового", ignoreCase = true) }
        val bullet = Regex("""^\s*[-*•+]\s+(.+)$""")
        val from = if (heading >= 0) heading + 1 else 0
        val raw = lines.drop(from).firstNotNullOfOrNull { bullet.find(it)?.groupValues?.get(1) } ?: return null
        val text = stripMarkdown(raw)
        if (text.isEmpty()) return null
        return ellipsize(text, NOTE_MAX_CHARS)
    }

    fun stripMarkdown(s: String): String = s
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1") // links / images → their text
        .replace(Regex("""^\s*#+\s*"""), "")
        .replace("**", "").replace("__", "").replace("`", "").replace("~~", "")
        .replace(Regex("""(?<![\p{L}\d])[*_](\S(?:.*?\S)?)[*_](?![\p{L}\d])"""), "$1")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun ellipsize(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1).trimEnd(' ', ',', ';', ':', '—', '-', '.') + "…"

    /** 12 h throttle: [force] ignores it; a failed attempt retries after an hour; a clock jump back allows a check. */
    fun shouldCheck(nowMs: Long, lastSuccessMs: Long, lastAttemptMs: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (lastAttemptMs in (lastSuccessMs + 1)..nowMs && nowMs - lastAttemptMs < RETRY_AFTER_FAILURE_MS) return false
        if (lastSuccessMs <= 0L || lastSuccessMs > nowMs) return true
        return nowMs - lastSuccessMs >= CHECK_INTERVAL_MS
    }

    /** Banner: feature on, a release newer than the installed app, and ✕ not pressed for THIS version. */
    fun bannerVisible(enabled: Boolean, latest: ReleaseInfo?, installed: String, dismissedVersion: String?): Boolean =
        enabled && latest != null && isNewer(latest.version, installed) && normalize(dismissedVersion) != latest.version

    /** Notification: once per newer version (posted, or skipped because the main screen was open). */
    fun shouldNotify(enabled: Boolean, latest: ReleaseInfo?, installed: String, notifiedVersion: String?): Boolean =
        enabled && latest != null && isNewer(latest.version, installed) && normalize(notifiedVersion) != latest.version
}
