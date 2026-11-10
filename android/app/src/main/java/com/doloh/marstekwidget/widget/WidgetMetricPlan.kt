package com.doloh.marstekwidget.widget

enum class WidgetMetric {
    SOC,
    STORED_ENERGY,
    BATTERY_STATE,
    ESTIMATED_BATTERY_POWER,
    ETA,
    TEMPERATURE,
    GRID_POWER,
    BACKUP_POWER,
    ACCUMULATED_COUNTERS,
    RESERVE,
    OPERATING_MODE,
    FRESHNESS,
}

/** Progressive information hierarchy; each layout's primary telemetry stays explicit. */
object WidgetMetricPlan {
    private val core = setOf(
        WidgetMetric.SOC,
        WidgetMetric.STORED_ENERGY,
        WidgetMetric.BATTERY_STATE,
        WidgetMetric.ESTIMATED_BATTERY_POWER,
        WidgetMetric.ETA,
        WidgetMetric.FRESHNESS,
    )

    private val medium = core + setOf(
        WidgetMetric.TEMPERATURE,
        WidgetMetric.GRID_POWER,
        WidgetMetric.BACKUP_POWER,
    )

    fun forVariant(variant: WidgetVariant): Set<WidgetMetric> = when (variant) {
        WidgetVariant.MINI, WidgetVariant.STRIP -> core
        WidgetVariant.MEDIUM -> medium
        WidgetVariant.LARGE -> medium + setOf(
            WidgetMetric.RESERVE,
            WidgetMetric.OPERATING_MODE,
        )
    }
}
