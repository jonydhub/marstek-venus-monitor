package com.doloh.marstekwidget.data

import android.content.Context
import com.doloh.marstekwidget.direct.BatteryFlowCalculator
import com.doloh.marstekwidget.direct.CapacitySample
import com.doloh.marstekwidget.direct.DeviceInfo
import com.doloh.marstekwidget.direct.DirectStatusComposer
import com.doloh.marstekwidget.direct.GridHysteresis
import com.doloh.marstekwidget.direct.GridPoint
import com.doloh.marstekwidget.direct.LoadStatsAccumulator
import com.doloh.marstekwidget.direct.HistoryMath
import com.doloh.marstekwidget.direct.HistorySample
import com.doloh.marstekwidget.direct.MarstekRpc
import com.doloh.marstekwidget.direct.MarstekUdpClient
import com.doloh.marstekwidget.direct.RpcResult
import com.doloh.marstekwidget.outage.KYIV
import com.doloh.marstekwidget.outage.OutageStatusBuilder
import com.doloh.marstekwidget.settings.AppSettings
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.domain.BatteryDirection
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.JournalDetector
import com.doloh.marstekwidget.domain.JournalObservation
import com.doloh.marstekwidget.domain.PowerFields
import com.doloh.marstekwidget.notify.AlertNotifier
import com.doloh.marstekwidget.widget.WidgetPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime

sealed interface DirectPoll {
    data class Ok(val root: JSONObject) : DirectPoll
    data class Failed(val message: String) : DirectPoll
}

/** 48 h of samples in a small CSV file (one line per ≥30 s). */
class HistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "history.csv")

    @Synchronized
    fun rows(now: Double): List<HistorySample> {
        if (!file.exists()) return emptyList()
        val all = file.readLines().mapNotNull(HistorySample::decode)
        val kept = HistoryMath.prune(all, now)
        if (all.size - kept.size > 200) file.writeText(kept.joinToString("\n", postfix = "\n") { it.encode() })
        return kept
    }

    @Synchronized
    fun append(sample: HistorySample) {
        file.appendText(sample.encode() + "\n")
    }
}

/**
 * Direct (LAN) mode: polls the battery's Open API with read-only Get methods and composes the same
 * status JSON the server produces. State (capacity samples, last slow-method answers) survives restarts.
 */
object DirectEngine {
    private const val FULL_EVERY_MS = 60_000L
    /** Every poll is stored (the light hysteresis replays them); only near-duplicates from a widget + app poll are skipped. */
    private const val HISTORY_EVERY_S = 10.0
    private val lastAnswers = mutableMapOf<String, RpcResult.Ok>()
    private var lastFullAt = 0L
    private var lastHistoryTs = 0.0

    private val CHART_KINDS = setOf("light_lost", "light_returned", "charging_started", "charged_full", "anomaly_overload_trip")

    /** Journal: feed one poll into the detector, persist new events, notify anomalies. Never breaks telemetry. */
    private fun journal(context: Context, obs: JournalObservation, nowMs: Long) {
        runCatching {
            val store = JournalStore(context)
            val threshold = WidgetPreferences(context).anomalyThresholdWatts()
            val (events, memory) = JournalDetector.step(store.memory(), obs, nowMs, threshold)
            store.saveMemory(memory)
            store.append(events, nowMs)
            AlertNotifier.notifyJournal(context, events)
        }
    }

    private fun failed(context: Context, message: String, nowMs: Long): DirectPoll.Failed {
        // A poll without an answer is kept as a bare marker: it breaks the light-return run (GridHysteresis).
        runCatching { HistoryStore(context).append(HistorySample(nowMs / 1000.0, null, null, null, null, null, null, null)) }
        journal(context, JournalObservation(ok = false), nowMs)
        return DirectPoll.Failed(message)
    }

