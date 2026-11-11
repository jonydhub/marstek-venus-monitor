package com.doloh.marstekwidget.widget

/**
 * Size buckets for responsive widgets. Mirrors the platform's sized-RemoteViews rule
 * (pick the largest-area entry that fits, otherwise the smallest) so pre-API-31 devices
 * and the debug preview select the same layout the launcher would.
 */
enum class WidgetShape(val minWidthDp: Float, val minHeightDp: Float) {
    FLAT_NARROW(100f, 40f),
    FLAT(220f, 40f),
    SMALL(100f, 110f),

    /** Roomier 2×2 (Samsung One UI ≈ 170–190 dp cells): bigger ring/type plus today's durations. */
    SQUARE(165f, 150f),

    /** Tall 2×2 (Samsung portrait ≈ 170×200 dp): ring + gradient bar + today line + mini schedule + next outage. */
    SQUARE_TALL(140f, 185f),
    WIDE(250f, 100f),
    TALL(180f, 190f),
    LARGE(250f, 280f),

    /** Tablet-sized cells (≈ 6×4 and up): the LARGE block with bigger type. */
    XLARGE(400f, 400f);

    val area: Float get() = minWidthDp * minHeightDp

    /** The 4×4-style block (light chip + flows), at phone or tablet scale. */
    val isBlock: Boolean get() = this == LARGE || this == XLARGE

    /** Shapes that show today's charge / discharge / no-light durations. */
    val showsToday: Boolean get() = this == SQUARE || this == SQUARE_TALL || this == WIDE || this == TALL || isBlock

    /** Shapes with the today + tomorrow schedule bar. */
    val showsScheduleBar: Boolean get() = this == WIDE || this == SQUARE_TALL || this == TALL || isBlock
}

object WidgetShapeSelector {
    /**
     * Picks by real width AND height (not just area): a 4×2 at 350×200 dp is a wide row layout, a 2×2 that is
     * taller than wide (Samsung portrait 170×200 dp) gets the tall 2×2 layout instead of leaving empty bands.
     */
    fun select(widthDp: Float, heightDp: Float): WidgetShape = when {
        heightDp < 100f -> if (widthDp >= WidgetShape.FLAT.minWidthDp) WidgetShape.FLAT else WidgetShape.FLAT_NARROW
        widthDp >= 400f && heightDp >= 400f -> WidgetShape.XLARGE
        widthDp >= 250f && heightDp >= 280f -> WidgetShape.LARGE
        widthDp >= 250f -> if (heightDp >= 205f) WidgetShape.TALL else WidgetShape.WIDE
        widthDp >= 180f && heightDp >= 250f -> WidgetShape.TALL
        widthDp >= 140f && heightDp >= 185f -> WidgetShape.SQUARE_TALL
        widthDp >= 165f && heightDp >= 150f -> WidgetShape.SQUARE
        else -> WidgetShape.SMALL
    }

    /** Samsung/most launchers: portrait cell = minWidth × maxHeight, landscape = maxWidth × minHeight (dp). */
    fun portraitSize(minWidth: Int, maxWidth: Int, minHeight: Int, maxHeight: Int): Pair<Float, Float>? =
        if (minWidth > 0 && maxHeight > 0) minWidth.toFloat() to maxHeight.toFloat()
        else if (maxWidth > 0 && minHeight > 0) maxWidth.toFloat() to minHeight.toFloat() else null

    fun landscapeSize(minWidth: Int, maxWidth: Int, minHeight: Int, maxHeight: Int): Pair<Float, Float>? =
        if (maxWidth > 0 && minHeight > 0) maxWidth.toFloat() to minHeight.toFloat() else null

    fun defaultFor(variant: WidgetVariant): WidgetShape = when (variant) {
        WidgetVariant.MINI -> WidgetShape.SMALL
        WidgetVariant.STRIP -> WidgetShape.FLAT
        WidgetVariant.MEDIUM -> WidgetShape.WIDE
        WidgetVariant.LARGE -> WidgetShape.LARGE
    }
}
