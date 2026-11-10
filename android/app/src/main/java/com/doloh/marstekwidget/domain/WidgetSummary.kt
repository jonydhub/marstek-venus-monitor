package com.doloh.marstekwidget.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

enum class SummaryMode { OUTAGE, CHARGING, DISCHARGING, IDLE, UNKNOWN }
enum class SummaryTone { NEUTRAL, GOOD, WARNING, CRITICAL }
enum class SummaryLineKind { BACKUP_OUTPUT, RETURN_TIME, RESERVE, ETA, POWER, LIGHT, TEMPERATURE }

data class SummaryLine(val kind: SummaryLineKind, val text: String, val tone: SummaryTone = SummaryTone.NEUTRAL)

/**
 * State-aware context texts shared by every widget size and the main screen.
 * `primary`/`secondary` are the two middle lines of the flat strip, `trailing*` its right column;
 * `lines` is the ordered detail list for larger layouts.
 */
data class WidgetSummary(
    val mode: SummaryMode,
    val lightState: GridState,
    val primary: String,
    val primaryTone: SummaryTone,
    val secondary: String?,
    val trailingPrimary: String,
    val trailingSecondary: String?,
    val lines: List<SummaryLine>,
    /** Battery-only state (no light info), for places that show the light state separately. */
    val batteryState: String,
)

object WidgetSummaryResolver {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val uk: Locale = Locale.forLanguageTag("uk-UA")
    private const val SEP = " · "