    @Synchronized
    fun poll(context: Context, settings: AppSettings, cachedOutage: CachedOutage?): DirectPoll {
        val host = settings.deviceHost ?: return DirectPoll.Failed("Не вказано IP батареї")
        val nowMs = System.currentTimeMillis()
        val full = nowMs - lastFullAt > FULL_EVERY_MS || lastAnswers.isEmpty()
        val methods = if (full) DirectStatusComposer.POLL_METHODS else DirectStatusComposer.FAST_METHODS
        val results = linkedMapOf<String, com.doloh.marstekwidget.direct.RpcResult>()
        try {
            DatagramUdpTransport(host, settings.devicePort).use { transport ->
                val client = MarstekUdpClient(transport, host, timeoutMs = 1500, attempts = 3, retryDelayMs = 0, minGapMs = 1000)
                methods.forEach { method -> results[method] = client.query(method) } // client paces ≥1 s (VenusE drops bursts)
            }
        } catch (e: Exception) {
            return failed(context, "Мережа недоступна: ${e.javaClass.simpleName}", nowMs)
        }
        if (results["Bat.GetStatus"] !is RpcResult.Ok) {
            return failed(context, "Батарея не відповідає ($host:${settings.devicePort})", nowMs)
        }
        if (full) lastFullAt = nowMs
        results.forEach { (m, r) -> if (r is RpcResult.Ok) lastAnswers[m] = r }
        DirectStatusComposer.POLL_METHODS.forEach { m -> if (results[m] !is RpcResult.Ok) lastAnswers[m]?.let { results[m] = it } }

        val bat = (results["Bat.GetStatus"] as RpcResult.Ok).data
        (results["ES.GetStatus"] as? RpcResult.Ok)?.let { results["ES.GetStatus"] = it.copy(data = PowerFields.normalize(it.data)) }
        val es = (results["ES.GetStatus"] as? RpcResult.Ok)?.data
        val capacity = bat.optDouble("bat_capacity", Double.NaN).takeIf(Double::isFinite)
        val rated = bat.optDouble("rated_capacity", Double.NaN).takeIf(Double::isFinite)
            ?: es?.optDouble("bat_cap", Double.NaN)?.takeIf(Double::isFinite)
        rated?.let { SettingsStore(context).rememberDetectedRated(it) }
        val ratedForEta = rated ?: settings.ratedCapacityWh

        val nowS = nowMs / 1000.0
        val samples = loadSamples(context).toMutableList()
        if (capacity != null) samples += CapacitySample(nowS, capacity)
        val trimmed = samples.filter { nowS - it.t <= BatteryFlowCalculator.TREND_WINDOW_SECONDS }.takeLast(120)
        saveSamples(context, trimmed)
        val offgrid = PowerFields.read(es, "offgrid_power")
        val ongrid = PowerFields.read(es, "ongrid_power")
        val flow = BatteryFlowCalculator.calculate(trimmed, capacity, ratedForEta, ongridW = ongrid, offgridW = offgrid)

        val history = HistoryStore(context)
        val socValue = bat.optDouble("soc", Double.NaN).takeIf(Double::isFinite)
        val sample = HistorySample(nowS, socValue?.toInt(), capacity, flow.powerW.takeIf { flow.available }, flow.direction,
            if (es != null) offgrid else null, if (es != null) ongrid else null, bat.optDouble("bat_temp", Double.NaN).takeIf(Double::isFinite))
        val before = runCatching { history.rows(nowS) }.getOrDefault(emptyList())
        var rows = before
        if (nowS - lastHistoryTs >= HISTORY_EVERY_S) {
            lastHistoryTs = nowS
            runCatching { history.append(sample) }
            rows = before + sample
            HistoryMath.drawIncrement(before.lastOrNull(), sample, settings.backupThresholdW)?.let { (wh, seconds) ->
                val today = java.time.LocalDate.now(KYIV)
                prefs(context).edit().putString("load_stats", LoadStatsAccumulator.add(prefs(context).getString("load_stats", null),
                    today.toString(), wh, seconds, LoadStatsAccumulator.windowStart(today))).apply()
            }
        }
        val measured = HistoryMath.measuredBackupLoad(rows, nowS, settings.backupThresholdW)
        // Light only from the backup output, through the hysteresis (GridHysteresis, same rules as the server):
        // lost at once, back only on grid charging or ≥ 5 min without load; no ES answer = unknown, never «off».
        val points = HistoryMath.gridPoints(rows).let { if (it.lastOrNull()?.ts == nowS) it else it + GridPoint(nowS, sample.offgridW, sample.ongridW) }
        val (tracker, signal) = GridHysteresis.replay(points, settings.backupThresholdW, HistoryMath.CONFIRM_GAP_SECONDS)
        val grid = if (es == null) "unknown" else tracker.state ?: "unknown"
        val smoothed = if (grid == "off") HistoryMath.smoothedBackupLoad(rows, nowS, tracker.offSince) else null
        fun iso(t: Double?) = t?.let { Instant.ofEpochMilli((it * 1000).toLong()).atZone(KYIV).toOffsetDateTime().withNano(0).toString() }
        val gridDetail = JSONObject()
            .put("basis", "backup_output_hysteresis")
            .put("pending_return", signal.pending)
            .put("outage_since", iso(tracker.offSince) ?: JSONObject.NULL)
            .put("zero_load_since", iso(tracker.zeroSince) ?: JSONObject.NULL)
            .put("light_since", iso(tracker.onSince) ?: JSONObject.NULL)
            .put("confirmed_by", tracker.confirmedBy ?: JSONObject.NULL)
            .put("return_confirm_seconds", GridHysteresis.RETURN_CONFIRM_SECONDS.toInt())
            .put("overload_trip", if (tracker.tripped && tracker.tripW != null)
                JSONObject().put("last_w", Math.round(tracker.tripW!!)).put("at", iso(tracker.tripAt) ?: JSONObject.NULL) else JSONObject.NULL)
        val outage = OutageStatusBuilder.build(
            config = settings.outageConfig,
            schedule = cachedOutage?.schedule,
            error = cachedOutage?.error,
            now = ZonedDateTime.now(KYIV),
            gridState = grid,
            storedWh = capacity,
            flow = flow,
            expectedLoadW = settings.averageLoadW,
            measuredLoadW = measured,
            fetchedAt = cachedOutage?.fetchedAt,
            backupOutputW = offgrid,
            ratedWh = ratedForEta,
            socPercent = socValue,
            floorPct = settings.dischargeFloorPct,
            backupThresholdW = settings.backupThresholdW,
            smoothedLoadW = smoothed,
            gridDetail = gridDetail,
        )
        val mode = (results["ES.GetMode"] as? RpcResult.Ok)?.data?.optString("mode")?.takeIf(String::isNotBlank)
        journal(context, JournalObservation(
            ok = true,
            gridState = when (grid) { "on" -> GridState.ON; "off" -> GridState.OFF; else -> GridState.UNKNOWN },
            direction = BatteryDirection.fromApi(flow.direction),
            batteryWatts = flow.powerW.takeIf { flow.available },
            batteryMeasured = !flow.estimated,
            backupWatts = offgrid,
            soc = socValue?.toInt(),
            temperatureC = bat.optDouble("bat_temp", Double.NaN).takeIf(Double::isFinite),
            returnedAtMillis = signal.returnedAt?.let { (it * 1000).toLong() },
            returnBasis = tracker.confirmedBy.takeIf { signal.returnedAt != null },
            overloadTripW = signal.tripW,
            mode = mode,
        ), nowMs)
        val chart = runCatching {
            JournalStore(context).events(200).filter { it.kind in CHART_KINDS && it.tsMillis >= nowMs - 24 * 3600_000L }
                .sortedBy { it.tsMillis }.map { it.tsMillis to it.kind }
        }.getOrDefault(emptyList())
        val insights = HistoryMath.insights(rows, nowS, offgrid, bat.optDouble("bat_temp", Double.NaN).takeIf(Double::isFinite),
            settings.backupThresholdW, chart,
            loadStats = LoadStatsAccumulator.stats(prefs(context).getString("load_stats", null),
                LoadStatsAccumulator.windowStart(java.time.LocalDate.now(KYIV))))
        val root = DirectStatusComposer.compose(
            results, flow, outage, insights, Instant.ofEpochMilli(nowMs).toString(), settings.deviceModel,
            fallbackRatedWh = settings.ratedCapacityWh,
        )
        return DirectPoll.Ok(root)
    }

    /** «Перевірити з'єднання»: model via discovery + battery status. Blocking. */
    fun probe(host: String, port: Int): Pair<DeviceInfo?, RpcResult> =
        DatagramUdpTransport(host, port).use { transport ->
            val client = MarstekUdpClient(transport, host, timeoutMs = 1500, attempts = 3, retryDelayMs = 0, minGapMs = 1000)
            val device = (client.query(MarstekRpc.DISCOVERY_METHOD) as? RpcResult.Ok)?.let { DeviceInfo.fromGetDevice(it, host, port) }
            device to client.query("Bat.GetStatus")
        }

    private fun loadSamples(context: Context): List<CapacitySample> = runCatching {
        val arr = JSONArray(prefs(context).getString("samples", "[]"))
        (0 until arr.length()).map { arr.getJSONArray(it).let { p -> CapacitySample(p.getDouble(0), p.getDouble(1)) } }
    }.getOrDefault(emptyList())

    private fun saveSamples(context: Context, samples: List<CapacitySample>) {
        val arr = JSONArray()
        samples.forEach { arr.put(JSONArray().put(it.t).put(it.wh)) }
        prefs(context).edit().putString("samples", arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("marstek_direct", Context.MODE_PRIVATE)
}
