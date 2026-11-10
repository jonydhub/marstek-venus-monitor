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
    fun sourcesPerRegion() {
        assertEquals(false, OutageRegions.byId("vinnytsia")!!.inUaOutages)
        assertEquals(false, OutageRegions.byId("vinnytsia")!!.hasScheduleSource)
        for (id in listOf("kharkiv", "poltava", "zaporizhzhia", "chernivtsi", "lviv", "kyiv", "odesa")) {
            assertTrue(id, OutageRegions.byId(id)!!.hasScheduleSource)
        }
        assertEquals((1..12).map(Int::toString), OutageRegions.byId("chernivtsi")!!.defaultQueues)
        assertEquals("6.2", OutageRegions.byId("kharkiv")!!.defaultQueues.last())
        assertNull(OutageRegions.byId("atlantis"))
    }

    @Test
    fun searchMatchesUkrainianNamesCaseInsensitively() {
        assertEquals(listOf("lviv"), OutageRegions.search("льві").map { it.id })
        assertTrue(OutageRegions.search("").size == OutageRegions.all.size)
        assertEquals(listOf("dnipro", "dnipro-cek"), OutageRegions.search("дніпро").map { it.id })
    }

    @Test
    fun legacySlugsResolveToCanonicalIds() {
        assertEquals("kharkiv", OutageRegions.resolve("harkivska-oblast")!!.id)
        assertEquals("kharkiv", OutageRegions.resolve("kharkivska-oblast")!!.id)
        assertEquals("khmelnytskyi", OutageRegions.resolve("hmelnitska-oblast")!!.id)
        assertEquals("lviv", OutageRegions.resolve("lvivska-oblast")!!.id)
        assertEquals("dnipro", OutageRegions.resolve("dnipro-dnem")!!.id)
        assertEquals("vinnytsia", OutageRegions.resolve("vinnitska-oblast")!!.id)
        assertEquals("odesa", OutageRegions.resolve("odesa")!!.id)
        assertNull(OutageRegions.resolve("atlantis"))
    }

    @Test
    fun queueOrderIsNatural() {
        assertEquals(listOf("1.1", "1.2", "2.1", "10.1"), listOf("10.1", "2.1", "1.2", "1.1").sortedWith(OutageRegions.queueOrder))
    }
}
