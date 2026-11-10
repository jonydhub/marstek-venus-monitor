package com.doloh.marstekwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TelemetryFreshnessResolverTest {
    private val now = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()

    @Test
    fun marksSuccessfulSnapshotStalePastThe100SecondThreshold() {
        val collectedAt = Instant.ofEpochMilli(now - 100_001).toString()

        assertEquals(
            WidgetFetchState.STALE,
            TelemetryFreshnessResolver.resolve(WidgetFetchState.OK, collectedAt, now - 10_000, now),
        )
    }

    @Test
    fun keepsFreshSnapshotFreshAtThresholdBoundary() {
        val collectedAt = Instant.ofEpochMilli(now - 100_000).toString()

        assertEquals(
            WidgetFetchState.OK,
            TelemetryFreshnessResolver.resolve(WidgetFetchState.OK, collectedAt, now - 100_000, now),
        )
    }

    @Test
    fun fallsBackToLastSuccessfulFetchWhenSourceTimestampIsMissing() {
        assertEquals(
            WidgetFetchState.STALE,
            TelemetryFreshnessResolver.resolve(WidgetFetchState.OK, null, now - 100_001, now),
        )
    }

    @Test
    fun transientErrorWithRecentSuccessfulPollStaysOnline() {
        // Direct mode: one dropped UDP exchange must not flip a live screen to «ОФЛАЙН».
        val collectedAt = Instant.ofEpochMilli(now - 6_000).toString()
        assertEquals(
            WidgetFetchState.OK,
            TelemetryFreshnessResolver.resolve(WidgetFetchState.ERROR, collectedAt, now - 6_000, now),
        )
    }

    @Test
    fun errorAfterThresholdIsOffline() {
        val collectedAt = Instant.ofEpochMilli(now - 100_001).toString()
        assertEquals(
            WidgetFetchState.ERROR,
            TelemetryFreshnessResolver.resolve(WidgetFetchState.ERROR, collectedAt, now - 100_001, now),
        )
    }

    @Test
    fun neverMasksAuthenticationOrNetworkErrorsAsStale() {
        assertEquals(
            WidgetFetchState.ERROR,
            TelemetryFreshnessResolver.resolve(WidgetFetchState.ERROR, null, now - 1_000_000, now),
        )
        assertEquals(
            WidgetFetchState.NEEDS_SIGN_IN,
            TelemetryFreshnessResolver.resolve(WidgetFetchState.NEEDS_SIGN_IN, null, now - 1_000_000, now),
        )
    }
}
