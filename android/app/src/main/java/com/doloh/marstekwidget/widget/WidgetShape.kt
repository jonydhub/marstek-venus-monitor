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

    /** Tall 2×2 (Samsung portrait ≈ 170×200 dp): bigger ring + state, today and today's schedule rows. */
    SQUARE_TALL(140f, 185f),
    WIDE(250f, 100f),
    TALL(180f, 190f),
    LARGE(250f, 280f),

    /** Tablet-sized cells (≈ 6×4 and up): the LARGE block with bigger type. */
    XLARGE(400f, 400f);

    val area: Float get() = minWidthDp * minHeightDp

    /** The 4×4-style block (light chip + flows), at phone or tablet scale. */
    val isBlock: Boolean get() = this == LARGE || this == XLARGE

    /** 2×2-class cells sharing the ring layout (header, ring, one-line rows sized by [com.doloh.marstekwidget.domain.CompactPlanner]). */
    val isCompact: Boolean get() = this == SMALL || this == SQUARE || this == SQUARE_TALL

    /** How the SOC is drawn: exactly one indicator per shape — the ring in 2×2 cells, the gradient bar in wide ones, text only in 4×1. */
    val socIndicator: SocIndicator get() = when {
        isCompact -> SocIndicator.RING
        this == FLAT || this == FLAT_NARROW -> SocIndicator.TEXT
        else -> SocIndicator.BAR
    }

    /** Wide/tall/block shapes that show today's durations (compact cells render their own one-line row). */
    val showsToday: Boolean get() = this == WIDE || this == TALL || isBlock

    /** Shapes with the today + tomorrow schedule bar (compact cells draw a today-only bar themselves). */
    val showsScheduleBar: Boolean get() = this == WIDE || this == TALL || isBlock

    /**
     * Time labels above the schedule bar («07:00 … 13:00», now «10:42»), in dp: only the ≥ 4-cell-wide shapes
     * (4×2, 4×4, tablet); 0 = none. Must match the ImageView heights in the layouts ([scheduleBarDp] + label row).
     */
    val scheduleTimeLabelDp: Float get() = when (this) {
        WIDE, LARGE -> 8f
        XLARGE -> 11f
        else -> 0f
    }

    /** Height of the bar itself inside the schedule bitmap, dp. */
    val scheduleBarDp: Float get() = if (this == XLARGE) 12f else 8f
}

enum class SocIndicator { RING, BAR, TEXT }

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
