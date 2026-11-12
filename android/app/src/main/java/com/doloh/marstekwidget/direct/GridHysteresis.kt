package com.doloh.marstekwidget.direct

/**
 * Port of server/gridstate.py (keep in sync): the confirmed light state from the backup output, with hysteresis.
 *
 * A Venus E in UPS mode feeds its backup output only without the grid, but a load there (an inverter air conditioner)
 * pauses on its own — «0 W» does not prove the light is back. Light lost: immediately (backup ≥ threshold).
 * Light back: (a) ongrid < −50 W (the battery charges from the grid) or (b) < threshold for ≥ 5 min with no failed poll.
 * Overload trip: last backup ≥ 500 W (or ≥ 300 W and +150 W since the previous reading), then a poll without an
 * answer, then 0 W — reported once; after it only grid charging confirms the light.
 * [step] with `offgridW == null` = the battery did not answer ES.GetStatus.
 */
data class GridTracker(
    val state: String? = null,
    val offSince: Double? = null,
    val onSince: Double? = null,
    val zeroSince: Double? = null,
    val lastTs: Double? = null,
    val lastBackup: Double? = null,
    val prevBackup: Double? = null,
    val failedSinceBackup: Boolean = false,
    val tripped: Boolean = false,
    val tripW: Double? = null,
    val tripAt: Double? = null,
    val confirmedBy: String? = null,
)

data class GridSignal(
    val lost: Boolean = false,
    val returnedAt: Double? = null,
    val tripW: Double? = null,
    val pending: Boolean = false,
)

data class GridSpan(val start: Double, val end: Double, val state: String)

/** One poll for the tracker: epoch seconds, backup-output and grid-port powers (null = no ES answer). */
data class GridPoint(val ts: Double, val offgridW: Double?, val ongridW: Double?)

object GridHysteresis {
    const val RETURN_CONFIRM_SECONDS = 300.0
    const val GRID_CHARGE_W = -50.0
    const val TRIP_MIN_W = 500.0
    const val TRIP_RISE_MIN_W = 300.0
    const val TRIP_RISE_STEP_W = 150.0
    const val DEFAULT_CONFIRM_GAP_SECONDS = 120.0

    /** Longest pause between two polls that still counts as consecutive: ≥ 120 s and 2.5 × the poll interval. */
    fun confirmGapSeconds(pollSeconds: Double): Double = maxOf(DEFAULT_CONFIRM_GAP_SECONDS, 2.5 * pollSeconds)

    fun step(
        previous: GridTracker?,
        ts: Double,
        offgridW: Double?,
        ongridW: Double?,
        thresholdW: Double = GridStateDetector.DEFAULT_THRESHOLD_W,
        confirmGap: Double = DEFAULT_CONFIRM_GAP_SECONDS,
    ): Pair<GridTracker, GridSignal> {
        var t = previous ?: GridTracker()
        val offgrid = offgridW?.takeIf(Double::isFinite)
        val ongrid = ongridW?.takeIf(Double::isFinite)
        if (offgrid == null) {
            return t.copy(zeroSince = null, failedSinceBackup = true, lastTs = ts) to GridSignal(pending = t.state == "off")
        }
        if (t.lastTs != null && ts - t.lastTs!! > confirmGap) t = t.copy(zeroSince = null)
        val signal: GridSignal
        if (offgrid >= thresholdW) {
            val lost = t.state != "off"
            t = t.copy(state = "off", offSince = if (t.state == "off" && t.offSince != null) t.offSince else ts, onSince = null,
                zeroSince = null, tripped = false, tripW = null, tripAt = null, confirmedBy = null)
            signal = GridSignal(lost = lost)
        } else if (ongrid != null && ongrid < GRID_CHARGE_W) {
            val wasOff = t.state == "off"
            t = t.copy(state = "on", offSince = null, onSince = if (t.state != "on") ts else (t.onSince ?: ts), zeroSince = null,
                tripped = false, confirmedBy = if (wasOff) "grid_charging" else t.confirmedBy)
            signal = GridSignal(returnedAt = if (wasOff) ts else null)
        } else if (t.state == "on") {
            t = t.copy(zeroSince = null)
            signal = GridSignal()
        } else {
            var tripNow: Double? = null
            val last = t.lastBackup
            val prev = t.prevBackup
            if (t.state == "off" && t.failedSinceBackup && !t.tripped && last != null &&
                (last >= TRIP_MIN_W || (last >= TRIP_RISE_MIN_W && prev != null && last - prev >= TRIP_RISE_STEP_W))
            ) {
                tripNow = last
                t = t.copy(tripped = true, tripW = last, tripAt = ts)
            }
            val zeroSince = t.zeroSince ?: ts
            if (!t.tripped && ts - zeroSince >= RETURN_CONFIRM_SECONDS) {
                val wasOff = t.state == "off"
                val back = if (wasOff) zeroSince else ts
                t = t.copy(state = "on", offSince = null, onSince = back, zeroSince = null, confirmedBy = if (wasOff) "idle_5min" else null)
                signal = GridSignal(returnedAt = if (wasOff) back else null, tripW = tripNow)
            } else {
                t = t.copy(zeroSince = zeroSince)
                signal = GridSignal(tripW = tripNow, pending = t.state == "off")
            }
        }
        t = t.copy(lastTs = ts, prevBackup = t.lastBackup, lastBackup = offgrid, failedSinceBackup = false)
        return t to signal
    }

