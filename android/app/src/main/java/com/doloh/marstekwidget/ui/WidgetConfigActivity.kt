package com.doloh.marstekwidget.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.widget.WidgetDisplayOptions
import com.doloh.marstekwidget.widget.WidgetMetric
import com.doloh.marstekwidget.widget.WidgetMetricPlan
import com.doloh.marstekwidget.widget.WidgetPreferences
import com.doloh.marstekwidget.widget.WidgetRefreshScheduler
import com.doloh.marstekwidget.widget.WidgetRenderer
import com.doloh.marstekwidget.widget.WidgetVariant

class WidgetConfigActivity : Activity() {
    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val previous = WidgetPreferences(this).options(widgetId)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(Color.rgb(7, 21, 34))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.config_title)
            textSize = 22f
            setTextColor(Color.rgb(243, 247, 250))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.config_explanation)
            textSize = 14f
            setTextColor(Color.rgb(160, 176, 187))
        }, spaced())

        val soc = checkbox(R.string.widget_show_soc, previous.showSoc)
        val eta = checkbox(R.string.widget_show_eta, previous.showEta)
        val temperature = checkbox(R.string.widget_show_temperature, previous.showTemperature)
        val power = checkbox(R.string.widget_show_power, previous.showPower)
        val capacity = checkbox(R.string.widget_show_capacity, previous.showCapacity)
        val energy = checkbox(R.string.widget_show_energy, previous.showEnergy)
        val batteryState = checkbox(R.string.widget_show_battery_state, previous.showBatteryState)
        val gridPower = checkbox(R.string.widget_show_grid_power, previous.showGridPower)
        val backupPower = checkbox(R.string.widget_show_backup_power, previous.showBackupPower)
        val lastUpdated = checkbox(R.string.widget_show_last_updated, previous.showLastUpdated)
        val providerName = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        val variant = when {
            providerName.endsWith("MarstekCompactWidgetProvider") -> WidgetVariant.MINI
            providerName.endsWith("MarstekStripWidgetProvider") -> WidgetVariant.STRIP
            providerName.endsWith("MarstekEnergyWidgetProvider") -> WidgetVariant.LARGE
            else -> WidgetVariant.MEDIUM
        }
        val availableMetrics = WidgetMetricPlan.forVariant(variant)
        val options = listOf(
            WidgetMetric.SOC to soc,
            WidgetMetric.STORED_ENERGY to capacity,
            WidgetMetric.BATTERY_STATE to batteryState,
            WidgetMetric.ESTIMATED_BATTERY_POWER to power,
            WidgetMetric.ETA to eta,
            WidgetMetric.TEMPERATURE to temperature,
            WidgetMetric.GRID_POWER to gridPower,
            WidgetMetric.BACKUP_POWER to backupPower,
            WidgetMetric.ACCUMULATED_COUNTERS to energy,
            WidgetMetric.FRESHNESS to lastUpdated,
        )
        options.forEach { (metric, checkbox) ->
            if (metric in availableMetrics) root.addView(checkbox, spaced())
            else checkbox.visibility = View.GONE
        }

        val signIn = Button(this).apply {
            text = getString(R.string.config_sign_in)
            setOnClickListener { startActivity(Intent(this@WidgetConfigActivity, AccessLoginActivity::class.java)) }
        }
        val settings = com.doloh.marstekwidget.settings.SettingsStore(this).load()
        if (settings.mode == com.doloh.marstekwidget.settings.ConnectionMode.SERVER &&
            settings.serverAuth == com.doloh.marstekwidget.settings.ServerAuth.CF_ACCESS
        ) root.addView(signIn, spaced())

        val save = Button(this).apply {
            text = getString(R.string.config_done)
            setOnClickListener {
                WidgetPreferences(this@WidgetConfigActivity).saveOptions(
                    widgetId,
                    WidgetDisplayOptions(
                        showSoc = soc.isChecked,
                        showEta = eta.isChecked,
                        showTemperature = temperature.isChecked,
                        showPower = power.isChecked,
                        showCapacity = capacity.isChecked,
                        showEnergy = energy.isChecked,
                        showBatteryState = batteryState.isChecked,
                        showGridPower = gridPower.isChecked,
                        showBackupPower = backupPower.isChecked,
                        showLastUpdated = lastUpdated.isChecked,
                    ),
                )
                val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                setResult(RESULT_OK, result)
                WidgetRenderer.update(this@WidgetConfigActivity, widgetId)
                WidgetRefreshScheduler.ensurePeriodic(this@WidgetConfigActivity)
                WidgetRefreshScheduler.refreshNow(this@WidgetConfigActivity)
                finish()
            }
        }
        root.addView(save, spaced())
        SystemBarsInsetHelper.apply(root, 20, 18, 20, 18)
        setContentView(root)
    }

    private fun checkbox(label: Int, checked: Boolean) = CheckBox(this).apply {
        text = getString(label)
        isChecked = checked
        textSize = 15f
        setTextColor(Color.rgb(224, 234, 239))
        buttonTintList = ColorStateList.valueOf(Color.rgb(54, 198, 213))
    }

    private fun spaced() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
