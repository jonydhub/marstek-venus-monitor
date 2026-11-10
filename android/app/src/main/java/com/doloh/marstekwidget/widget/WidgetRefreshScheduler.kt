package com.doloh.marstekwidget.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WidgetRefreshScheduler {
    private const val PERIODIC_WORK = "marstek-widget-periodic-refresh"
    private const val MANUAL_WORK = "marstek-widget-manual-refresh"
    private const val OUTAGE_WAKEUP_WORK = "marstek-outage-soon-check"

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<MarstekRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MarstekRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** One-time extra check ~60 min before a scheduled outage so «outage soon» is not up to 15 min late. */
    fun scheduleOutageWakeup(context: Context, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<MarstekRefreshWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            OUTAGE_WAKEUP_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(MANUAL_WORK)
    }
}