    fun resolve(snapshot: BatterySnapshot, now: ZonedDateTime = ZonedDateTime.now(kyiv)): WidgetSummary {
        val info = snapshot.powerOutage
        val light = info?.gridState ?: GridState.UNKNOWN
        val temperature = (snapshot.temperatureCelsius ?: snapshot.insights?.temperatureCelsius)?.let(::formatTemperature)
        val temperatureLine = temperature?.let { SummaryLine(SummaryLineKind.TEMPERATURE, "Температура $it") }
        val reserve = info?.let(PowerOutageFormatter::reserve)
        val reserveTone = when (info?.coversOutage) {
            true -> SummaryTone.GOOD
            false -> SummaryTone.CRITICAL
            null -> SummaryTone.NEUTRAL
        }

        if (info != null && light == GridState.OFF) {
            val backup = (snapshot.insights?.backupOutputWatts ?: snapshot.offgridPowerWatts)?.let(::formatWatts)
            val returnAt = info.currentEnd?.let { PowerOutageFormatter.shortDetail(info, now) }
            // One runtime number during an outage: autonomy runtime if known, else the battery ETA to 0%.
            val runtime = info.runtimeSeconds ?: snapshot.etaSeconds?.takeIf { snapshot.direction == BatteryDirection.DISCHARGING }
            val remaining = info.currentEnd?.let { end ->
                runCatching { java.time.Duration.between(now, java.time.OffsetDateTime.parse(end)).seconds }.getOrNull()
            }?.takeIf { it > 0 }
            val covers = info.coversOutage ?: if (runtime != null && remaining != null) runtime >= remaining else null
            val verdict = when (covers) { true -> " · вистачить"; false -> " · не вистачить"; null -> "" }
            val runtimeValue = runtime?.let(EtaTextFormatter::formatSeconds)
            val reserveText = runtimeValue?.let { "Запас ≈ $it$verdict" } ?: "Запас: оцінюю розряд…"
            val outageTone = when (covers) {
                true -> SummaryTone.GOOD
                false -> SummaryTone.CRITICAL
                null -> SummaryTone.NEUTRAL
            }
            return WidgetSummary(
                mode = SummaryMode.OUTAGE,
                lightState = light,
                primary = listOfNotNull("Світла нема", backup?.let { "вихід $it" }).joinToString(SEP),
                primaryTone = SummaryTone.WARNING,
                // The runtime number sits in the trailing column ("0% · X · °C"); the middle line carries only the verdict.
                secondary = when {
                    runtimeValue == null -> reserveText
                    covers == true -> "Запасу вистачить"
                    covers == false -> "Запасу не вистачить"
                    else -> "Запас до 0% · оцінка"
                },
                trailingPrimary = returnAt ?: "до —",
                trailingSecondary = listOfNotNull("0% · ${runtimeValue ?: "—"}", temperature).joinToString(SEP),
                lines = listOfNotNull(
                    SummaryLine(
                        SummaryLineKind.RETURN_TIME,
                        if (info.currentEnd != null) PowerOutageFormatter.detail(info, now) else "Час повернення невідомий",
                    ),
                    SummaryLine(SummaryLineKind.RESERVE, reserveText, outageTone),
                    temperatureLine,
                ),
                batteryState = listOfNotNull(directionWord(snapshot.direction), backup?.let { "резервний вихід $it" }).joinToString(SEP),
            )
        }

        val lightShort = info?.let { listOfNotNull(PowerOutageFormatter.headline(it), PowerOutageFormatter.shortDetail(it, now)).joinToString(SEP) }
        val lightLine = info?.let {
            SummaryLine(SummaryLineKind.LIGHT, PowerOutageFormatter.headline(it) + SEP + PowerOutageFormatter.detail(it, now),
                if (it.gridState == GridState.ON) SummaryTone.GOOD else SummaryTone.NEUTRAL)
        }
        val reserveLine = reserve?.let { SummaryLine(SummaryLineKind.RESERVE, it, reserveTone) }

        when (snapshot.direction) {
            BatteryDirection.CHARGING, BatteryDirection.DISCHARGING -> {
                val charging = snapshot.direction == BatteryDirection.CHARGING
                val power = snapshot.batteryPowerWatts?.takeIf(Double::isFinite)?.let(::formatKw)
                val eta = EtaTextFormatter.formatSeconds(snapshot.etaSeconds)
                val target = if (charging) "100%" else "0%"
                return WidgetSummary(
                    mode = if (charging) SummaryMode.CHARGING else SummaryMode.DISCHARGING,
                    lightState = light,
                    primary = power?.let { "${if (charging) "Заряд" else "Розряд"} ≈ $it" } ?: if (charging) "Заряджається" else "Розряджається",
                    primaryTone = if (charging) SummaryTone.GOOD else SummaryTone.NEUTRAL,
                    secondary = lightShort,
                    trailingPrimary = eta,
                    trailingSecondary = listOfNotNull("до $target", temperature).joinToString(SEP),
                    // Power is already the headline, so the detail list starts with the ETA.
                    lines = listOfNotNull(
                        SummaryLine(SummaryLineKind.ETA, if (snapshot.etaSeconds != null) "До $target ≈ $eta · оцінка" else "До $target: —"),
                        lightLine,
                        reserveLine,
                        temperatureLine,
                    ),
                    batteryState = listOfNotNull(if (charging) "Заряджається" else "Розряджається", power?.let { "≈ $it" }).joinToString(SEP),
                )
            }
            BatteryDirection.IDLE, BatteryDirection.UNKNOWN -> {
                val idle = snapshot.direction == BatteryDirection.IDLE
                val stateWord = if (idle) "очікування" else "стан невідомий"
                val runtime = info?.runtimeSeconds?.let { "≈ ${EtaTextFormatter.formatSeconds(it)}" }
                return WidgetSummary(
                    mode = if (idle) SummaryMode.IDLE else SummaryMode.UNKNOWN,
                    lightState = light,
                    primary = info?.let { PowerOutageFormatter.headline(it) + SEP + stateWord } ?: stateWord.replaceFirstChar { it.titlecase(uk) },
                    primaryTone = SummaryTone.NEUTRAL,
                    secondary = info?.let { PowerOutageFormatter.detail(it, now) },
                    trailingPrimary = runtime ?: temperature ?: "—",
                    trailingSecondary = if (runtime != null) listOfNotNull("запас", temperature).joinToString(SEP) else null,
                    lines = listOfNotNull(lightLine, reserveLine, temperatureLine),
                    batteryState = directionWord(snapshot.direction),
                )
            }
        }
    }

    private fun directionWord(direction: BatteryDirection): String = when (direction) {
        BatteryDirection.CHARGING -> "Заряджається"
        BatteryDirection.DISCHARGING -> "Розряджається"
        BatteryDirection.IDLE -> "Очікування"
        BatteryDirection.UNKNOWN -> "Стан невідомий"
    }

    fun formatWatts(watts: Double): String = String.format(uk, "%.0f W", abs(watts))
    fun formatKw(watts: Double): String = String.format(uk, "%.2f кВт", abs(watts) / 1000.0)
    fun formatTemperature(celsius: Double): String = String.format(uk, "%.1f °C", celsius)
}
