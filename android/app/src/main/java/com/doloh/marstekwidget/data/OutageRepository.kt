package com.doloh.marstekwidget.data

import android.content.Context
import com.doloh.marstekwidget.outage.CachingHttpFetcher
import com.doloh.marstekwidget.outage.KYIV
import com.doloh.marstekwidget.outage.OutageConfig
import com.doloh.marstekwidget.outage.OutageProviderChain
import com.doloh.marstekwidget.outage.OutageRegions
import com.doloh.marstekwidget.outage.OutageSchedule
import com.doloh.marstekwidget.outage.OutageScheduleCodec
import com.doloh.marstekwidget.outage.SvitloCatalog
import com.doloh.marstekwidget.outage.SvitloParser
import com.doloh.marstekwidget.outage.SvitloSelector
import com.doloh.marstekwidget.outage.UaOutagesParser
import com.doloh.marstekwidget.outage.fetchTextPublic
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class CachedOutage(val schedule: OutageSchedule?, val fetchedAt: String?, val error: String?)

/** Fetches the configured queue's schedule through the provider chain at most once per 10 minutes. */
class OutageRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("marstek_outage", Context.MODE_PRIVATE)
    private val fetcher = CachingHttpFetcher(AndroidHttpFetcher(), FileHttpCache(File(app.cacheDir, "http")))

    fun cached(config: OutageConfig?): CachedOutage {
        if (config == null || prefs.getString(KEY_ID, null) != key(config)) return CachedOutage(null, null, null)
        return CachedOutage(
            OutageScheduleCodec.decode(prefs.getString(KEY_SCHEDULE, null)),
            prefs.getString(KEY_FETCHED_AT, null),
            prefs.getString(KEY_ERROR, null),
        )
    }

    /** Blocking; call off the main thread. */
    fun refreshIfDue(config: OutageConfig?, force: Boolean = false): CachedOutage {
        config ?: return CachedOutage(null, null, null)
        if (!inFlight.compareAndSet(false, true)) return cached(config)
        try {
            return refreshLocked(config, force)
        } finally {
            inFlight.set(false)
        }
    }

    private fun refreshLocked(config: OutageConfig, force: Boolean): CachedOutage {
        val sameKey = prefs.getString(KEY_ID, null) == key(config)
        val last = prefs.getLong(KEY_ATTEMPT, 0L)
        val now = System.currentTimeMillis()
        if (sameKey && !force && now - last in 0 until MIN_INTERVAL_MS) return cached(config)
        val result = runCatching { OutageProviderChain.standard(fetcher).fetch(config.regionId, config.queue, LocalDate.now(KYIV)) }
            .getOrNull()
        val edit = prefs.edit().putLong(KEY_ATTEMPT, now).putString(KEY_ID, key(config))
        if (result?.schedule != null) {
            edit.putString(KEY_SCHEDULE, OutageScheduleCodec.encode(result.schedule))
                .putString(KEY_FETCHED_AT, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(now).atZone(KYIV)))
                .remove(KEY_ERROR)
        } else {
            if (!sameKey) edit.remove(KEY_SCHEDULE).remove(KEY_FETCHED_AT)
            edit.putString(KEY_ERROR, result?.notes?.joinToString("; ") ?: "fetch_failed")
        }
        edit.apply()
        return cached(config)
    }

    /** Queue list for the picker: ua-outages groups, else svitlo.live catalog, else the common 1.1–6.2 set. */
    fun queuesFor(regionId: String): List<String> {
        val region = OutageRegions.byId(regionId) ?: return DEFAULT_QUEUES
        if (region.inUaOutages) {
            runCatching { UaOutagesParser.groups(fetcher.fetchTextPublic(UaOutagesParser.URL), region.id) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val primary = runCatching { SvitloParser.parseRoot(fetcher.fetchTextPublic(SvitloSelector.PRIMARY_URL)) }.getOrNull()
        val fallback = runCatching { SvitloParser.parseRoot(fetcher.fetchTextPublic(SvitloSelector.FALLBACK_URL)) }.getOrNull()
        SvitloCatalog.merge(primary, fallback).firstOrNull { it.id == region.svitloSlug }?.queues
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return DEFAULT_QUEUES
    }

    private fun key(config: OutageConfig) = "${config.regionId}|${config.queue}"

    companion object {
        private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        const val MIN_INTERVAL_MS = 10 * 60_000L
        val DEFAULT_QUEUES = (1..6).flatMap { listOf("$it.1", "$it.2") }
        private const val KEY_ID = "id"
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_ERROR = "error"
        private const val KEY_ATTEMPT = "attempt_at"
    }
}