    /** Replays [points] (any order) and returns the final tracker plus the signal of the last point. */
    fun replay(
        points: List<GridPoint>,
        thresholdW: Double = GridStateDetector.DEFAULT_THRESHOLD_W,
        confirmGap: Double = DEFAULT_CONFIRM_GAP_SECONDS,
    ): Pair<GridTracker, GridSignal> {
        var tracker: GridTracker? = null
        var signal = GridSignal()
        points.sortedBy { it.ts }.forEach { p ->
            val (t, s) = step(tracker, p.ts, p.offgridW, p.ongridW, thresholdW, confirmGap)
            tracker = t
            signal = s
        }
        return (tracker ?: GridTracker()) to signal
    }

    /**
     * Merged on/off/unknown spans over [start, now] — same rules as gridstate.timeline: a span takes the state after
     * its earlier poll, a return confirmed by 5 min without load is back-dated to the start of that run, gaps longer
     * than [maxGap], time before the first poll and an unconfirmed state are unknown; a stale last poll is not extended.
     */
    fun timeline(
        points: List<GridPoint>,
        start: Double,
        now: Double,
        thresholdW: Double = GridStateDetector.DEFAULT_THRESHOLD_W,
        confirmGap: Double = DEFAULT_CONFIRM_GAP_SECONDS,
        maxGap: Double = 300.0,
    ): List<GridSpan> {
        val sorted = points.filter { it.ts <= now }.sortedBy { it.ts }
        val states = mutableListOf<Pair<Double, String>>()
        var tracker: GridTracker? = null
        sorted.forEach { p ->
            val (t, signal) = step(tracker, p.ts, p.offgridW, p.ongridW, thresholdW, confirmGap)
            tracker = t
            signal.returnedAt?.let { back ->
                for (i in states.indices.reversed()) {
                    if (states[i].first < back) break
                    states[i] = states[i].first to "on"
                }
            }
            states += p.ts to (t.state ?: "unknown")
        }
        val merged = mutableListOf<DoubleArray>()
        val mergedStates = mutableListOf<String>()
        fun push(a: Double, b: Double, state: String) {
            if (b <= a) return
            if (merged.isNotEmpty() && mergedStates.last() == state && kotlin.math.abs(merged.last()[1] - a) < 1e-6) merged.last()[1] = b
            else { merged += doubleArrayOf(a, b); mergedStates += state }
        }
        var cursor = start
        states.forEachIndexed { i, (t0, state) ->
            val t1 = if (i + 1 < states.size) states[i + 1].first else now
            if (t1 - t0 > maxGap) return@forEachIndexed
            val a = maxOf(t0, start)
            val b = minOf(t1, now)
            if (b <= a) return@forEachIndexed
            if (a > cursor) push(cursor, a, "unknown")
            push(a, b, state)
            cursor = b
        }
        if (now > cursor) push(cursor, now, "unknown")
        return merged.indices.map { GridSpan(merged[it][0], merged[it][1], mergedStates[it]) }
    }
}
