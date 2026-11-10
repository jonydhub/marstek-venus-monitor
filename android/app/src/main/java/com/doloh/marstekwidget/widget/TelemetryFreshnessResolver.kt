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
        if (state != WidgetFetchState.OK && state != WidgetFetchState.ERROR) return state

        val timestamp = collectedAt?.let(::parseTimestamp) ?: lastFetchedAtMillis
        if (timestamp <= 0L) return if (state == WidgetFetchState.ERROR) state else WidgetFetchState.NEVER
        val ageMillis = (nowMillis - timestamp).coerceAtLeast(0L)
        val fresh = ageMillis <= staleAfterMillis
        // A single failed poll (the battery drops UDP requests under load) right after a successful one
        // is not «offline»: the data on screen is still live. Only an error past the threshold is.
        if (state == WidgetFetchState.ERROR) return if (fresh) WidgetFetchState.OK else WidgetFetchState.ERROR
        return if (fresh) WidgetFetchState.OK else WidgetFetchState.STALE
    }

    private fun parseTimestamp(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
