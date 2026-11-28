package com.doloh.marstekwidget.data

import android.content.Context
import com.doloh.marstekwidget.domain.ExtraSchedule
import com.doloh.marstekwidget.domain.ExtraScheduleData
import com.doloh.marstekwidget.domain.ExtraSchedules
import com.doloh.marstekwidget.domain.ScheduleWindow
import com.doloh.marstekwidget.outage.CachingHttpFetcher
import com.doloh.marstekwidget.outage.KYIV
import com.doloh.marstekwidget.outage.OutageProviderChain
import com.doloh.marstekwidget.outage.OutageRegions
import com.doloh.marstekwidget.outage.OutageSchedule
import com.doloh.marstekwidget.outage.OutageScheduleCodec
import java.io.File
import java.time.LocalDate

/**
 * «Інші графіки»: the list (робота, батьки…) and one cached schedule per region|queue, each fetched through the
 * provider chain at most every 10 min. Own prefs file and in-flight flag: never touches the own-schedule cache
 * ([OutageRepository]). Display only — nothing here feeds alerts.
 */
class ExtraScheduleRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val fetcher by lazy { CachingHttpFetcher(AndroidHttpFetcher(), FileHttpCache(File(app.cacheDir, "http"))) }

    fun list(): List<ExtraSchedule> = ExtraSchedules.parse(prefs.getString(KEY_LIST, null))

    fun save(list: List<ExtraSchedule>) {
        val clean = ExtraSchedules.parse(ExtraSchedules.encode(list))
        val keep = clean.map { it.key }.toSet()
        val edit = prefs.edit().putString(KEY_LIST, ExtraSchedules.encode(clean))
        // drop caches of removed entries
        prefs.all.keys.filter { k -> PREFIXES.any { k.startsWith(it) } && k.substringAfter(':') !in keep }.forEach(edit::remove)
        edit.apply()
    }

    fun add(entry: ExtraSchedule): List<ExtraSchedule> = ExtraSchedules.add(list(), entry).also(::save)

    fun remove(key: String): List<ExtraSchedule> = list().filterNot { it.key == key }.also(::save)

    /** null = nothing fetched yet («…»). A region without any schedule source is known at once (no fetch). */
    fun cached(entry: ExtraSchedule): ExtraScheduleData? {
        val region = OutageRegions.byId(entry.region)
        if (region == null || !region.hasScheduleSource) return ExtraScheduleData(available = false)
        val schedule = OutageScheduleCodec.decode(prefs.getString(S + entry.key, null))
        val error = prefs.getString(E + entry.key, null)
        if (schedule == null && error == null) return null
        return toData(schedule, error)
    }

    fun cachedAll(): List<Pair<ExtraSchedule, ExtraScheduleData?>> = list().map { it to cached(it) }

    /**
     * Blocking; call off the main thread. Fetches every entry whose last attempt is older than 10 min ([force]: all,
     * or only [onlyKey]). Returns true when some cache changed.
     */
    fun refreshIfDue(force: Boolean = false, onlyKey: String? = null): Boolean {
        var changed = false
        val today = LocalDate.now(KYIV)
        for (entry in list()) {
            if (onlyKey != null && entry.key != onlyKey) continue
            if (OutageRegions.byId(entry.region)?.hasScheduleSource != true) continue
            val now = System.currentTimeMillis()
            val last = prefs.getLong(A + entry.key, 0L)
            if (!force && now - last in 0 until OutageRepository.MIN_INTERVAL_MS) continue
            // one fetch per schedule at a time (worker, main screen and Settings may ask together)
            if (!inFlight.add(entry.key)) continue
            try {
                val result = runCatching { OutageProviderChain.standard(fetcher).fetch(entry.region, entry.queue, today) }.getOrNull()
                val before = prefs.getString(S + entry.key, null) to prefs.getString(E + entry.key, null)
                val edit = prefs.edit().putLong(A + entry.key, now)
                if (result?.schedule != null) {
                    edit.putString(S + entry.key, OutageScheduleCodec.encode(result.schedule)).remove(E + entry.key)
                } else {
                    // keep the last good schedule; the error only shows when nothing was ever published
                    edit.putString(E + entry.key, result?.notes?.joinToString("; ") ?: "fetch_failed")
                }
                edit.apply()
                if (before != (prefs.getString(S + entry.key, null) to prefs.getString(E + entry.key, null))) changed = true
            } finally {
                inFlight.remove(entry.key)
            }
        }
        return changed
    }

    private fun toData(schedule: OutageSchedule?, error: String?): ExtraScheduleData = ExtraScheduleData(
        available = true,
        published = schedule?.publishedDates?.isNotEmpty() == true,
        scheduleDates = schedule?.publishedDates.orEmpty().map { it.toString() },
        windows = schedule?.windows.orEmpty().map { ScheduleWindow(it.start.toInstant().toEpochMilli(), it.end.toInstant().toEpochMilli(), it.maybe) },
        emergency = schedule?.emergency == true,
        error = error,
    )

    companion object {
        private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        const val PREFS = "marstek_extra_schedules"
        const val KEY_LIST = "list"
        private const val S = "schedule:"
        private const val E = "error:"
        private const val A = "attempt:"
        private val PREFIXES = listOf(S, E, A)
    }
}
