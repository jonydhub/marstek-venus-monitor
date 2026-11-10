package com.doloh.marstekwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

abstract class BaseMarstekWidgetProvider : AppWidgetProvider() {
    protected abstract val variant: WidgetVariant

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshScheduler.ensurePeriodic(context)
        WidgetRefreshScheduler.refreshNow(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRefreshScheduler.ensurePeriodic(context)
        appWidgetIds.forEach { WidgetRenderer.update(context, it, variant) }
        WidgetRefreshScheduler.refreshNow(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        // API < 31 has no sized RemoteViews: re-pick the layout for the new cell size.
        WidgetRenderer.update(context, appWidgetId, variant)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val preferences = WidgetPreferences(context)
        appWidgetIds.forEach(preferences::removeWidget)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        // Keep the periodic check alive while power/battery notifications are wanted.
        if (!WidgetRenderer.hasWidgets(context) && !WidgetPreferences(context).anyAlertsEnabled()) {
            WidgetRefreshScheduler.cancel(context)
        }
        super.onDisabled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            WidgetRefreshScheduler.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_REFRESH = "com.doloh.marstekwidget.action.REFRESH"
    }
}

class MarstekWidgetProvider : BaseMarstekWidgetProvider() {
    override val variant = WidgetVariant.MEDIUM
}

class MarstekCompactWidgetProvider : BaseMarstekWidgetProvider() {
    override val variant = WidgetVariant.MINI
}

class MarstekStripWidgetProvider : BaseMarstekWidgetProvider() {
    override val variant = WidgetVariant.STRIP
}

class MarstekEnergyWidgetProvider : BaseMarstekWidgetProvider() {
    override val variant = WidgetVariant.LARGE
}
