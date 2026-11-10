package com.doloh.marstekwidget.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlValidatorTest {
    @Test
    fun httpsIsAcceptedAndNormalized() {
        assertEquals("https://example.com", ServerUrlValidator.normalize("https://example.com/"))
        assertEquals("https://example.com/sub", ServerUrlValidator.normalize("  https://example.com/sub/  "))
        assertEquals("https://example.com/api/status", ServerUrlValidator.statusUrl("https://example.com"))
    }

    @Test
    fun plainHttpOnlyForLanHosts() {
        assertEquals("http://172.16.0.5:8767", ServerUrlValidator.normalize("http://172.16.0.5:8767"))
        assertEquals("http://10.0.0.2:8767", ServerUrlValidator.normalize("http://10.0.0.2:8767/"))
        assertEquals("http://nuc.local:8767", ServerUrlValidator.normalize("http://nuc.local:8767"))
        assertNull(ServerUrlValidator.normalize("http://example.com"))
        assertNull(ServerUrlValidator.normalize("http://8.8.8.8"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(ServerUrlValidator.normalize(""))
        assertNull(ServerUrlValidator.normalize("ftp://example.com"))
        assertNull(ServerUrlValidator.normalize("https://"))
        assertNull(ServerUrlValidator.normalize("https://exa mple.com"))
    }

    @Test
    fun hostAllowlistDerivesFromConfiguredUrl() {
        val allow = ServerUrlValidator.trustedHosts("https://power.example.org")
        assertEquals(true, ServerUrlValidator.isTrusted("https://power.example.org/x", allow))
        assertEquals(true, ServerUrlValidator.isTrusted("https://team.cloudflareaccess.com/cdn-cgi", allow))
        assertEquals(false, ServerUrlValidator.isTrusted("https://evil.example.org/", allow))
        assertEquals(false, ServerUrlValidator.isTrusted("http://power.example.org/", allow))
    }
}
