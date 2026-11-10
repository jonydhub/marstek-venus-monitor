package com.doloh.marstekwidget.outage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutageRegionsTest {
    @Test
    fun canonicalListUsesUaOutagesIdsWithUkrainianNames() {
        val ids = OutageRegions.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.containsAll(listOf("kyiv", "kyiv-region", "dnipro", "dnipro-cek", "lviv", "kharkiv", "vinnytsia")))
        assertTrue(ids.none { it.startsWith("uz-") })
        assertEquals("Львівська область", OutageRegions.byId("lviv")!!.name)
        assertEquals(OutageRegions.all.map { it.name }, OutageRegions.all.map { it.name }.sortedWith(OutageRegions.nameOrder))
    }

    @Test
    fun mapsToSvitloSlugs() {
        assertEquals("lvivska-oblast", OutageRegions.byId("lviv")!!.svitloSlug)
        assertEquals("kharkivska-oblast", OutageRegions.byId("kharkiv")!!.svitloSlug)
        assertEquals("dnipro-dnem", OutageRegions.byId("dnipro")!!.svitloSlug)
        assertEquals("vinnitska-oblast", OutageRegions.byId("vinnytsia")!!.svitloSlug)
        assertEquals(false, OutageRegions.byId("vinnytsia")!!.inUaOutages)
        assertNull(OutageRegions.byId("atlantis"))
    }

    @Test
    fun searchMatchesUkrainianNamesCaseInsensitively() {
        assertEquals(listOf("lviv"), OutageRegions.search("льві").map { it.id })
        assertTrue(OutageRegions.search("").size == OutageRegions.all.size)
        assertEquals(listOf("dnipro", "dnipro-cek"), OutageRegions.search("дніпро").map { it.id })
    }

    @Test
    fun everySvitloCatalogEntryWeCareAboutMapsBack() {
        assertEquals("kharkiv", OutageRegions.fromSvitloSlug("harkivska-oblast")!!.id)
        assertEquals("khmelnytskyi", OutageRegions.fromSvitloSlug("hmelnitska-oblast")!!.id)
        assertEquals("lviv", OutageRegions.fromSvitloSlug("lvivska-oblast")!!.id)
    }
}
