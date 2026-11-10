package com.doloh.marstekwidget.outage

import com.doloh.marstekwidget.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SvitloParserTest {
    private val primary = SvitloParser.parseRoot(Fixtures.text("svitlo_primary.json"))!!
    private val fallback = SvitloParser.parseRoot(Fixtures.text("svitlo_fallback.json"))!!
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun unwrapsBodyStringFromPrimaryWorker() {
        assertEquals("2026-11-10", primary.optString("date_today"))
        assertTrue(primary.getJSONArray("regions").length() > 0)
        assertNull(SvitloParser.parseRoot("{not json"))
        assertNull(SvitloParser.parseRoot("""{"body":"{broken"}"""))
    }

    @Test
    fun catalogMergesBothApisWithUkrainianNamesAndNormalizedSlugs() {
        val catalog = SvitloCatalog.merge(primary, fallback)
        val ids = catalog.map { it.id }
        assertTrue("lvivska-oblast" in ids)
        assertTrue("kharkivska-oblast" in ids)
        assertFalse("harkivska-oblast" in ids)
        assertFalse("hmelnitska-oblast" in ids)
        assertTrue("vinnitska-oblast" in ids) // only in the fallback API
        assertEquals(ids.size, ids.distinct().size)
        assertEquals("Львівська область", catalog.first { it.id == "lvivska-oblast" }.name)
        assertEquals("Вінницька область", catalog.first { it.id == "vinnitska-oblast" }.name)
        val kyiv = catalog.first { it.id == "kyiv" }
        assertEquals("1.1", kyiv.queues.first())
        assertEquals(kyiv.queues, kyiv.queues.sortedWith(SvitloCatalog.queueComparator))
        assertFalse(catalog.any { it.id == "avtonomna-respublika-krim" })
        assertEquals(catalog.map { it.name }, catalog.map { it.name }.sortedWith(OutageRegions.nameOrder))
    }

    @Test
    fun naturalQueueOrder() {
        assertEquals(listOf("1.1", "1.2", "2.1", "10.1"), listOf("10.1", "2.1", "1.2", "1.1").sortedWith(SvitloCatalog.queueComparator))
    }

    @Test
    fun staleScheduleInPrimaryFallsBackToSecondaryApi() {
        // Lviv: primary only has 2026-10-17 (score 0); fallback has today with zeros (score 1) -> fallback, nothing published.
        val pick = SvitloSelector.select("lvivska-oblast", "1.1", primary, fallback, today)!!
        assertEquals(SvitloApi.FALLBACK, pick.api)
        assertTrue(pick.schedule.windows.isEmpty())
        assertTrue(pick.schedule.publishedDates.isEmpty())
        assertFalse(pick.schedule.emergency)
        assertEquals(OutageSourceKind.SVITLO, pick.schedule.source)
    }

    @Test
    fun emergencyInPrimaryIsNeverReplacedByFallback() {
        val pick = SvitloSelector.select("kyiv", "1.1", primary, fallback, today)!!
        assertEquals(SvitloApi.PRIMARY, pick.api)
        assertTrue(pick.schedule.emergency)
        assertTrue(pick.schedule.windows.isNotEmpty())
        assertEquals(listOf(today, today.plusDays(1)), pick.schedule.publishedDates)
        val first = pick.schedule.windows.first()
        assertEquals("2026-11-10T02:00+02:00[Europe/Kyiv]", first.start.toString())
        assertEquals("2026-11-10T09:00+02:00[Europe/Kyiv]", first.end.toString())
    }

    @Test
    fun mappedSlugIsFoundInEitherApi() {
        val pick = SvitloSelector.select("kharkivska-oblast", "1.1", primary, fallback, today)
        assertNotNull(pick)
        val onlyOld = SvitloSelector.select("kharkivska-oblast", "1.1", null, fallback, today)!!
        assertEquals(SvitloApi.FALLBACK, onlyOld.api)
        assertTrue(onlyOld.schedule.publishedDates.isNotEmpty())
    }

    @Test
    fun unknownRegionOrQueueIsNull() {
        assertNull(SvitloSelector.select("atlantis", "1.1", primary, fallback, today))
    }

    @Test
    fun primaryRegionsListDecidesWhichApiIsPreferred() {
        assertTrue(SvitloSelector.prefersPrimary("lvivska-oblast"))
        assertTrue(SvitloSelector.prefersPrimary("kharkivska-oblast"))
        assertFalse(SvitloSelector.prefersPrimary("vinnitska-oblast"))
    }
}
