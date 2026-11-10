package com.doloh.marstekwidget.domain

import java.util.Locale

enum class BatteryOperationState {
    CHARGING,
    DISCHARGING,
    WAITING,
    UNKNOWN,
}

object BatteryOperationStateResolver {
    fun resolve(direction: BatteryDirection): BatteryOperationState = when (direction) {
        BatteryDirection.CHARGING -> BatteryOperationState.CHARGING
        BatteryDirection.DISCHARGING -> BatteryOperationState.DISCHARGING
        BatteryDirection.IDLE -> BatteryOperationState.WAITING
        BatteryDirection.UNKNOWN -> BatteryOperationState.UNKNOWN
    }
}

object OperatingModeFormatter {
    fun format(mode: String?): String {
        val raw = mode?.trim().orEmpty()
        if (raw.isEmpty()) return "—"
        return when (raw.uppercase(Locale.ROOT)) {
            "MANUAL" -> "Ручний"
            "AUTO", "AUTOMATIC" -> "Автоматичний"
            "UPS" -> "UPS"
            "SELF_USE", "SELF-USE" -> "Власне споживання"
            "AI" -> "AI"
            else -> raw
        }
    }
}
