package com.doloh.marstekwidget.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.doloh.marstekwidget.direct.BatteryFlow
import com.doloh.marstekwidget.direct.GridStateDetector
import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.BatterySnapshotParser
import com.doloh.marstekwidget.domain.JournalEvent
import com.doloh.marstekwidget.notify.AlertNotifier
import com.doloh.marstekwidget.outage.KYIV
import com.doloh.marstekwidget.outage.OutageStatusBuilder
import com.doloh.marstekwidget.settings.AppSettings
import com.doloh.marstekwidget.settings.ConnectionMode
import com.doloh.marstekwidget.settings.ServerAuth
import com.doloh.marstekwidget.settings.ServerUrlValidator
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.widget.WidgetPreferences
import com.doloh.marstekwidget.widget.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

object AccessCookie {
    const val NAME = "CF_Authorization"

    fun present(url: String?): Boolean {
        url ?: return false
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        return cookies.split(';').any { it.trim().startsWith("$NAME=") }
    }
}

sealed interface TelemetryFetchResult {
    data class Success(val snapshot: BatterySnapshot) : TelemetryFetchResult
    data object NeedsSignIn : TelemetryFetchResult
    data class Failure(val message: String) : TelemetryFetchResult
}

/** Server mode: GET {base}/api/status with optional Bearer token or Cloudflare Access cookie. */
class MarstekApiClient(private val context: Context, private val settings: AppSettings, private val token: String?) {
    sealed interface Raw {
        data class Ok(val root: JSONObject) : Raw
        data object NeedsSignIn : Raw
        data class Failed(val message: String) : Raw
    }

    suspend fun fetchRaw(): Raw {
        val base = settings.serverUrl?.let(ServerUrlValidator::normalize) ?: return Raw.Failed("Не вказано адресу сервера")
        return fetchJson(ServerUrlValidator.statusUrl(base))
    }

    /** Server journal (GET /api/events, newest first); null when unreachable or older server without it. */
    suspend fun fetchEvents(limit: Int = 200): List<JournalEvent>? = (fetchJournal(limit) as? JournalFetch.Ok)?.events

    /** Server journal; tells an old server without /api/events (HTTP 404) apart from other failures. */
    suspend fun fetchJournal(limit: Int = 200): JournalFetch {
        val base = settings.serverUrl?.let(ServerUrlValidator::normalize) ?: return JournalFetch.Failed("Сервер не налаштовано")
        return when (val raw = fetchJson("${base.trimEnd('/')}/api/events?limit=$limit")) {
            is Raw.Ok -> {
                val arr = raw.root.optJSONArray("events") ?: return JournalFetch.Failed("Неочікувана відповідь сервера")
                val started = raw.root.optString("journal_started_at").takeIf(String::isNotBlank)
                    ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                JournalFetch.Ok((0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(JournalEvent::fromJson) }, started)
            }
            is Raw.Failed -> if (raw.message == "HTTP 404") JournalFetch.NotSupported else JournalFetch.Failed(raw.message)
            Raw.NeedsSignIn -> JournalFetch.Failed("Потрібен вхід")
        }
    }

    sealed class JournalFetch {
        data class Ok(val events: List<JournalEvent>, val startedAtMillis: Long?) : JournalFetch()
        object NotSupported : JournalFetch()
        data class Failed(val message: String) : JournalFetch()
    }

    private suspend fun fetchJson(statusUrl: String): Raw {
        var cookies: String? = null
        var userAgent: String? = null
        if (settings.serverAuth == ServerAuth.CF_ACCESS) {
            val pair = runCatching {
                withContext(Dispatchers.Main.immediate) {
                    CookieManager.getInstance().getCookie(statusUrl) to WebSettings.getDefaultUserAgent(context.applicationContext)
                }
            }.getOrDefault(null to null)
            cookies = pair.first
            userAgent = pair.second
            if (cookies.isNullOrBlank() || !cookies.split(';').any { it.trim().startsWith("${AccessCookie.NAME}=") }) return Raw.NeedsSignIn
        }
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(statusUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 12_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache")
                cookies?.let { connection.setRequestProperty("Cookie", it) }
                if (settings.serverAuth == ServerAuth.BEARER && !token.isNullOrBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                connection.setRequestProperty("User-Agent", userAgent ?: "MarstekEnergy/1.0.0 (Android)")
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        if (connection.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
                            return@withContext if (settings.serverAuth == ServerAuth.CF_ACCESS) Raw.NeedsSignIn else Raw.Failed("Сервер повернув HTML замість JSON")
                        }
                        Raw.Ok(JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }))
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN, in 300..399 ->
                        if (settings.serverAuth == ServerAuth.CF_ACCESS) Raw.NeedsSignIn
                        else Raw.Failed(if (settings.serverAuth == ServerAuth.BEARER) "Токен не прийнято (HTTP $status)" else "Потрібна авторизація (HTTP $status)")
                    else -> Raw.Failed("HTTP $status")
                }
            } catch (_: Exception) {
                Raw.Failed("Сервер недоступний")
            } finally {
                connection?.disconnect()
            }
        }
    }
}

