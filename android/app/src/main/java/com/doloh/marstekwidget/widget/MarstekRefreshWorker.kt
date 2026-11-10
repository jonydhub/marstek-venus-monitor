package com.doloh.marstekwidget.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.doloh.marstekwidget.data.TelemetryFetchResult
import com.doloh.marstekwidget.data.TelemetryRepository
import com.doloh.marstekwidget.settings.SettingsStore

/** Background refresh (~15 min). Direct mode only reaches the battery on the home network; otherwise data goes stale. */
class MarstekRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = WidgetPreferences(applicationContext)
        val wantsNotifications = preferences.anyAlertsEnabled()
        if (!WidgetRenderer.hasWidgets(applicationContext) && !wantsNotifications) return Result.success()
        if (!SettingsStore(applicationContext).load().configured) return Result.success()
        return when (TelemetryRepository.refreshAndStore(applicationContext)) {
            is TelemetryFetchResult.Failure -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
