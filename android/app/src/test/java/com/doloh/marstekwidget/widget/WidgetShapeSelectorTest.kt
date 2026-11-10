package com.doloh.marstekwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetShapeSelectorTest {
    @Test
    fun flatSamsungStripUsesSingleRowLayout() {
        assertEquals(WidgetShape.FLAT, WidgetShapeSelector.select(340f, 64f))
        assertEquals(WidgetShape.FLAT, WidgetShapeSelector.select(300f, 40f))
        assertEquals(WidgetShape.FLAT, WidgetShapeSelector.select(240f, 90f))
    }

    @Test
    fun narrowFlatDropsTrailingColumn() {
        assertEquals(WidgetShape.FLAT_NARROW, WidgetShapeSelector.select(160f, 64f))
        assertEquals(WidgetShape.FLAT_NARROW, WidgetShapeSelector.select(40f, 30f))
    }

    @Test
    fun gridSizesPickTheLayoutThatFillsThem() {
        assertEquals(WidgetShape.SMALL, WidgetShapeSelector.select(160f, 160f))
        assertEquals(WidgetShape.WIDE, WidgetShapeSelector.select(340f, 160f))
        assertEquals(WidgetShape.TALL, WidgetShapeSelector.select(340f, 250f))
        assertEquals(WidgetShape.TALL, WidgetShapeSelector.select(200f, 260f))
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.select(340f, 340f))
    }

    @Test
    fun variantsHaveSensibleDefaultShapes() {
        assertEquals(WidgetShape.SMALL, WidgetShapeSelector.defaultFor(WidgetVariant.MINI))
        assertEquals(WidgetShape.FLAT, WidgetShapeSelector.defaultFor(WidgetVariant.STRIP))
        assertEquals(WidgetShape.WIDE, WidgetShapeSelector.defaultFor(WidgetVariant.MEDIUM))
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.defaultFor(WidgetVariant.LARGE))
    }
}