/** One refresh for either mode; stores the snapshot, updates widgets and runs alerts. */
object TelemetryRepository {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun fetch(context: Context, settings: AppSettings = SettingsStore(context).load()): TelemetryFetchResult {
        if (!settings.configured) return TelemetryFetchResult.Failure("Підключення не налаштовано")
        val outageRepo = OutageRepository(context)
        return when (settings.mode) {
            ConnectionMode.DIRECT -> withContext(Dispatchers.IO) {
                // Network schedule fetch (≤ once per 10 min) runs alongside the UDP poll instead of delaying it.
                val cachedNow = outageRepo.cached(settings.outageConfig)
                val outage = if (cachedNow.schedule != null || settings.outageConfig == null) {
                    backgroundScope.launch { runCatching { outageRepo.refreshIfDue(settings.outageConfig) } }
                    cachedNow
                } else runCatching { outageRepo.refreshIfDue(settings.outageConfig) }.getOrNull()
                when (val poll = DirectEngine.poll(context, settings, outage)) {
                    is DirectPoll.Ok -> runCatching { TelemetryFetchResult.Success(BatterySnapshotParser.parse(poll.root)) }
                        .getOrElse { TelemetryFetchResult.Failure("Неочікувана відповідь батареї") }
                    is DirectPoll.Failed -> TelemetryFetchResult.Failure(poll.message)
                }
            }
            ConnectionMode.SERVER -> when (val raw = MarstekApiClient(context, settings, SettingsStore(context).token()).fetchRaw()) {
                is MarstekApiClient.Raw.Ok -> withContext(Dispatchers.IO) {
                    val root = raw.root
                    // A region chosen in the app overrides the server's own outage block.
                    settings.outageConfig?.let { config ->
                        val outage = runCatching { outageRepo.refreshIfDue(config) }.getOrNull()
                        root.put("power_outage", localOutageBlock(root, settings, outage))
                    }
                    runCatching { TelemetryFetchResult.Success(BatterySnapshotParser.parse(root)) }
                        .getOrElse { TelemetryFetchResult.Failure("Неочікувана відповідь сервера") }
                }
                MarstekApiClient.Raw.NeedsSignIn -> TelemetryFetchResult.NeedsSignIn
                is MarstekApiClient.Raw.Failed -> TelemetryFetchResult.Failure(raw.message)
            }
        }
    }

    private fun localOutageBlock(root: JSONObject, settings: AppSettings, outage: CachedOutage?): JSONObject {
        val methods = root.optJSONObject("methods") ?: JSONObject()
        val es = methods.optJSONObject("ES.GetStatus")
        val bat = methods.optJSONObject("Bat.GetStatus")?.optJSONObject("data")
        val offgrid = es?.optJSONObject("data")?.optDouble("offgrid_power", Double.NaN)?.takeIf(Double::isFinite)
        val f = root.optJSONObject("battery_flow") ?: JSONObject()
        val flow = BatteryFlow(f.optBoolean("available"), f.optDouble("power_w", Double.NaN).takeIf(Double::isFinite),
            f.optString("direction", "unknown"), 0, 0, null, null, null)
        val measured = root.optJSONObject("insights")?.optDouble("backup_avg_w_24h", Double.NaN)?.takeIf(Double::isFinite)
        return OutageStatusBuilder.build(
            settings.outageConfig, outage?.schedule, outage?.error, ZonedDateTime.now(KYIV),
            GridStateDetector.detect(offgrid, es?.optBoolean("ok", false) == true, settings.backupThresholdW),
            bat?.optDouble("bat_capacity", Double.NaN)?.takeIf(Double::isFinite), flow, settings.averageLoadW, measured,
            outage?.fetchedAt,
            backupOutputW = offgrid,
            ratedWh = bat?.optDouble("rated_capacity", Double.NaN)?.takeIf(Double::isFinite),
            socPercent = bat?.optDouble("soc", Double.NaN)?.takeIf(Double::isFinite),
            floorPct = settings.dischargeFloorPct,
            backupThresholdW = settings.backupThresholdW,
        )
    }

    /** Server mode: post anomaly notifications for server journal events not seen yet (first run only marks). */
    private suspend fun notifyServerJournal(context: Context) {
        val settings = SettingsStore(context).load()
        val preferences = WidgetPreferences(context)
        if (settings.mode != ConnectionMode.SERVER || !preferences.anomalyNotificationsEnabled()) return
        val events = runCatching { MarstekApiClient(context, settings, SettingsStore(context).token()).fetchEvents(50) }.getOrNull() ?: return
        val newest = events.maxOfOrNull { it.tsMillis } ?: return
        val seen = preferences.lastSeenServerEventMillis()
        if (seen >= 0) AlertNotifier.notifyJournal(context, events.filter { it.tsMillis > seen })
        preferences.setLastSeenServerEventMillis(maxOf(newest, seen))
    }

    suspend fun refreshAndStore(context: Context): TelemetryFetchResult {
        val preferences = WidgetPreferences(context)
        val result = fetch(context)
        when (result) {
            is TelemetryFetchResult.Success -> {
                preferences.saveSnapshot(result.snapshot)
                WidgetRenderer.updateAll(context)
                AlertNotifier.process(context, result.snapshot)
                notifyServerJournal(context)
            }
            TelemetryFetchResult.NeedsSignIn -> {
                preferences.setNeedsSignIn()
                WidgetRenderer.updateAll(context)
            }
            is TelemetryFetchResult.Failure -> {
                preferences.setError(result.message)
                AlertNotifier.process(context, preferences.snapshot())
                WidgetRenderer.updateAll(context)
            }
        }
        return result
    }
}
