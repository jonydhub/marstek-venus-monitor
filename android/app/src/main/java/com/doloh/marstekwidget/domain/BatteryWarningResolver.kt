package com.doloh.marstekwidget.domain

import kotlin.math.abs

enum class BatteryWarning {
    CRITICAL_TEMPERATURE,
    HIGH_TEMPERATURE,
    LOW_SOC,
    HIGH_POWER,
}

object BatteryWarningResolver {
    fun resolve(snapshot: BatterySnapshot): BatteryWarning? = when {
        snapshot.temperatureCelsius?.let { it >= 50.0 } == true -> BatteryWarning.CRITICAL_TEMPERATURE
        snapshot.temperatureCelsius?.let { it >= 45.0 } == true -> BatteryWarning.HIGH_TEMPERATURE
        snapshot.socPercent?.let { it < 20 } == true -> BatteryWarning.LOW_SOC
        snapshot.batteryPowerWatts?.let { abs(it) > 500.0 } == true -> BatteryWarning.HIGH_POWER
        else -> null
    }
}
