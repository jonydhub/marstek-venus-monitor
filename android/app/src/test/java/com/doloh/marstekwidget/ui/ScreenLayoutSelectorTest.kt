package com.doloh.marstekwidget.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutSelectorTest {
    @Test
    fun phonesAndNarrowWindowsUseSingleColumn() {
        listOf(0, 320, 360, 411, 599).forEach { assertEquals("width $it", ScreenLayoutMode.SINGLE, ScreenLayoutSelector.modeFor(it)) }
    }

    @Test
    fun portraitTabletAndFoldableUseTwoColumns() {
        listOf(600, 673, 800, 839).forEach { assertEquals("width $it", ScreenLayoutMode.TWO_COLUMN, ScreenLayoutSelector.modeFor(it)) }
    }

    @Test
    fun expandedButNarrowWindowsUseTwoWideColumns() {
        // Phone landscape (~914 dp) and unfolded foldables: three ~290 dp columns would wrap the hero.
        listOf(840, 914, 1024, 1099).forEach { assertEquals("width $it", ScreenLayoutMode.TWO_COLUMN, ScreenLayoutSelector.modeFor(it)) }
        val medium = ScreenLayoutSelector.specFor(800)
        val expanded = ScreenLayoutSelector.specFor(914)
        assertTrue(expanded.sparklineHeightDp > medium.sparklineHeightDp)
        assertTrue(expanded.maxContentWidthDp >= 914)
    }

    @Test
    fun landscapeTabletUsesThreeColumns() {
        listOf(1100, 1280, 1920).forEach { assertEquals("width $it", ScreenLayoutMode.THREE_COLUMN, ScreenLayoutSelector.modeFor(it)) }
    }

    @Test
    fun phoneSpecKeepsTheOriginalScale() {
        val spec = ScreenLayoutSelector.specFor(411)
        assertEquals(1f, spec.textScale)
        assertEquals(1, spec.columns)
        assertEquals(72, spec.sparklineHeightDp)
    }

    @Test
    fun tabletsGetLargerTypeTallerChartAndBoundedWidth() {
        val two = ScreenLayoutSelector.specFor(800)
        val three = ScreenLayoutSelector.specFor(1280)
        assertEquals(2, two.columns)
        assertEquals(3, three.columns)
        assertTrue(two.textScale > 1f && three.textScale >= two.textScale)
        assertTrue(three.sparklineHeightDp > two.sparklineHeightDp && two.sparklineHeightDp > 72)
        // Content never grows unbounded on very wide screens; each column stays a readable width.
        assertTrue(three.maxContentWidthDp in 1100..1400)
        assertTrue(ScreenLayoutSelector.specFor(1920).maxContentWidthDp == three.maxContentWidthDp)
        assertTrue(two.maxContentWidthDp >= 800)
    }

    @Test
    fun formsAreCenteredAtReadableWidth() {
        assertEquals(640, ScreenLayoutSelector.FORM_MAX_WIDTH_DP)
    }
}
