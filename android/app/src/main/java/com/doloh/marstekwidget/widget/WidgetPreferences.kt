package com.doloh.marstekwidget.widget

import android.content.Context
import com.doloh.marstekwidget.domain.BatteryDirection
import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.AlertEvaluator
import com.doloh.marstekwidget.domain.AlertGroup
import com.doloh.marstekwidget.domain.AlertMemory
import com.doloh.marstekwidget.domain.AlertMemoryCodec
import com.doloh.marstekwidget.domain.AlertSettings
import org.json.JSONObject

data class WidgetDisplayOptions(
    val showSoc: Boolean = true,
    val showEta: Boolean = true,
    val showTemperature: Boolean = true,
    val showPower: Boolean = true,
    val showCapacity: Boolean = true,
    val showEnergy: Boolean = true,
    val showBatteryState: Boolean = true,
    val showGridPower: Boolean = true,
    val showBackupPower: Boolean = true,
    val showLastUpdated: Boolean = true,
)

enum class WidgetFetchState { NEVER, OK, STALE, NEEDS_SIGN_IN, ERROR }

class WidgetPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun options(widgetId: Int): WidgetDisplayOptions = WidgetDisplayOptions(
        showSoc = prefs.getBoolean(optionKey(widgetId, "soc"), true),
        showEta = prefs.getBoolean(optionKey(widgetId, "eta"), true),
        showTemperature = prefs.getBoolean(optionKey(widgetId, "temperature"), true),
        showPower = prefs.getBoolean(optionKey(widgetId, "power"), true),
        showCapacity = prefs.getBoolean(optionKey(widgetId, "capacity"), true),
        showEnergy = prefs.getBoolean(optionKey(widgetId, "energy"), true),
        showBatteryState = prefs.getBoolean(optionKey(widgetId, "battery_state"), true),
        showGridPower = prefs.getBoolean(optionKey(widgetId, "grid_power"), true),
        showBackupPower = prefs.getBoolean(optionKey(widgetId, "backup_power"), true),
        showLastUpdated = prefs.getBoolean(optionKey(widgetId, "last_updated"), true),
    )

    fun saveOptions(widgetId: Int, options: WidgetDisplayOptions) {
        prefs.edit()
            .putBoolean(optionKey(widgetId, "soc"), options.showSoc)
            .putBoolean(optionKey(widgetId, "eta"), options.showEta)
            .putBoolean(optionKey(widgetId, "temperature"), options.showTemperature)
            .putBoolean(optionKey(widgetId, "power"), options.showPower)
            .putBoolean(optionKey(widgetId, "capacity"), options.showCapacity)
            .putBoolean(optionKey(widgetId, "energy"), options.showEnergy)
            .putBoolean(optionKey(widgetId, "battery_state"), options.showBatteryState)
            .putBoolean(optionKey(widgetId, "grid_power"), options.showGridPower)
            .putBoolean(optionKey(widgetId, "backup_power"), options.showBackupPower)
            .putBoolean(optionKey(widgetId, "last_updated"), options.showLastUpdated)
            .apply()
    }

    fun removeWidget(widgetId: Int) {
        prefs.edit()
            .remove(optionKey(widgetId, "soc"))
            .remove(optionKey(widgetId, "eta"))
            .remove(optionKey(widgetId, "temperature"))
            .remove(optionKey(widgetId, "power"))
            .remove(optionKey(widgetId, "capacity"))
            .remove(optionKey(widgetId, "energy"))
            .remove(optionKey(widgetId, "battery_state"))
            .remove(optionKey(widgetId, "grid_power"))
            .remove(optionKey(widgetId, "backup_power"))
            .remove(optionKey(widgetId, "last_updated"))
            .apply()
    }

    fun saveSnapshot(snapshot: BatterySnapshot) {
        val value = JSONObject()
            .putNullable("soc", snapshot.socPercent)
            .putNullable("capacityWh", snapshot.batteryCapacityWh)
            .putNullable("ratedCapacityWh", snapshot.ratedCapacityWh)
            .putNullable("temperatureC", snapshot.temperatureCelsius)
            .putNullable("powerW", snapshot.batteryPowerWatts)
            .putNullable("solarPowerW", snapshot.solarPowerWatts)
            .putNullable("gridPowerW", snapshot.gridPowerWatts)
            .putNullable("offgridPowerW", snapshot.offgridPowerWatts)
            .putNullable("totalPvEnergyWh", snapshot.totalPvEnergyWh)
            .putNullable("totalGridInputEnergyWh", snapshot.totalGridInputEnergyWh)
            .putNullable("totalGridOutputEnergyWh", snapshot.totalGridOutputEnergyWh)
            .putNullable("totalLoadEnergyWh", snapshot.totalLoadEnergyWh)
            .putNullable("meterPhaseAPowerW", snapshot.meterPhaseAPowerWatts)
            .putNullable("meterPhaseBPowerW", snapshot.meterPhaseBPowerWatts)
            .putNullable("meterPhaseCPowerW", snapshot.meterPhaseCPowerWatts)
            .putNullable("meterTotalPowerW", snapshot.meterTotalPowerWatts)
            .putNullable("meterInputEnergyWh", snapshot.meterInputEnergyWh)
            .putNullable("meterOutputEnergyWh", snapshot.meterOutputEnergyWh)
            .put("direction", snapshot.direction.name)
            .putNullable("etaSeconds", snapshot.etaSeconds)
            .putNullable("collectedAt", snapshot.collectedAt)
            .putNullable("operatingMode", snapshot.operatingMode)
            .putNullable("ctState", snapshot.ctState)
            .putNullable("powerOutageJson", snapshot.powerOutageJson)
            .putNullable("insightsJson", snapshot.insightsJson)
        prefs.edit()
            .putString(KEY_SNAPSHOT, value.toString())
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .putString(KEY_STATE, WidgetFetchState.OK.name)
            .remove(KEY_ERROR)
            .apply()
    }

    fun snapshot(): BatterySnapshot? {
        val value = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val json = JSONObject(value)
            BatterySnapshot(
                socPercent = json.optNullableInt("soc"),
                batteryCapacityWh = json.optNullableDouble("capacityWh"),
                ratedCapacityWh = json.optNullableDouble("ratedCapacityWh"),
                temperatureCelsius = json.optNullableDouble("temperatureC"),
                batteryPowerWatts = json.optNullableDouble("powerW"),
                direction = runCatching {
                    BatteryDirection.valueOf(json.optString("direction", "UNKNOWN"))
                }.getOrDefault(BatteryDirection.UNKNOWN),
                etaSeconds = json.optNullableLong("etaSeconds"),
                collectedAt = json.optString("collectedAt").takeIf { it.isNotBlank() && it != "null" },
                solarPowerWatts = json.optNullableDouble("solarPowerW"),
                gridPowerWatts = json.optNullableDouble("gridPowerW"),
                offgridPowerWatts = json.optNullableDouble("offgridPowerW"),
                totalPvEnergyWh = json.optNullableDouble("totalPvEnergyWh"),
                totalGridInputEnergyWh = json.optNullableDouble("totalGridInputEnergyWh"),
                totalGridOutputEnergyWh = json.optNullableDouble("totalGridOutputEnergyWh"),
                totalLoadEnergyWh = json.optNullableDouble("totalLoadEnergyWh"),
                meterPhaseAPowerWatts = json.optNullableDouble("meterPhaseAPowerW"),
                meterPhaseBPowerWatts = json.optNullableDouble("meterPhaseBPowerW"),
                meterPhaseCPowerWatts = json.optNullableDouble("meterPhaseCPowerW"),
                meterTotalPowerWatts = json.optNullableDouble("meterTotalPowerW"),
                meterInputEnergyWh = json.optNullableDouble("meterInputEnergyWh"),
                meterOutputEnergyWh = json.optNullableDouble("meterOutputEnergyWh"),
                operatingMode = json.optString("operatingMode").takeIf { it.isNotBlank() && it != "null" },
                ctState = json.optNullableInt("ctState"),
                powerOutageJson = json.optString("powerOutageJson").takeIf { it.isNotBlank() && it != "null" },
                insightsJson = json.optString("insightsJson").takeIf { it.isNotBlank() && it != "null" },
            )
        } catch (_: Exception) {
            null
        }
    }

    fun state(): WidgetFetchState {
        val stored = runCatching {
        WidgetFetchState.valueOf(prefs.getString(KEY_STATE, WidgetFetchState.NEVER.name)!!)
        }.getOrDefault(WidgetFetchState.NEVER)
        return TelemetryFreshnessResolver.resolve(
            state = stored,
            collectedAt = snapshot()?.collectedAt,
            lastFetchedAtMillis = lastFetchedAtMillis(),
            nowMillis = System.currentTimeMillis(),
        )
    }

    fun lastFetchedAtMillis(): Long = prefs.getLong(KEY_FETCHED_AT, 0L)

    fun setNeedsSignIn() = setState(WidgetFetchState.NEEDS_SIGN_IN)
    fun setError(message: String) {
        prefs.edit().putString(KEY_ERROR, message).putString(KEY_STATE, WidgetFetchState.ERROR.name).apply()
    }

    fun errorMessage(): String? = prefs.getString(KEY_ERROR, null)

    fun alertSettings(): AlertSettings = AlertSettings(
        enabledGroups = AlertGroup.entries.filter { prefs.getBoolean(groupKey(it), legacyGroupDefault(it)) }.toSet(),
        highLoadThresholdWatts = prefs.getInt(KEY_HIGH_LOAD_W, AlertEvaluator.DEFAULT_HIGH_LOAD_W),
    )

    fun setAlertGroupEnabled(group: AlertGroup, enabled: Boolean) {
        prefs.edit().putBoolean(groupKey(group), enabled).apply()
    }

    fun setHighLoadThreshold(watts: Int) {
        prefs.edit().putInt(KEY_HIGH_LOAD_W, watts).apply()
    }

    fun anyAlertsEnabled(): Boolean = alertSettings().enabledGroups.isNotEmpty()

    fun alertMemory(): AlertMemory? = AlertMemoryCodec.decode(prefs.getString(KEY_ALERT_MEMORY, null))

    fun saveAlertMemory(memory: AlertMemory) {
        prefs.edit()
            .putString(KEY_ALERT_MEMORY, AlertMemoryCodec.encode(memory))
            .remove(LEGACY_MEMORY_GRID)
            .remove(LEGACY_MEMORY_RESERVE)
            .remove(LEGACY_MEMORY_LOW_SOC)
            .apply()
    }

    /** Pre-alert-engine toggles map onto the new groups so an upgrade keeps the user's choice. */
    private fun legacyGroupDefault(group: AlertGroup): Boolean = when (group) {
        AlertGroup.LIGHT_SCHEDULE -> prefs.getBoolean(LEGACY_LIGHT_LOST, true) || prefs.getBoolean(LEGACY_LIGHT_RETURNED, true)
        AlertGroup.RESERVE -> prefs.getBoolean(LEGACY_RESERVE, true) || prefs.getBoolean(LEGACY_LOW_SOC, true)
        else -> true
    }

    private fun groupKey(group: AlertGroup) = "notify.group.${group.name.lowercase()}"

    private fun setState(state: WidgetFetchState) {
        prefs.edit().putString(KEY_STATE, state.name).apply()
    }

    private fun optionKey(widgetId: Int, field: String) = "widget.$widgetId.$field"

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

    private fun JSONObject.optNullableInt(key: String): Int? =
        optNullableDouble(key)?.toInt()

    private fun JSONObject.optNullableLong(key: String): Long? =
        optNullableDouble(key)?.toLong()

    private companion object {
        const val PREFS_NAME = "marstek_widget_preferences"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_FETCHED_AT = "fetched_at"
        const val KEY_STATE = "fetch_state"
        const val KEY_ERROR = "error"
        const val KEY_HIGH_LOAD_W = "notify.high_load_w"
        const val KEY_ALERT_MEMORY = "alerts.memory"
        const val LEGACY_LIGHT_LOST = "notify.light_lost"
        const val LEGACY_LIGHT_RETURNED = "notify.light_returned"
        const val LEGACY_RESERVE = "notify.reserve_short"
        const val LEGACY_LOW_SOC = "notify.low_soc"
        const val LEGACY_MEMORY_GRID = "notify.memory.grid"
        const val LEGACY_MEMORY_RESERVE = "notify.memory.reserve_sent"
        const val LEGACY_MEMORY_LOW_SOC = "notify.memory.low_soc_sent"
    }
}
