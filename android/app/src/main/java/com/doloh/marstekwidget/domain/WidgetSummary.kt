package com.doloh.marstekwidget.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** Main-screen ETA line: time to 100%/0% while moving, «Заряджено» when idle at ≥99%, else null. */
    val etaText: String? = null,
)

object WidgetSummaryResolver {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val uk: Locale = Locale.forLanguageTag("uk-UA")
    private const val SEP = " · "
    private const val FULL_SOC = 99

    /** «11%» when the reserve is computed above a discharge floor, else «0%». */
    fun floorTarget(info: PowerOutageInfo?): String = info?.floorPct?.let { "${it.roundToInt()}%" } ?: "0%"

    /** Time to the discharge floor at the current trend power (falls back to the device ETA to 0 %). */
    fun dischargeEtaSeconds(snapshot: BatterySnapshot): Long? {
        val usable = snapshot.powerOutage?.usableWh
        val power = snapshot.batteryPowerWatts?.takeIf { it.isFinite() && abs(it) >= 1.0 }
        if (usable != null && power != null && snapshot.powerOutage?.floorPct != null) return (usable / abs(power) * 3600.0).toLong()
        return snapshot.etaSeconds
    }

    fun resolve(snapshot: BatterySnapshot, now: ZonedDateTime = ZonedDateTime.now(kyiv), linkLost: Boolean = false): WidgetSummary {
        val info = snapshot.powerOutage
        val temperature = (snapshot.temperatureCelsius ?: snapshot.insights?.temperatureCelsius)?.let(::formatTemperature)
        if (linkLost || snapshot.batteryLinkLost) {
            // Light comes only from the backup output: without an answer from the battery it is unknown, not «нема».
            val status = LightStatusResolver.resolve(snapshot, fetchFailed = true, now = now)
            val schedule = info?.let { PowerOutageFormatter.detail(it, now, GridState.UNKNOWN) }
            return WidgetSummary(
                mode = SummaryMode.UNKNOWN,
                lightState = GridState.UNKNOWN,
                primary = status.headline,
                primaryTone = SummaryTone.WARNING,
                secondary = status.hint?.replaceFirstChar { it.titlecase(uk) } ?: schedule,
                trailingPrimary = "—",
                trailingSecondary = null,
                lines = listOfNotNull(
                    status.hint?.let { SummaryLine(SummaryLineKind.LIGHT, it.replaceFirstChar { c -> c.titlecase(uk) }, SummaryTone.WARNING) },
                    schedule?.let { SummaryLine(SummaryLineKind.RETURN_TIME, it) },
                ),
                batteryState = status.headline,
            )
        }
        val light = info?.gridState ?: GridState.UNKNOWN
        val temperatureLine = temperature?.let { SummaryLine(SummaryLineKind.TEMPERATURE, "Температура $it") }
        val reserve = info?.let(PowerOutageFormatter::reserve)
        val reserveTone = when (info?.coversOutage) {
            true -> SummaryTone.GOOD
            false -> SummaryTone.CRITICAL
            null -> SummaryTone.NEUTRAL
        }

        if (info != null && light == GridState.OFF) {
            val backupW = backupLoadWatts(snapshot)
            val backup = backupW?.let(::formatWatts)
            val returnAt = info.currentEnd?.let { PowerOutageFormatter.shortDetail(info, now) }
            // One runtime number during an outage: autonomy runtime if known, else the battery ETA to 0%.
            val runtime = info.runtimeSeconds ?: dischargeEtaSeconds(snapshot)?.takeIf { snapshot.direction == BatteryDirection.DISCHARGING }
            val remaining = info.currentEnd?.let { end ->
                runCatching { java.time.Duration.between(now, java.time.OffsetDateTime.parse(end)).seconds }.getOrNull()
            }?.takeIf { it > 0 }
            val covers = info.coversOutage ?: if (runtime != null && remaining != null) runtime >= remaining else null
            val runtimeValue = runtime?.let(EtaTextFormatter::formatSeconds)
            // Power always shown: «Споживання 1330 Вт · вистачить на 3 год 22 хв».
            val reserveText = outagePowerLine(backupW, runtime, covers) ?: "Запас: оцінюю розряд…"
            val outageTone = when (covers) {
                true -> SummaryTone.GOOD
                false -> SummaryTone.CRITICAL
                null -> SummaryTone.NEUTRAL
            }
            return WidgetSummary(
                mode = SummaryMode.OUTAGE,
                lightState = light,
                primary = listOfNotNull("Світла нема", backup).joinToString(SEP),
                primaryTone = SummaryTone.WARNING,
                // The runtime number sits in the trailing column ("0% · X · °C"); the middle line carries only the verdict.
                secondary = when {
                    runtimeValue == null -> reserveText
                    covers == true -> "Запасу вистачить"
                    covers == false -> "Запасу не вистачить"
                    else -> "Запас до ${floorTarget(info)} · оцінка"
                },
                trailingPrimary = returnAt ?: "до —",
                trailingSecondary = listOfNotNull("${floorTarget(info)} · ${runtimeValue ?: "—"}", temperature).joinToString(SEP),
                // Priority: power + runtime first, then when the light returns.
                lines = listOfNotNull(
                    SummaryLine(SummaryLineKind.RESERVE, reserveText, outageTone),
                    SummaryLine(
                        SummaryLineKind.RETURN_TIME,
                        if (info.currentEnd != null) PowerOutageFormatter.detail(info, now) else "Час повернення невідомий",
                    ),
                    temperatureLine,
                ),
                // 4×4 headline: the power sits in the light block's «Споживання … · вистачить на …» row, not here too.
                batteryState = directionWord(snapshot.direction),
            )
        }

        val lightShort = info?.let { listOfNotNull(PowerOutageFormatter.headline(it), PowerOutageFormatter.shortDetail(it, now)).joinToString(SEP) }
        val lightLine = info?.let {
            SummaryLine(SummaryLineKind.LIGHT, PowerOutageFormatter.headline(it) + SEP + PowerOutageFormatter.detailBesideHeadline(it, now),
                if (it.gridState == GridState.ON) SummaryTone.GOOD else SummaryTone.NEUTRAL)
        }
        val reserveLine = reserve?.let { SummaryLine(SummaryLineKind.RESERVE, it, reserveTone) }

        when (snapshot.direction) {
            BatteryDirection.CHARGING, BatteryDirection.DISCHARGING -> {
                val charging = snapshot.direction == BatteryDirection.CHARGING
                val power = snapshot.batteryPowerWatts?.takeIf(Double::isFinite)?.let(::formatKw)
                val etaSeconds = if (charging) snapshot.etaSeconds else dischargeEtaSeconds(snapshot)
                val eta = EtaTextFormatter.formatSeconds(etaSeconds)
                val target = if (charging) "100%" else floorTarget(info)
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
                        SummaryLine(SummaryLineKind.ETA, if (etaSeconds != null) "До $target ≈ $eta · оцінка" else "До $target: —"),
                        lightLine,
                        reserveLine,
                        temperatureLine,
                    ),
                    batteryState = listOfNotNull(if (charging) "Заряджається" else "Розряджається", power?.let { "≈ $it" }).joinToString(SEP),
                    etaText = if (etaSeconds != null) "До $target ≈ $eta · оцінка" else "До $target: —",
                )
            }
            BatteryDirection.IDLE, BatteryDirection.UNKNOWN -> {
                val idle = snapshot.direction == BatteryDirection.IDLE
                val stateWord = if (idle) "очікування" else "стан невідомий"
                val runtime = info?.runtimeSeconds?.let { "≈ ${EtaTextFormatter.formatSeconds(it)}" }
                // The headline already names the light state, so the light detail line carries only the schedule.
                val lightDetail = info?.let { SummaryLine(SummaryLineKind.LIGHT, PowerOutageFormatter.detailBesideHeadline(it, now)) }
                return WidgetSummary(
                    mode = if (idle) SummaryMode.IDLE else SummaryMode.UNKNOWN,
                    lightState = light,
                    primary = info?.let { PowerOutageFormatter.headline(it) + SEP + stateWord } ?: stateWord.replaceFirstChar { it.titlecase(uk) },
                    primaryTone = SummaryTone.NEUTRAL,
                    secondary = info?.let { PowerOutageFormatter.detail(it, now) },
                    trailingPrimary = runtime ?: temperature ?: "—",
                    trailingSecondary = if (runtime != null) listOfNotNull("запас", temperature).joinToString(SEP) else null,
                    lines = listOfNotNull(lightDetail, reserveLine, temperatureLine),
                    batteryState = directionWord(snapshot.direction),
                    etaText = if (idle && (snapshot.socPercent ?: 0) >= FULL_SOC) "Заряджено" else null,
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

    fun formatWatts(watts: Double): String = String.format(uk, "%.0f\u00A0Вт", abs(watts))
    /** Battery flow: «300 Вт» below 1 kW, «1,2 кВт» from 1 kW (value and unit joined by a no-break space). */
    fun formatKw(watts: Double): String = if (abs(watts) < 999.5) formatWatts(watts) else String.format(uk, "%.1f\u00A0кВт", abs(watts) / 1000.0)

    /** The house load on the backup output during an outage: only a POSITIVE reading (negative = noise), else null. */
    fun backupLoadWatts(snapshot: BatterySnapshot?): Double? =
        (snapshot?.insights?.backupOutputWatts ?: snapshot?.offgridPowerWatts)?.takeIf { it.isFinite() && it >= 1.0 }

    /** «Споживання 1330 Вт · вистачить на 3 год 22 хв» — the outage power row shared by all widget shapes. */
    fun outagePowerLine(backupW: Double?, runtimeSeconds: Long?, covers: Boolean?): String? {
        val runtime = runtimeSeconds?.let(EtaTextFormatter::formatSeconds)
        val verdict = if (covers == false) "вистачить лише на" else "вистачить на"
        return when {
            backupW != null && runtime != null -> "Споживання ${formatWatts(backupW)} · $verdict $runtime"
            backupW != null -> "Споживання ${formatWatts(backupW)} · оцінюю запас…"
            runtime != null -> "Запас ≈ $runtime" + if (covers == false) " · не вистачить" else if (covers == true) " · вистачить" else ""
            else -> null
        }
    }
    fun formatTemperature(celsius: Double): String = String.format(uk, "%.1f\u00A0°C", celsius)
}
