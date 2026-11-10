package com.doloh.marstekwidget.widget

import java.time.Instant

object TelemetryFreshnessResolver {
    const val DEFAULT_STALE_AFTER_MILLIS = 100_000L

    fun resolve(
        state: WidgetFetchState,
        collectedAt: String?,
        lastFetchedAtMillis: Long,
        nowMillis: Long,
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
    ): WidgetFetchState {
        if (state != WidgetFetchState.OK) return state

        val timestamp = collectedAt?.let(::parseTimestamp) ?: lastFetchedAtMillis
        if (timestamp <= 0L) return WidgetFetchState.NEVER
        val ageMillis = (nowMillis - timestamp).coerceAtLeast(0L)
        return if (ageMillis > staleAfterMillis) WidgetFetchState.STALE else WidgetFetchState.OK
    }

    private fun parseTimestamp(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
