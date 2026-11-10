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
    WIDE(250f, 100f),
    TALL(180f, 190f),
    LARGE(250f, 280f);

    val area: Float get() = minWidthDp * minHeightDp
}

object WidgetShapeSelector {
    fun select(widthDp: Float, heightDp: Float): WidgetShape =
        WidgetShape.entries.filter { widthDp >= it.minWidthDp && heightDp >= it.minHeightDp }.maxByOrNull { it.area }
            ?: WidgetShape.entries.minBy { it.area }

    fun defaultFor(variant: WidgetVariant): WidgetShape = when (variant) {
        WidgetVariant.MINI -> WidgetShape.SMALL
        WidgetVariant.STRIP -> WidgetShape.FLAT
        WidgetVariant.MEDIUM -> WidgetShape.WIDE
        WidgetVariant.LARGE -> WidgetShape.LARGE
    }
}
