package com.doloh.marstekwidget.domain

import java.time.ZoneId
import java.time.ZonedDateTime

enum class LightKind { ON, OFF, NO_LINK, UNKNOWN }

data class LightStatus(val kind: LightKind, val headline: String, val hint: String?) {
    /** The grid state to act on: a lost link is always UNKNOWN, never OFF. */
    val gridState: GridState get() = when (kind) {
        LightKind.ON -> GridState.ON
        LightKind.OFF -> GridState.OFF
        else -> GridState.UNKNOWN
    }
}

/**
 * Light on/off comes ONLY from the battery's backup-output power (power_outage.grid_state).
 * When the battery (or the server) cannot be read, the state is «Немає зв'язку з батареєю» — never «Світла нема»,
 * even if the cached snapshot said off. If the schedule says an outage is on right now, that is only a hint.
 */
object LightStatusResolver {
    const val NO_LINK = "Немає зв'язку з батареєю"
    const val SCHEDULED_HINT = "за графіком зараз відключення"
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")

    fun resolve(snapshot: BatterySnapshot?, fetchFailed: Boolean, now: ZonedDateTime = ZonedDateTime.now(kyiv)): LightStatus {
        val info = snapshot?.powerOutage
        if (fetchFailed || snapshot?.batteryLinkLost == true) {
            val hint = info?.takeIf { PowerOutageFormatter.scheduledNow(it, now) }?.let { SCHEDULED_HINT }
            return LightStatus(LightKind.NO_LINK, NO_LINK, hint)
        }
        return when (info?.gridState) {
            GridState.ON -> LightStatus(LightKind.ON, "Світло є", null)
            GridState.OFF -> LightStatus(LightKind.OFF, "Світла нема", null)
            else -> LightStatus(LightKind.UNKNOWN, "Світло: —", null)
        }
    }
}
