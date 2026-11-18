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
 *
 * Bypass (UPS pass-through): some firmwares report the backup-port load even while the grid feeds it. (A) ongrid
 * < −50 W wins over a backup draw → light ON. (B) during a draw, expected energy E = ∫backup W dt vs the observed
 * drop D of bat_wh (~5 Wh steps): E ≥ 30 Wh and D < 0.25·E → grid-fed (`bypass_no_discharge`, back-dated). A or B
 * learns `bypassLoadSeen` (persisted per device by the caller). Learned — or forced by [GridBypass.mode] "yes" — a
 * new draw is only a *pending loss* (state kept) until the battery visibly discharges (D ≥ 10 Wh and D ≥ 0.4·E),
 * then OFF back-dated to the window start. The window restarts after each decision, after 120 Wh without one, and
 * when the draw stops, a poll fails, the gap exceeds confirmGap or bat_wh is missing. Mode "no" = legacy rules.
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
    val lostBy: String? = null,
    val bypassLoadSeen: Boolean = false,
    val bypassActive: Boolean = false,
    val drawSince: Double? = null,
    val drawStartWh: Double? = null,
    val drawWh: Double = 0.0,
) {
    internal fun noWindow() = copy(drawSince = null, drawStartWh = null, drawWh = 0.0, bypassActive = false)
}

data class GridSignal(
    val lost: Boolean = false,
    val returnedAt: Double? = null,
    val tripW: Double? = null,
    val pending: Boolean = false,
    val lostAt: Double? = null,
    val pendingLoss: Boolean = false,
)

data class GridSpan(val start: Double, val end: Double, val state: String)

/** One poll for the tracker: epoch seconds, backup-output and grid-port powers (null = no ES answer), bat_wh. */
data class GridPoint(val ts: Double, val offgridW: Double?, val ongridW: Double?, val batWh: Double? = null)

/** Bypass handling: [mode] auto / yes / no (setting), [seen] = the persisted learned flag of this device. */
data class GridBypass(val mode: String = AUTO, val seen: Boolean = false) {
    val normalized: String get() = normalize(mode)

    companion object {
        const val AUTO = "auto"
        const val YES = "yes"
        const val NO = "no"
        fun normalize(value: String?): String = when (value?.trim()?.lowercase()) {
            YES, "1", "true", "on" -> YES
            NO, "0", "false", "off" -> NO
            else -> AUTO
        }
    }
}

object GridHysteresis {
    const val RETURN_CONFIRM_SECONDS = 300.0
    const val GRID_CHARGE_W = -50.0
    const val TRIP_MIN_W = 500.0
    const val TRIP_RISE_MIN_W = 300.0
    const val TRIP_RISE_STEP_W = 150.0
    const val DEFAULT_CONFIRM_GAP_SECONDS = 120.0
    const val BYPASS_WINDOW_WH = 30.0
    const val BYPASS_MAX_DROP_RATIO = 0.25
    const val DISCHARGE_MIN_WH = 10.0
    const val DISCHARGE_MIN_RATIO = 0.4
    const val BYPASS_WINDOW_MAX_WH = 120.0

    /** Longest pause between two polls that still counts as consecutive: ≥ 120 s and 2.5 × the poll interval. */
    fun confirmGapSeconds(pollSeconds: Double): Double = maxOf(DEFAULT_CONFIRM_GAP_SECONDS, 2.5 * pollSeconds)

