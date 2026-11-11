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
        assertEquals(WidgetShape.SMALL, WidgetShapeSelector.select(120f, 120f))
        assertEquals(WidgetShape.SMALL, WidgetShapeSelector.select(160f, 160f))
        // Samsung One UI 2×2 cells (≈ 170–190 dp): roomier square layout; taller than wide → the tall 2×2 layout.
        assertEquals(WidgetShape.SQUARE, WidgetShapeSelector.select(178f, 170f))
        assertEquals(WidgetShape.SQUARE_TALL, WidgetShapeSelector.select(178f, 186f))
        assertEquals(WidgetShape.SQUARE_TALL, WidgetShapeSelector.select(170f, 200f))
        // …but a 4×2 never falls back to the square layout.
        assertEquals(WidgetShape.WIDE, WidgetShapeSelector.select(340f, 186f))
        assertEquals(WidgetShape.WIDE, WidgetShapeSelector.select(340f, 160f))
        assertEquals(WidgetShape.TALL, WidgetShapeSelector.select(340f, 250f))
        assertEquals(WidgetShape.TALL, WidgetShapeSelector.select(200f, 260f))
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.select(340f, 340f))
    }

    @Test
    fun tabletCellsPickShapesThatFillThem() {
        // 5×1 / 6×1 strip on a tablet stays a single row.
        assertEquals(WidgetShape.FLAT, WidgetShapeSelector.select(620f, 90f))
        // 5×2: tall enough for the five-line list, not the 4×4 block.
        assertEquals(WidgetShape.TALL, WidgetShapeSelector.select(560f, 210f))
        // 6×3 and a 3×3 tablet square: the full block.
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.select(680f, 330f))
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.select(330f, 330f))
        // 6×4 and bigger: the enlarged block with bigger type.
        assertEquals(WidgetShape.XLARGE, WidgetShapeSelector.select(700f, 480f))
        assertEquals(WidgetShape.XLARGE, WidgetShapeSelector.select(420f, 420f))
    }

    @Test
    fun bigPhoneCellsDoNotJumpToTabletShape() {
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.select(340f, 430f))
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.select(390f, 400f))
    }

    @Test
    fun samsungOneUiCellsFillTheirShape() {
        // One UI portrait sizes (minWidth × maxHeight): 4×1, 2×2, 4×2, 4×4.
        assertEquals(WidgetShape.FLAT, WidgetShapeSelector.select(350f, 84f))
        assertEquals(WidgetShape.SQUARE_TALL, WidgetShapeSelector.select(170f, 200f))
        assertEquals(WidgetShape.WIDE, WidgetShapeSelector.select(350f, 200f))
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.select(350f, 420f))
    }

    @Test
    fun portraitUsesMinWidthAndMaxHeightLandscapeTheOpposite() {
        assertEquals(170f to 200f, WidgetShapeSelector.portraitSize(170, 190, 160, 200))
        assertEquals(190f to 160f, WidgetShapeSelector.landscapeSize(170, 190, 160, 200))
        assertEquals(null, WidgetShapeSelector.portraitSize(0, 0, 0, 0))
    }

    @Test
    fun trailingDurationsWrapBetweenNumberGroupsOnly() {
        assertEquals("≈\u00A03\u00A0д 15\u00A0год", WidgetRenderer.keepUnits("≈ 3 д 15 год"))
        assertEquals("до 22:00", WidgetRenderer.keepUnits("до 22:00"))
    }

    @Test
    fun variantsHaveSensibleDefaultShapes() {
        assertEquals(WidgetShape.SMALL, WidgetShapeSelector.defaultFor(WidgetVariant.MINI))
        assertEquals(WidgetShape.FLAT, WidgetShapeSelector.defaultFor(WidgetVariant.STRIP))
        assertEquals(WidgetShape.WIDE, WidgetShapeSelector.defaultFor(WidgetVariant.MEDIUM))
        assertEquals(WidgetShape.LARGE, WidgetShapeSelector.defaultFor(WidgetVariant.LARGE))
    }
}
