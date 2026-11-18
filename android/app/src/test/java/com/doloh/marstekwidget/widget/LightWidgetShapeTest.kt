package com.doloh.marstekwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightWidgetShapeTest {
    @Test
    fun flatCellsAreStripsByWidth() {
        assertEquals(LightWidgetShape.STRIP, LightWidgetShapeSelector.select(340f, 44f))
        assertEquals(LightWidgetShape.STRIP, LightWidgetShapeSelector.select(350f, 84f))
        assertEquals(LightWidgetShape.STRIP_NARROW, LightWidgetShapeSelector.select(160f, 72f))
        assertEquals(LightWidgetShape.STRIP_NARROW, LightWidgetShapeSelector.select(110f, 40f))
    }

    @Test
    fun twoByTwoAndWiderCells() {
        assertEquals(LightWidgetShape.SQUARE, LightWidgetShapeSelector.select(160f, 160f))
        assertEquals(LightWidgetShape.SQUARE, LightWidgetShapeSelector.select(170f, 200f))
        assertEquals(LightWidgetShape.SQUARE, LightWidgetShapeSelector.select(110f, 110f))
        assertEquals(LightWidgetShape.WIDE, LightWidgetShapeSelector.select(340f, 160f))
        assertEquals(LightWidgetShape.WIDE, LightWidgetShapeSelector.select(350f, 200f))
        assertEquals(LightWidgetShape.WIDE, LightWidgetShapeSelector.select(350f, 420f))
    }

    @Test
    fun stripSecondLineNeedsHeight() {
        assertFalse(LightWidgetShapeSelector.stripHasTwoLines(44f))
        assertTrue(LightWidgetShapeSelector.stripHasTwoLines(72f))
    }

    @Test
    fun planNeverOverflowsTheCard() {
        for (w in listOf(100f, 110f, 140f, 160f, 170f, 200f, 250f, 340f, 350f, 420f))
            for (h in listOf(100f, 110f, 130f, 160f, 200f, 230f, 300f, 420f))
                for (scale in listOf(1f, 1.3f)) {
                    val shape = LightWidgetShapeSelector.select(w, h)
                    val plan = LightBlockPlanner.plan(w, h, shape, hasGroup = true, hasReserve = true, fontScale = scale)
                    assertTrue("$w×$h@$scale content ${plan.contentDp}", plan.contentDp <= h - LightBlockPlanner.PAD_V)
                }
    }

    @Test
    fun standardCellsShowTheExpectedRows() {
        val square = LightBlockPlanner.plan(160f, 160f, LightWidgetShape.SQUARE, hasGroup = true, hasReserve = true)
        assertTrue(square.showNext)
        assertFalse("reserve is a 4×2 row", square.showReserve)
        val wide = LightBlockPlanner.plan(340f, 160f, LightWidgetShape.WIDE, hasGroup = true, hasReserve = true)
        assertTrue(wide.showNext && wide.showReserve && wide.showGroup)
        val samsungWide = LightBlockPlanner.plan(350f, 200f, LightWidgetShape.WIDE, hasGroup = true, hasReserve = true)
        assertTrue(samsungWide.showNext && samsungWide.showReserve && samsungWide.showGroup)
    }

    @Test
    fun tallerCellsGetBiggerTypeAndFillMostOfTheHeight() {
        val small = LightBlockPlanner.plan(160f, 160f, LightWidgetShape.SQUARE, hasGroup = true, hasReserve = false)
        val tall = LightBlockPlanner.plan(170f, 200f, LightWidgetShape.SQUARE, hasGroup = true, hasReserve = false)
        assertTrue(tall.durationSp > small.durationSp)
        // rows take at least half of the inner height; spacers distribute the rest (no big empty band)
        assertTrue(tall.contentDp >= (200f - LightBlockPlanner.PAD_V) * 0.5f)
    }

    @Test
    fun tinyCellDropsOptionalRowsBeforeTheDuration() {
        val plan = LightBlockPlanner.plan(250f, 100f, LightWidgetShape.WIDE, hasGroup = true, hasReserve = true)
        assertTrue(plan.contentDp <= 100f - LightBlockPlanner.PAD_V)
        assertTrue(plan.durationSp >= 18f)
        assertFalse(plan.showGroup)
    }
}
