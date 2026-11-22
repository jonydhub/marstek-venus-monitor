package com.doloh.marstekwidget.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.doloh.marstekwidget.BuildConfig
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.data.AndroidHttpFetcher
import com.doloh.marstekwidget.notify.AlertNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SharedPreferences `marstek_update`: the switch, throttle times, last known release, ✕ and notified versions. */
class UpdatePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }
    fun lastSuccessMs(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)
    fun lastAttemptMs(): Long = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
    fun dismissedVersion(): String? = prefs.getString(KEY_DISMISSED, null)
    fun dismiss(version: String) { prefs.edit().putString(KEY_DISMISSED, version).apply() }
    fun notifiedVersion(): String? = prefs.getString(KEY_NOTIFIED, null)
    fun markNotified(version: String) { prefs.edit().putString(KEY_NOTIFIED, version).apply() }

    fun latest(): ReleaseInfo? {
        val version = AppUpdate.normalize(prefs.getString(KEY_VERSION, null)) ?: return null
        val html = prefs.getString(KEY_HTML, null) ?: return null
        return ReleaseInfo(version, html, prefs.getString(KEY_APK, null), prefs.getString(KEY_NOTE, null))
    }

    fun recordAttempt(nowMs: Long) { prefs.edit().putLong(KEY_LAST_ATTEMPT, nowMs).apply() }

    /** A good answer; [release] null (no published release / draft only) keeps the last known one. */
    fun recordSuccess(nowMs: Long, release: ReleaseInfo?) {
        val e = prefs.edit().putLong(KEY_LAST_CHECK, nowMs).putLong(KEY_LAST_ATTEMPT, nowMs)
        if (release != null) e.putString(KEY_VERSION, release.version).putString(KEY_HTML, release.htmlUrl)
            .putString(KEY_APK, release.apkUrl).putString(KEY_NOTE, release.note)
        e.apply()
    }

    /** Debug builds only: `marstek_update/debug_url` points the check at a mock server. */
    fun releaseUrl(): String = (if (BuildConfig.DEBUG) prefs.getString(KEY_DEBUG_URL, null)?.takeIf { it.isNotBlank() } else null)
        ?: UpdateChecker.LATEST_RELEASE_URL

    private companion object {
        const val PREFS = "marstek_update"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_VERSION = "latest.version"
        const val KEY_HTML = "latest.html_url"
        const val KEY_APK = "latest.apk_url"
        const val KEY_NOTE = "latest.note"
        const val KEY_DISMISSED = "dismissed_version"
        const val KEY_NOTIFIED = "notified_version"
        const val KEY_DEBUG_URL = "debug_url"
    }
}

enum class UpdateCheckOutcome { CHECKED, SKIPPED, FAILED, DISABLED }

/**
 * Looks at GitHub `releases/latest` (public, no token, nothing about the user or the station is sent) at most once
 * per 12 h — from MainActivity.onResume and the periodic refresh worker — and posts one notification per new version.
 */
object UpdateChecker {
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/jonydhub/marstek-venus-monitor/releases/latest"
    const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID = 700
    private const val TIMEOUT_MS = 10_000
    private val mutex = Mutex()

    /** Set by MainActivity onResume/onPause: while the main screen is visible the banner is enough. */
    @Volatile var mainScreenVisible: Boolean = false

    val installedVersion: String get() = BuildConfig.VERSION_NAME

    fun bannerRelease(context: Context): ReleaseInfo? {
        val p = UpdatePreferences(context)
        val latest = p.latest()
        return latest.takeIf { AppUpdate.bannerVisible(p.enabled(), it, installedVersion, p.dismissedVersion()) }
    }

    /**
     * Never throws; network work runs on IO. [force] = «Перевірити зараз» (ignores the 12 h limit and the switch);
     * [foreground] = the user is looking at the result on screen, so the notification is only marked, not posted.
     */
    suspend fun check(context: Context, force: Boolean = false, foreground: Boolean = false, nowMs: Long = System.currentTimeMillis()): UpdateCheckOutcome =
        mutex.withLock {
            val app = context.applicationContext
            val p = UpdatePreferences(app)
            if (!force && !p.enabled()) return@withLock UpdateCheckOutcome.DISABLED
            if (!AppUpdate.shouldCheck(nowMs, p.lastSuccessMs(), p.lastAttemptMs(), force)) return@withLock UpdateCheckOutcome.SKIPPED
            p.recordAttempt(nowMs)
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    AndroidHttpFetcher(TIMEOUT_MS).get(p.releaseUrl(), mapOf(
                        "Accept" to "application/vnd.github+json",
                        "User-Agent" to "MarstekVenusMonitor/$installedVersion",
                    ))
                }.getOrNull()
            }
            // 404 = no published release yet: a valid answer, the last known result stays.
            val ok = response != null && (response.code == 200 && response.body != null || response.code == 404)
            if (!ok) return@withLock UpdateCheckOutcome.FAILED
            val release = response?.body?.let(AppUpdate::parseRelease)
            p.recordSuccess(nowMs, release)
            notifyIfNeeded(app, foreground)
            UpdateCheckOutcome.CHECKED
        }

    /** One notification per newer version; marked as notified even when not posted (main screen open, no permission). */
    fun notifyIfNeeded(context: Context, foreground: Boolean = false) {
        val p = UpdatePreferences(context)
        val latest = p.latest()
        if (latest == null || !p.enabled() || !AppUpdate.isNewer(latest.version, installedVersion)) {
            cancel(context)
            return
        }
        if (!AppUpdate.shouldNotify(p.enabled(), latest, installedVersion, p.notifiedVersion())) return
        p.markNotified(latest.version)
        if (foreground || mainScreenVisible || !AlertNotifier.canPost(context)) return
        runCatching { post(context, latest) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Оновлення застосунку", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Повідомлення, коли на GitHub виходить нова версія застосунку"
        })
    }

    fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun post(context: Context, release: ReleaseInfo) {
        ensureChannel(context)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val download = PendingIntent.getActivity(context, 970, viewIntent(release.downloadUrl), flags)
        val whatsNew = PendingIntent.getActivity(context, 971, viewIntent(release.htmlUrl), flags)
        val text = release.note ?: "Натисніть, щоб завантажити"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Вийшла версія ${release.version}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(download)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Що нового", whatsNew).build())
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
