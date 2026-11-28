package com.doloh.marstekwidget.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.doloh.marstekwidget.data.ExtraScheduleRepository
import com.doloh.marstekwidget.data.TelemetryFetchResult
import com.doloh.marstekwidget.data.TelemetryRepository
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.update.UpdateChecker

/** Background refresh (~15 min). Direct mode only reaches the battery on the home network; otherwise data goes stale. */
class MarstekRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // «Вийшла нова версія»: cheap — skipped unless 12 h passed (or the switch is off); never fails the refresh.
        runCatching { UpdateChecker.check(applicationContext) }
        val preferences = WidgetPreferences(applicationContext)
        val wantsNotifications = preferences.anyAlertsEnabled()
        if (!WidgetRenderer.hasWidgets(applicationContext) && !wantsNotifications) return Result.success()
        if (!SettingsStore(applicationContext).load().configured) return Result.success()
        // «Інші графіки» (display only, ≤ once per 10 min each) before the redraw below, so the schedules widget is fresh
        if (ScheduleListWidgetRenderer.widgetIds(applicationContext).isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ExtraScheduleRepository(applicationContext).refreshIfDue() } }
        }
        return when (TelemetryRepository.refreshAndStore(applicationContext)) {
            is TelemetryFetchResult.Failure -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
