package com.doloh.marstekwidget.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.domain.Alert
import com.doloh.marstekwidget.domain.AlertEvaluation
import com.doloh.marstekwidget.domain.AlertEvaluator
import com.doloh.marstekwidget.domain.AlertSeverity
import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.JournalCategory
import com.doloh.marstekwidget.domain.JournalEvent
import com.doloh.marstekwidget.ui.JournalActivity
import com.doloh.marstekwidget.ui.MainActivity
import com.doloh.marstekwidget.widget.WidgetPreferences
import com.doloh.marstekwidget.widget.WidgetRefreshScheduler

/** Runs the pure [AlertEvaluator] on each refresh, persists its memory and posts the resulting notifications. */
object AlertNotifier {
    const val CHANNEL_INFO = "alerts_info"
    const val CHANNEL_WARN = "alerts_warn"
    const val CHANNEL_CRITICAL = "alerts_critical"
    private const val LEGACY_CHANNEL = "power_battery"
    private const val ANOMALY_NOTIFICATION_BASE = 600

    @Synchronized
    fun process(context: Context, snapshot: BatterySnapshot?, nowMillis: Long = System.currentTimeMillis()): AlertEvaluation {
        val preferences = WidgetPreferences(context)
        val evaluation = AlertEvaluator.evaluate(
            preferences.alertMemory(), snapshot, nowMillis, preferences.alertSettings(), preferences.lastFetchedAtMillis(),
        )
        preferences.saveAlertMemory(evaluation.memory)
        AlertEvaluator.outageSoonWakeupDelayMillis(snapshot?.powerOutage?.nextStart, nowMillis)?.let { delay ->
            WidgetRefreshScheduler.scheduleOutageWakeup(context, delay)
        }
        if (evaluation.notifications.isNotEmpty() && canPost(context)) {
            ensureChannels(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            evaluation.notifications.forEach { alert -> manager?.notify(notificationId(alert), build(context, alert)) }
        }
        return evaluation
    }

    /** Journal anomalies → one notification each (toggle «Сповіщати про аномалії»). Light/connection events stay in the journal. */
    fun notifyJournal(context: Context, events: List<JournalEvent>) {
        // Overload trip / leaving UPS come from the alert engine in both modes; not posted twice.
        val anomalies = events.filter { it.category == JournalCategory.ANOMALY && it.kind !in com.doloh.marstekwidget.domain.JournalDetector.ALERT_ENGINE_KINDS }
        if (anomalies.isEmpty() || !WidgetPreferences(context).anomalyNotificationsEnabled() || !canPost(context)) return
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        anomalies.forEach { event ->
            val open = PendingIntent.getActivity(context, 950, Intent(context, JournalActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL_WARN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Аномалія")
                .setContentText(event.text)
                .setStyle(Notification.BigTextStyle().bigText(event.text))
                .setSubText("Журнал подій")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(ANOMALY_NOTIFICATION_BASE + (event.kind.hashCode() and 0xff), notification)
        }
    }

    /** Conditions true right now (for the main-screen card and widgets); never posts anything. */
    fun activeAlerts(context: Context, nowMillis: Long = System.currentTimeMillis()): List<Alert> {
        val preferences = WidgetPreferences(context)
        return AlertEvaluator.evaluate(
            preferences.alertMemory(), preferences.snapshot(), nowMillis, preferences.alertSettings(), preferences.lastFetchedAtMillis(),
        ).active
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
        listOf(
            Triple(CHANNEL_CRITICAL, R.string.notify_channel_critical, NotificationManager.IMPORTANCE_HIGH),
            Triple(CHANNEL_WARN, R.string.notify_channel_warn, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CHANNEL_INFO, R.string.notify_channel_info, NotificationManager.IMPORTANCE_LOW),
        ).forEach { (id, name, importance) ->
            manager.createNotificationChannel(NotificationChannel(id, context.getString(name), importance).apply {
                description = context.getString(R.string.notify_channel_description)
            })
        }
    }

    private fun channelFor(severity: AlertSeverity) = when (severity) {
        AlertSeverity.CRITICAL -> CHANNEL_CRITICAL
        AlertSeverity.WARN -> CHANNEL_WARN
        AlertSeverity.INFO -> CHANNEL_INFO
    }

    private fun notificationId(alert: Alert) = 200 + alert.id.ordinal

    private fun build(context: Context, alert: Alert): Notification {
        val open = PendingIntent.getActivity(
            context, 900 + alert.id.ordinal, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, channelFor(alert.severity))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setSubText(alert.group.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}