    fun step(
        previous: GridTracker?,
        ts: Double,
        offgridW: Double?,
        ongridW: Double?,
        thresholdW: Double = GridStateDetector.DEFAULT_THRESHOLD_W,
        confirmGap: Double = DEFAULT_CONFIRM_GAP_SECONDS,
        batWh: Double? = null,
        bypass: String = GridBypass.AUTO,
    ): Pair<GridTracker, GridSignal> {
        var t = previous ?: GridTracker()
        val offgrid = offgridW?.takeIf(Double::isFinite)
        val ongrid = ongridW?.takeIf(Double::isFinite)
        val wh = batWh?.takeIf(Double::isFinite)
        val mode = GridBypass.normalize(bypass)
        if (offgrid == null) {
            return t.noWindow().copy(zeroSince = null, failedSinceBackup = true, lastTs = ts) to GridSignal(pending = t.state == "off")
        }
        if (t.lastTs != null && ts - t.lastTs!! > confirmGap) t = t.noWindow().copy(zeroSince = null)
        var signal: GridSignal
        val gridImport = ongrid != null && ongrid < GRID_CHARGE_W
        if (offgrid >= thresholdW && mode != GridBypass.NO) {
            val (nt, ns) = draw(t, ts, offgrid, wh, gridImport, mode)
            t = nt
            signal = ns
        } else if (offgrid >= thresholdW) {
            val lost = t.state != "off"
            t = t.copy(state = "off", offSince = if (t.state == "off" && t.offSince != null) t.offSince else ts, onSince = null,
                zeroSince = null, tripped = false, tripW = null, tripAt = null, confirmedBy = null,
                lostBy = if (lost) "backup_draw" else t.lostBy)
            signal = GridSignal(lost = lost, lostAt = if (lost) ts else null)
        } else if (gridImport) {
            t = t.noWindow()
            val wasOff = t.state == "off"
            t = t.copy(state = "on", offSince = null, onSince = if (t.state != "on") ts else (t.onSince ?: ts), zeroSince = null,
                tripped = false, confirmedBy = if (wasOff) "grid_charging" else t.confirmedBy)
            signal = GridSignal(returnedAt = if (wasOff) ts else null)
        } else if (t.state == "on") {
            t = t.noWindow().copy(zeroSince = null)
            signal = GridSignal()
        } else {
            t = t.noWindow()
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

    /** The backup output draws and bypass handling is on (auto / yes): rules A and B, learned mode (gridstate._draw). */
    private fun draw(start: GridTracker, ts: Double, offgrid: Double, wh: Double?, gridImport: Boolean, mode: String): Pair<GridTracker, GridSignal> {
        var t = start
        t = when {
            wh == null -> t.copy(drawSince = null, drawStartWh = null, drawWh = 0.0)
            t.drawSince == null || t.drawStartWh == null || t.lastBackup == null || t.lastTs == null ->
                t.copy(drawSince = ts, drawStartWh = wh, drawWh = 0.0)
            else -> t.copy(drawWh = t.drawWh + (t.lastBackup!! + offgrid) / 2.0 * maxOf(0.0, ts - t.lastTs!!) / 3600.0)
        }
        val window = t.drawSince != null && t.drawStartWh != null
        val expected = if (window) t.drawWh else 0.0
        val drop = if (window) t.drawStartWh!! - wh!! else 0.0
        fun GridTracker.restart() = if (wh != null) copy(drawSince = ts, drawStartWh = wh, drawWh = 0.0) else this
        val learned = t.bypassLoadSeen || mode == GridBypass.YES
        val since = if (window) t.drawSince!! else ts
        if (gridImport || (window && expected >= BYPASS_WINDOW_WH && drop < BYPASS_MAX_DROP_RATIO * expected)) {
            val basis = if (gridImport) "grid_charging" else "bypass_no_discharge"
            val wasOff = t.state == "off"
            val back = if (gridImport) ts else since
            t = t.copy(state = "on", offSince = null, onSince = if (t.state == "on" && t.onSince != null) t.onSince else back,
                zeroSince = null, tripped = false, tripW = null, tripAt = null,
                confirmedBy = if (t.state != "on") basis else t.confirmedBy,
                bypassLoadSeen = mode == GridBypass.AUTO || t.bypassLoadSeen, bypassActive = true).restart()
            return t to GridSignal(returnedAt = if (wasOff) back else null)
        }
        if (window && drop >= DISCHARGE_MIN_WH && drop >= DISCHARGE_MIN_RATIO * expected) {
            val lost = t.state != "off"
            val at = if (learned) since else ts
            t = t.copy(state = "off", offSince = if (!lost && t.offSince != null) t.offSince else at, onSince = null,
                zeroSince = null, tripped = false, tripW = null, tripAt = null, confirmedBy = null,
                lostBy = if (lost) (if (learned) "battery_discharge" else "backup_draw") else t.lostBy,
                bypassActive = false).restart()
            return t to GridSignal(lost = lost, lostAt = if (lost) at else null)
        }
        if (window && expected >= BYPASS_WINDOW_MAX_WH) t = t.restart()
        if (learned && t.state != "off" && !t.bypassActive) return t.copy(zeroSince = null) to GridSignal(pendingLoss = true)
        if (learned && t.bypassActive) return t.copy(zeroSince = null) to GridSignal()
        val lost = t.state != "off"
        t = t.copy(state = "off", offSince = if (!lost && t.offSince != null) t.offSince else ts, onSince = null,
            zeroSince = null, tripped = false, tripW = null, tripAt = null, confirmedBy = null,
            lostBy = if (lost) "backup_draw" else t.lostBy, bypassActive = false)
        return t to GridSignal(lost = lost, lostAt = if (lost) ts else null)
    }

    private fun seed(bypass: GridBypass): GridTracker? =
        if (bypass.seen && bypass.normalized != GridBypass.NO) GridTracker(bypassLoadSeen = true) else null

    /** Replays [points] (any order) and returns the final tracker plus the signal of the last point. */
    fun replay(
        points: List<GridPoint>,
        thresholdW: Double = GridStateDetector.DEFAULT_THRESHOLD_W,
        confirmGap: Double = DEFAULT_CONFIRM_GAP_SECONDS,
        bypass: GridBypass = GridBypass(),
    ): Pair<GridTracker, GridSignal> {
        var tracker: GridTracker? = seed(bypass)
        var signal = GridSignal()
        points.sortedBy { it.ts }.forEach { p ->
            val (t, s) = step(tracker, p.ts, p.offgridW, p.ongridW, thresholdW, confirmGap, p.batWh, bypass.normalized)
            tracker = t
            signal = s
        }
        return (tracker ?: GridTracker()) to signal
    }

    /**
     * Merged on/off/unknown spans over [start, now] — same rules as gridstate.timeline: a span takes the state after
     * its earlier poll, a return confirmed by 5 min without load (or «no discharge» in bypass) is back-dated to the
     * start of that run, a battery-confirmed loss (learned bypass) to the start of its window; gaps longer than
     * [maxGap], time before the first poll and an unconfirmed state are unknown; a stale last poll is not extended.
     */
    fun timeline(
        points: List<GridPoint>,
        start: Double,
        now: Double,
        thresholdW: Double = GridStateDetector.DEFAULT_THRESHOLD_W,
        confirmGap: Double = DEFAULT_CONFIRM_GAP_SECONDS,
        maxGap: Double = 300.0,
        bypass: GridBypass = GridBypass(),
    ): List<GridSpan> {
        val sorted = points.filter { it.ts <= now }.sortedBy { it.ts }
        val states = mutableListOf<Pair<Double, String>>()
        var tracker: GridTracker? = seed(bypass)
        sorted.forEach { p ->
            val (t, signal) = step(tracker, p.ts, p.offgridW, p.ongridW, thresholdW, confirmGap, p.batWh, bypass.normalized)
            tracker = t
            val backTo = signal.returnedAt ?: signal.lostAt?.takeIf { signal.lost }
            val backState = if (signal.returnedAt != null) "on" else "off"
            backTo?.let { back ->
                for (i in states.indices.reversed()) {
                    if (states[i].first < back) break
                    states[i] = states[i].first to backState
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
