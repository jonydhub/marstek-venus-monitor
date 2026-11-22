package com.doloh.marstekwidget.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private val body141 = "**Що нового**\n" +
        "- «Світло за 7 днів»: над смугою кожного дня — час відключень («07:47–08:49», «до 00:15»), під смугою — скільки пішло з батареї.\n" +
        "- Те, що не вмістилось на смузі, — окремим рядком під нею.\n\n" +
        "SHA-256 APK: `5ec74fd6c3d5eef1623cd18a610426b96cb86fc387771c467caf10c8383d8f93`"

    private fun releaseJson(
        tag: String = "v1.4.2",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """[{"name":"MarstekVenusMonitor-1.3.7-setup.exe","browser_download_url":"https://x/setup.exe"},
            {"name":"Marstek-Venus-Monitor.apk","browser_download_url":"https://github.com/r/releases/download/v1.4.2/Marstek-Venus-Monitor.apk"}]""",
        body: String? = body141,
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,
        "html_url":"https://github.com/jonydhub/marstek-venus-monitor/releases/tag/$tag",
        "assets":$assets,"body":${body?.let { org.json.JSONObject.quote(it) } ?: "null"}}"""

    // --- semver -----------------------------------------------------------------------------------------------

    @Test fun newerPatchWithVPrefix() = assertTrue(AppUpdate.isNewer("v1.4.2", "1.4.1"))
    @Test fun numericNotLexicographic() {
        assertTrue(AppUpdate.isNewer("1.4.10", "1.4.1"))
        assertTrue(AppUpdate.isNewer("1.4.10", "1.4.9"))
        assertFalse(AppUpdate.isNewer("1.4.9", "1.4.10"))
    }
    @Test fun sameVersionIsNotNewer() {
        assertFalse(AppUpdate.isNewer("v1.4.1", "1.4.1"))
        assertEquals(0, AppUpdate.compare("1.5", "1.5.0"))
    }
    @Test fun shorterMinorBump() = assertTrue(AppUpdate.isNewer("1.5", "1.4.1"))
    @Test fun olderIsNotNewer() = assertFalse(AppUpdate.isNewer("v1.3.9", "1.4.1"))
    @Test fun garbageIsNeverAnUpdate() {
        listOf(null, "", "latest", "v", "1.4.x", "1.4.2-beta", "v1..2", "1.2.3.4.5").forEach {
            assertFalse("$it", AppUpdate.isNewer(it, "1.4.1"))
            assertNull("$it", AppUpdate.parseVersion(it))
        }
        assertFalse(AppUpdate.isNewer("1.4.2", "garbage"))
        assertEquals("1.4.2", AppUpdate.normalize(" v1.4.2 "))
    }

    // --- JSON ---------------------------------------------------------------------------------------------------

    @Test fun parsesReleaseAndPicksApkByExactName() {
        val r = AppUpdate.parseRelease(releaseJson())!!
        assertEquals("1.4.2", r.version)
        assertEquals("https://github.com/r/releases/download/v1.4.2/Marstek-Venus-Monitor.apk", r.apkUrl)
        assertEquals(r.apkUrl, r.downloadUrl)
        assertEquals("https://github.com/jonydhub/marstek-venus-monitor/releases/tag/v1.4.2", r.htmlUrl)
    }

    @Test fun similarAssetNameIsNotTheApk() {
        val r = AppUpdate.parseRelease(releaseJson(assets = """[{"name":"Marstek-Venus-Monitor.apk.sha256","browser_download_url":"https://x/a"},
            {"name":"marstek-venus-monitor.apk","browser_download_url":"https://x/b"}]"""))!!
        assertNull(r.apkUrl)
        assertEquals(r.htmlUrl, r.downloadUrl)
    }

    @Test fun missingAssetFallsBackToHtmlUrl() {
        val r = AppUpdate.parseRelease(releaseJson(assets = "[]"))!!
        assertNull(r.apkUrl)
        assertEquals("https://github.com/jonydhub/marstek-venus-monitor/releases/tag/v1.4.2", r.downloadUrl)
        val noAssetsKey = AppUpdate.parseRelease("""{"tag_name":"v1.4.2","html_url":"https://github.com/x/releases/tag/v1.4.2","body":""}""")
        assertNotNull(noAssetsKey)
        assertEquals("https://github.com/x/releases/tag/v1.4.2", noAssetsKey!!.downloadUrl)
        assertNull(noAssetsKey.note)
    }

    @Test fun draftAndPrereleaseIgnored() {
        assertNull(AppUpdate.parseRelease(releaseJson(draft = true)))
        assertNull(AppUpdate.parseRelease(releaseJson(prerelease = true)))
    }

    @Test fun badTagOrJsonIgnored() {
        assertNull(AppUpdate.parseRelease(releaseJson(tag = "nightly")))
        assertNull(AppUpdate.parseRelease("not json"))
        assertNull(AppUpdate.parseRelease(null))
        assertNull(AppUpdate.parseRelease("""{"message":"Not Found"}"""))
    }

    @Test fun nullBodyMeansNoNote() = assertNull(AppUpdate.parseRelease(releaseJson(body = null))!!.note)

    // --- release-note line --------------------------------------------------------------------------------------

    @Test fun firstBulletOfWhatsNewCutTo90() {
        val line = AppUpdate.releaseNoteLine(body141)!!
        assertTrue(line, line.startsWith("«Світло за 7 днів»: над смугою кожного дня"))
        assertTrue(line.length <= 90)
        assertTrue(line.endsWith("…"))
    }

    @Test fun markdownAndBackticksStripped() {
        val body = "### **Що нового:**\n* **Банер** про нову версію з `GitHub` — [деталі](https://x/y) і *курсив*\n- second"
        assertEquals("Банер про нову версію з GitHub — деталі і курсив", AppUpdate.releaseNoteLine(body))
    }

    @Test fun shortLineKeptWhole() =
        assertEquals("Виправлено віджет", AppUpdate.releaseNoteLine("**Що нового**\r\n- Виправлено віджет\r\n\r\nSHA-256 APK: `abc`"))

    @Test fun bulletsBeforeHeadingSkipped() =
        assertEquals("нове", AppUpdate.releaseNoteLine("- старе\n\n**Що нового**\n- нове"))

    @Test fun noBulletMeansNoLine() {
        assertNull(AppUpdate.releaseNoteLine("SHA-256 APK: `abc`\nСертифікат підпису SHA-256: `def`"))
        assertNull(AppUpdate.releaseNoteLine("**Що нового**\n\nSHA-256 APK: `abc`"))
        assertNull(AppUpdate.releaseNoteLine(""))
        assertNull(AppUpdate.releaseNoteLine(null))
    }

    @Test fun ellipsizeExactLimit() {
        val s = "а".repeat(90)
        assertEquals(s, AppUpdate.ellipsize(s, 90))
        val cut = AppUpdate.ellipsize(s + "б", 90)
        assertEquals(90, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    // --- 12 h throttle ------------------------------------------------------------------------------------------

    private val h = 3600_000L
    private val now = 1_800_000_000_000L

    @Test fun neverCheckedChecks() = assertTrue(AppUpdate.shouldCheck(now, 0L, 0L))
    @Test fun within12hSkips() = assertFalse(AppUpdate.shouldCheck(now, now - 11 * h, now - 11 * h))
    @Test fun after12hChecks() = assertTrue(AppUpdate.shouldCheck(now, now - 12 * h, now - 12 * h))
    @Test fun forceIgnoresLimit() = assertTrue(AppUpdate.shouldCheck(now, now - 60_000, now - 60_000, force = true))
    @Test fun clockMovedBackChecks() = assertTrue(AppUpdate.shouldCheck(now, now + 5 * h, now + 5 * h))
    @Test fun failedAttemptRetriesAfterAnHour() {
        assertFalse(AppUpdate.shouldCheck(now, now - 20 * h, now - 30 * 60_000))
        assertTrue(AppUpdate.shouldCheck(now, now - 20 * h, now - h))
        assertFalse(AppUpdate.shouldCheck(now, 0L, now - 10 * 60_000))
    }

    // --- banner ✕ per version, notification once per version ----------------------------------------------------

    private fun rel(v: String) = ReleaseInfo(v, "https://x/$v", null, null)

    @Test fun bannerShownOnlyWhenNewer() {
        assertTrue(AppUpdate.bannerVisible(true, rel("1.4.2"), "1.4.1", null))
        assertFalse(AppUpdate.bannerVisible(true, rel("1.4.1"), "1.4.1", null))
        assertFalse(AppUpdate.bannerVisible(true, null, "1.4.1", null))
        assertFalse(AppUpdate.bannerVisible(false, rel("1.4.2"), "1.4.1", null))
    }

    @Test fun closeHidesOnlyThatVersion() {
        assertFalse(AppUpdate.bannerVisible(true, rel("1.4.2"), "1.4.1", "1.4.2"))
        assertTrue(AppUpdate.bannerVisible(true, rel("1.4.3"), "1.4.1", "1.4.2"))
        assertTrue(AppUpdate.bannerVisible(true, rel("9.9.10"), "1.4.1", "9.9.9"))
    }

    @Test fun notifiedOncePerVersion() {
        assertTrue(AppUpdate.shouldNotify(true, rel("1.4.2"), "1.4.1", null))
        assertFalse(AppUpdate.shouldNotify(true, rel("1.4.2"), "1.4.1", "1.4.2"))
        assertTrue(AppUpdate.shouldNotify(true, rel("1.4.3"), "1.4.1", "1.4.2"))
        assertFalse(AppUpdate.shouldNotify(false, rel("1.4.3"), "1.4.1", null))
        assertFalse(AppUpdate.shouldNotify(true, rel("1.4.1"), "1.4.1", null))
    }

    @Test fun onlyGithubHttpsLinksAreOpened() {
        assertTrue(AppUpdate.trustedUrl("https://github.com/jonydhub/marstek-venus-monitor/releases/download/v1.4.2/Marstek-Venus-Monitor.apk"))
        assertFalse(AppUpdate.trustedUrl("http://github.com/x"))
        assertFalse(AppUpdate.trustedUrl("https://evil.example/Marstek-Venus-Monitor.apk"))
        assertFalse(AppUpdate.trustedUrl("https://github.com.evil.example/x"))
        assertFalse(AppUpdate.trustedUrl("https://user@github.com/x"))
        assertFalse(AppUpdate.trustedUrl("javascript:alert(1)"))
        assertNull(AppUpdate.parseRelease("""{"tag_name":"v1.4.2","html_url":"https://evil.example/r","body":""}"""))
    }
}
