package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

enum class AlertSeverity { INFO, WARN, CRITICAL }

/** User-facing toggle groups; each alert belongs to exactly one. */
enum class AlertGroup(val title: String) {
    LIGHT_SCHEDULE("Світло і графік"),
    RESERVE("Запас"),
    CHARGING("Заряджання"),
    CONSUMPTION("Споживання"),
    BATTERY_HEALTH("Стан батареї"),
}

enum class AlertId(val group: AlertGroup) {
    OUTAGE_SOON(AlertGroup.LIGHT_SCHEDULE),
    SCHEDULE_PUBLISHED(AlertGroup.LIGHT_SCHEDULE),
    LIGHT_LOST(AlertGroup.LIGHT_SCHEDULE),
    LIGHT_RETURNED(AlertGroup.LIGHT_SCHEDULE),
    RESERVE_SHORT_NEXT(AlertGroup.RESERVE),
    RESERVE_SHORT_CURRENT(AlertGroup.RESERVE),
    SOC_LOW(AlertGroup.RESERVE),
    SOC_CRITICAL(AlertGroup.RESERVE),
    CHARGING_STARTED(AlertGroup.CHARGING),
    CHARGED_FULL(AlertGroup.CHARGING),
    NOT_CHARGING_AFTER_RETURN(AlertGroup.CHARGING),
    HIGH_LOAD(AlertGroup.CONSUMPTION),
    TEMPERATURE_HIGH(AlertGroup.BATTERY_HEALTH),
    TEMPERATURE_COLD_CHARGING(AlertGroup.BATTERY_HEALTH),
    DATA_STALE(AlertGroup.BATTERY_HEALTH),
}

data class Alert(val id: AlertId, val severity: AlertSeverity, val title: String, val text: String) {
    val group: AlertGroup get() = id.group
}

data class AlertSettings(
    val enabledGroups: Set<AlertGroup> = AlertGroup.entries.toSet(),
    val highLoadThresholdWatts: Int = AlertEvaluator.DEFAULT_HIGH_LOAD_W,
)

/** Persisted between checks: last observed state + per-alert dedupe/hysteresis flags. */
data class AlertMemory(
    val gridState: GridState? = null,
    val direction: BatteryDirection? = null,
    val soc: Int? = null,
    val hadFreshData: Boolean = false,
    val staleSent: Boolean = false,
    val outageStartedAtMillis: Long? = null,
    val socAtOutageStart: Int? = null,
    val returnedAtMillis: Long? = null,
    val notChargingChecks: Int = 0,
    val notChargingSent: Boolean = false,
    val outageSoonSentFor: String? = null,
    val reserveNextSentFor: String? = null,
    val reserveCurrentSent: Boolean = false,
    val schedulePublished: Boolean? = null,
    val lastPublishedKey: String? = null,
    val lastNextStart: String? = null,
    val chargingStartedAtMillis: Long? = null,
    val fullSent: Boolean = false,
    val highLoadSent: Boolean = false,
    val socLowSent: Boolean = false,
    val socCriticalSent: Boolean = false,
    val tempHighSent: Boolean = false,
    val tempColdSent: Boolean = false,
)

/** [notifications] = events to post now (already filtered by toggles); [active] = conditions currently true. */
data class AlertEvaluation(val notifications: List<Alert>, val active: List<Alert>, val memory: AlertMemory)

/**
 * Pure alert engine: previous memory + current snapshot → alerts. Read-only: it only recommends,
 * it never sends commands to the battery. All power/energy/time figures are estimates.
 */
object AlertEvaluator {
    const val DEFAULT_HIGH_LOAD_W = 200
    const val STALE_AFTER_MILLIS = 30 * 60_000L
    const val OUTAGE_SOON_MINUTES = 60L
    const val OUTAGE_WAKEUP_WINDOW_MINUTES = 75L
    const val SOC_LOW = 20
    const val SOC_LOW_REARM = 25
    const val SOC_CRITICAL = 10
    const val SOC_CRITICAL_REARM = 15
    const val TEMP_HIGH = 45.0
    const val TEMP_COLD = 5.0
    const val TEMP_HYSTERESIS = 3.0
    const val NOT_CHARGING_SOC = 90
    const val NOT_CHARGING_CHECKS = 2
    private const val CHARGING_RENOTIFY_MILLIS = 60 * 60_000L
    private const val FULL_REARM_SOC = 95
    private const val HIGH_LOAD_REARM_FACTOR = 0.8

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val uk: Locale = Locale.forLanguageTag("uk-UA")
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private const val SEP = " · "

    fun top(active: List<Alert>): Alert? = active.maxByOrNull { it.severity.ordinal }

    /** Delay for a one-time check exactly [OUTAGE_SOON_MINUTES] before a start that is 60–75 min away; else null. */
    fun outageSoonWakeupDelayMillis(nextStart: String?, nowMillis: Long): Long? {
        val start = nextStart?.let(::parseMillis) ?: return null
        val until = start - nowMillis
        if (until <= OUTAGE_SOON_MINUTES * 60_000L || until > OUTAGE_WAKEUP_WINDOW_MINUTES * 60_000L) return null
        return until - OUTAGE_SOON_MINUTES * 60_000L
    }

    fun evaluate(
        previous: AlertMemory?,
        snapshot: BatterySnapshot?,
        nowMillis: Long,
        settings: AlertSettings,
        lastFetchedAtMillis: Long = 0L,
    ): AlertEvaluation {
        val m0 = previous ?: AlertMemory()
        if (snapshot == null) return AlertEvaluation(emptyList(), emptyList(), m0)
        val now = Instant.ofEpochMilli(nowMillis).atZone(kyiv)
        val events = mutableListOf<Alert>()
        val active = mutableListOf<Alert>()

        val telemetryAt = snapshot.collectedAt?.let(::parseMillis) ?: lastFetchedAtMillis.takeIf { it > 0L }
        if (telemetryAt != null && nowMillis - telemetryAt > STALE_AFTER_MILLIS) {
            val minutes = (nowMillis - telemetryAt) / 60_000L
            val alert = Alert(
                AlertId.DATA_STALE, AlertSeverity.WARN, "Немає свіжих даних",
                "Останні дані о ${hhmm(telemetryAt)} ($minutes хв тому) — зв’язок з батареєю або сервером втрачено. Сповіщення про стан тимчасово не перевіряються.",
            )
            var memory = m0
            if (m0.hadFreshData && !m0.staleSent) {
                events += alert
                memory = memory.copy(staleSent = true)
            }
            return AlertEvaluation(events.filter { it.group in settings.enabledGroups }, listOf(alert), memory)
        }

        var m = m0.copy(hadFreshData = true, staleSent = false)
        val info = snapshot.powerOutage
        val soc = snapshot.socPercent
        val direction = snapshot.direction
        val lastGrid = m0.gridState
        // Light only from the backup output of a battery that answered: a lost link keeps the last known state,
        // so a dead router/Wi-Fi or an unreachable server never fires LIGHT_LOST (even if the schedule says outage).
        val grid = info?.gridState?.takeIf { it != GridState.UNKNOWN && !snapshot.batteryLinkLost } ?: lastGrid
        val prevDirection = m0.direction?.takeIf { it != BatteryDirection.UNKNOWN }
        val backupW = snapshot.offgridPowerWatts ?: snapshot.insights?.backupOutputWatts
        val runtime = unifiedRuntime(snapshot)
        val temperature = snapshot.temperatureCelsius ?: snapshot.insights?.temperatureCelsius

        // --- light lost / returned ---
        var returnTick = false
        if (lastGrid == GridState.ON && grid == GridState.OFF) {
            events += Alert(AlertId.LIGHT_LOST, AlertSeverity.WARN, "Світло зникло", listOfNotNull(
                backupW?.let { "Резервний вихід ${watts(it)}" },
                soc?.let { "заряд $it%" },
                runtime?.let { "до 0% ≈ ${duration(it)} (оцінка)" },
                info?.currentEnd?.let(::parseZoned)?.let { "повернення о ${stamp(it, now)}" },
            ).joinToString(SEP).ifBlank { "Батарея живить резервний вихід" })
            m = m.copy(outageStartedAtMillis = nowMillis, socAtOutageStart = soc, returnedAtMillis = null, notChargingChecks = 0, notChargingSent = false)
        }
        if (lastGrid == GridState.OFF && grid == GridState.ON) {
            val observed = m0.outageStartedAtMillis?.let { (nowMillis - it) / 1000L }
            val startSoc = m0.socAtOutageStart
            val used = if (startSoc != null && soc != null && startSoc > soc) snapshot.ratedCapacityWh?.let { (startSoc - soc) / 100.0 * it } else null
            events += Alert(AlertId.LIGHT_RETURNED, AlertSeverity.INFO, "Світло повернулось", listOfNotNull(
                observed?.let { "Без світла ≈ ${duration(it)}" },
                used?.let { "витрачено ≈ ${kwh(it)} ($startSoc% → $soc%)" },
                soc?.let { "заряд $it%" }.takeIf { used == null },
                if (direction == BatteryDirection.CHARGING) "заряджання почалось" else "заряджання ще не почалось",
            ).joinToString(SEP))
            m = m.copy(outageStartedAtMillis = null, socAtOutageStart = null, returnedAtMillis = nowMillis, notChargingChecks = 0, notChargingSent = false)
            returnTick = true
        }
        if (grid == GridState.ON) m = m.copy(reserveCurrentSent = false)

        // --- not charging after return (read-only: we can only suggest checking the mode) ---
        if (m.returnedAtMillis != null && !returnTick) {
            val stuck = grid == GridState.ON && soc != null && soc < NOT_CHARGING_SOC && direction != BatteryDirection.CHARGING
            if (stuck) {
                val checks = m.notChargingChecks + 1
                m = m.copy(notChargingChecks = checks)
                if (checks >= NOT_CHARGING_CHECKS) {
                    val alert = Alert(AlertId.NOT_CHARGING_AFTER_RETURN, AlertSeverity.WARN, "Світло є, але батарея не заряджається",
                        "Заряд $soc%, світло повернулось о ${hhmm(m.returnedAtMillis!!)}, а заряджання так і не почалось. " +
                            "Перевірте режим роботи в застосунку Marstek (цей застосунок лише читає дані).")
                    active += alert
                    if (!m.notChargingSent) {
                        events += alert
                        m = m.copy(notChargingSent = true)
                    }
                }
            } else {
                m = m.copy(returnedAtMillis = null, notChargingChecks = 0, notChargingSent = false)
            }
        } else if (returnTick && direction == BatteryDirection.CHARGING) {
            m = m.copy(returnedAtMillis = null)
        }

        // --- schedule: before / around the next outage ---
        val nextStart = info?.nextStart?.let(::parseZoned)
        val nextEnd = info?.nextEnd?.let(::parseZoned)
        val nextDuration = info?.nextDurationSeconds ?: if (nextStart != null && nextEnd != null) Duration.between(nextStart, nextEnd).seconds else null
        if (info != null && grid != GridState.OFF && nextStart != null && nextStart.isAfter(now)) {
            val minutes = Duration.between(now, nextStart).toMinutes()
            val nextRuntime = info.runtimeSeconds ?: runtimeFromLoad(snapshot.batteryCapacityWh, loadWatts(snapshot))
            val covers = info.coversOutage ?: if (nextRuntime != null && nextDuration != null) nextRuntime >= nextDuration else null
            if (minutes <= OUTAGE_SOON_MINUTES) {
                val verdict = when (covers) { true -> " — вистачить"; false -> " — не вистачить"; null -> "" }
                val alert = Alert(
                    AlertId.OUTAGE_SOON, if (covers == false) AlertSeverity.WARN else AlertSeverity.INFO,
                    "Відключення о ${nextStart.format(clock)}${info.group?.let { " (черга $it)" } ?: ""}",
                    listOfNotNull(
                        "Через ${duration(minutes * 60)}",
                        soc?.let { "заряд $it%" },
                        nextRuntime?.let { "запас ≈ ${duration(it)}$verdict" } ?: "запас: —",
                    ).joinToString(SEP),
                )
                active += alert
                if (m0.outageSoonSentFor != info.nextStart) events += alert
                m = m.copy(outageSoonSentFor = info.nextStart)
            }
            if (covers == false && nextDuration != null) {
                val load = loadWatts(snapshot)
                val needed = load?.let { it * nextDuration / 3600.0 }
                val have = snapshot.batteryCapacityWh
                val window = "Відключення ${stamp(nextStart, now)}${nextEnd?.let { "–" + it.format(clock) } ?: ""} (${duration(nextDuration)})"
                val numbers = if (needed != null && have != null) {
                    "треба ≈ ${kwh(needed)}, є ${kwh(have)}" + if (needed > have) " — бракує ≈ ${kwh(needed - have)}" else ""
                } else null
                val alert = Alert(AlertId.RESERVE_SHORT_NEXT, AlertSeverity.WARN, "До відключення варто дозарядити", listOfNotNull(
                    "$window: ${numbers ?: "запасу не вистачить"}",
                    nextRuntime?.let { "запас ≈ ${duration(it)}" },
                    load?.let { "оцінка за споживанням ${watts(it)}" },
                ).joinToString(SEP))
                active += alert
                if (m0.reserveNextSentFor != info.nextStart) events += alert
                m = m.copy(reserveNextSentFor = info.nextStart)
            }
        }

        // --- schedule published / changed ---
        if (info != null) {
            val key = "${info.nextStart}|${info.nextEnd}"
            val previouslyPublished = m0.schedulePublished
            if (info.schedulePublished && previouslyPublished != null && key != m0.lastPublishedKey) {
                val progressed = m0.lastNextStart?.let(::parseMillis)?.let { it <= nowMillis } == true
                val newlyPublished = !previouslyPublished
                if (newlyPublished || !progressed) {
                    events += Alert(AlertId.SCHEDULE_PUBLISHED, AlertSeverity.INFO,
                        "Графік оновлено${info.group?.let { " (черга $it)" } ?: ""}",
                        if (nextStart != null) "Наступне: ${PowerOutageFormatter.detail(info, now)}" else "Відключень за графіком не заплановано")
                }
            }
            m = m.copy(
                schedulePublished = info.schedulePublished,
                lastPublishedKey = if (info.schedulePublished) key else m0.lastPublishedKey,
                lastNextStart = info.nextStart,
            )
        }

        // --- reserve during the current outage ---
        if (grid == GridState.OFF) {
            val remaining = info?.currentEnd?.let(::parseMillis)?.let { (it - nowMillis) / 1000L }?.takeIf { it > 0 }
            val covers = info?.coversOutage ?: if (runtime != null && remaining != null) runtime >= remaining else null
            if (covers == false) {
                val alert = Alert(AlertId.RESERVE_SHORT_CURRENT, AlertSeverity.CRITICAL, "Запасу може не вистачити", listOfNotNull(
                    runtime?.let { "Запас ≈ ${duration(it)}" },
                    info?.currentEnd?.let(::parseZoned)?.let { end -> "світло повернуть о ${stamp(end, now)}" + (remaining?.let { " (через ${duration(it)})" } ?: "") },
                    soc?.let { "заряд $it%" },
                    "зменшіть навантаження",
                ).joinToString(SEP))
                active += alert
                if (!m.reserveCurrentSent) {
                    events += alert
                    m = m.copy(reserveCurrentSent = true)
                }
            }
        }

        // --- high load during an outage (Venus bypasses while the grid is on) ---
        if (grid == GridState.OFF && backupW != null) {
            val load = abs(backupW)
            val avg = snapshot.insights?.backupAverageWatts24h
            val limit = max(settings.highLoadThresholdWatts.toDouble(), avg?.let { 2 * it } ?: 0.0)
            if (load > limit) {
                val left = runtimeFromLoad(snapshot.batteryCapacityWh, load)
                val alert = Alert(AlertId.HIGH_LOAD, AlertSeverity.WARN, "Велике споживання під час відключення",
                    "Споживання ${watts(load)}" + (left?.let { " — запасу вистачить лише на ${duration(it)}" } ?: "") +
                        " (поріг ${watts(limit)}${avg?.let { ", середнє ${watts(it)}" } ?: ""}; оцінка)")
                active += alert
                if (!m.highLoadSent) {
                    events += alert
                    m = m.copy(highLoadSent = true)
                }
            } else if (load < limit * HIGH_LOAD_REARM_FACTOR) {
                m = m.copy(highLoadSent = false)
            }
        } else if (grid != GridState.OFF) {
            m = m.copy(highLoadSent = false)
        }

        // --- SOC ---
        if (soc != null) {
            if (soc >= SOC_LOW_REARM) m = m.copy(socLowSent = false)
            if (soc >= SOC_CRITICAL_REARM) m = m.copy(socCriticalSent = false)
            val stored = snapshot.batteryCapacityWh?.let { kwh(it) }
            val outageRuntime = runtime?.takeIf { grid == GridState.OFF }?.let { "до 0% ≈ ${duration(it)}" }
            if (soc < SOC_CRITICAL) {
                val alert = Alert(AlertId.SOC_CRITICAL, AlertSeverity.CRITICAL, "Критично низький заряд: $soc%",
                    listOfNotNull("Заряд $soc%", stored, outageRuntime).joinToString(SEP))
                active += alert
                if (!m.socCriticalSent) {
                    events += alert
                    m = m.copy(socCriticalSent = true, socLowSent = true)
                }
            } else if (soc < SOC_LOW) {
                val alert = Alert(AlertId.SOC_LOW, AlertSeverity.WARN, "Заряд батареї нижче $SOC_LOW%",
                    listOfNotNull("Заряд $soc%", stored, outageRuntime).joinToString(SEP))
                active += alert
                if (!m.socLowSent) {
                    events += alert
                    m = m.copy(socLowSent = true)
                }
            }
        }

        // --- charging ---
        if (direction == BatteryDirection.CHARGING && prevDirection != null && prevDirection != BatteryDirection.CHARGING &&
            (m0.chargingStartedAtMillis == null || nowMillis - m0.chargingStartedAtMillis > CHARGING_RENOTIFY_MILLIS)
        ) {
            events += Alert(AlertId.CHARGING_STARTED, AlertSeverity.INFO, "Почалось заряджання", listOfNotNull(
                snapshot.batteryPowerWatts?.let { "≈ ${watts(it)} (оцінка)" },
                soc?.let { "заряд $it%" },
                snapshot.etaSeconds?.let { "до 100% ≈ ${duration(it)}" },
            ).joinToString(SEP))
            m = m.copy(chargingStartedAtMillis = nowMillis)
        }
        if (soc != null) {
            if (soc < FULL_REARM_SOC) m = m.copy(fullSent = false)
            val reachedHundred = soc >= 100 && (m0.soc ?: 100) < 100
            val stoppedNearFull = prevDirection == BatteryDirection.CHARGING && direction == BatteryDirection.IDLE && soc >= 98
            if ((reachedHundred || stoppedNearFull) && !m.fullSent) {
                events += Alert(AlertId.CHARGED_FULL, AlertSeverity.INFO, "Батарею заряджено",
                    listOfNotNull("Заряд $soc%", snapshot.batteryCapacityWh?.let(::kwh)).joinToString(SEP))
                m = m.copy(fullSent = true)
            }
        }

        // --- temperature (LiFePO4) ---
        if (temperature != null) {
            if (temperature < TEMP_HIGH - TEMP_HYSTERESIS) m = m.copy(tempHighSent = false)
            if (temperature >= TEMP_COLD + TEMP_HYSTERESIS) m = m.copy(tempColdSent = false)
            if (temperature > TEMP_HIGH) {
                val alert = Alert(AlertId.TEMPERATURE_HIGH, AlertSeverity.WARN, "Висока температура батареї",
                    "Температура ${celsius(temperature)} (поріг ${TEMP_HIGH.toInt()} °C) — забезпечте вентиляцію, зменшіть навантаження")
                active += alert
                if (!m.tempHighSent) {
                    events += alert
                    m = m.copy(tempHighSent = true)
                }
            }
            if (temperature < TEMP_COLD && direction == BatteryDirection.CHARGING) {
                val alert = Alert(AlertId.TEMPERATURE_COLD_CHARGING, AlertSeverity.WARN, "Заряджання на холоді",
                    "Температура ${celsius(temperature)} під час заряджання — LiFePO4 не варто заряджати нижче ${TEMP_COLD.toInt()} °C")
                active += alert
                if (!m.tempColdSent) {
                    events += alert
                    m = m.copy(tempColdSent = true)
                }
            }
        }

        m = m.copy(
            gridState = grid,
            direction = direction.takeIf { it != BatteryDirection.UNKNOWN } ?: m0.direction,
            soc = soc ?: m0.soc,
        )
        return AlertEvaluation(
            notifications = events.filter { it.group in settings.enabledGroups },
            active = active.sortedByDescending { it.severity.ordinal },
            memory = m,
        )
    }

    /** One runtime number during an outage: the server's autonomy runtime if known, else the battery ETA to 0%. */
    fun unifiedRuntime(snapshot: BatterySnapshot): Long? =
        snapshot.powerOutage?.runtimeSeconds ?: snapshot.etaSeconds?.takeIf { snapshot.direction == BatteryDirection.DISCHARGING }

    private fun loadWatts(snapshot: BatterySnapshot): Double? =
        snapshot.powerOutage?.loadWatts?.takeIf { it > 0 } ?: snapshot.insights?.backupAverageWatts24h?.takeIf { it > 0 }

    private fun runtimeFromLoad(capacityWh: Double?, load: Double?): Long? {
        if (capacityWh == null || load == null || load <= 0.0) return null
        return (capacityWh / load * 3600.0).toLong()
    }

    private fun parseMillis(value: String): Long? = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun parseZoned(value: String): ZonedDateTime? = parseMillis(value)?.let { Instant.ofEpochMilli(it).atZone(kyiv) }

    private fun hhmm(millis: Long): String = Instant.ofEpochMilli(millis).atZone(kyiv).format(clock)

    private fun stamp(value: ZonedDateTime, now: ZonedDateTime): String =
        if (value.toLocalDate() == now.toLocalDate()) value.format(clock) else "${value.format(DateTimeFormatter.ofPattern("dd.MM"))} ${value.format(clock)}"

    private fun duration(seconds: Long): String = EtaTextFormatter.formatSeconds(seconds.coerceAtLeast(1))
    private fun watts(value: Double): String = String.format(uk, "%.0f W", abs(value))
    private fun kwh(wh: Double): String = String.format(uk, "%.2f kWh", wh / 1000.0)
    private fun celsius(value: Double): String = String.format(uk, "%.1f\u00A0°C", value)
}

/** JSON persistence for [AlertMemory] (stored as one SharedPreferences string). */
object AlertMemoryCodec {
    fun encode(m: AlertMemory): String = JSONObject()
        .put("gridState", m.gridState?.name ?: JSONObject.NULL)
        .put("direction", m.direction?.name ?: JSONObject.NULL)
        .put("soc", m.soc ?: JSONObject.NULL)
        .put("hadFreshData", m.hadFreshData)
        .put("staleSent", m.staleSent)
        .put("outageStartedAtMillis", m.outageStartedAtMillis ?: JSONObject.NULL)
        .put("socAtOutageStart", m.socAtOutageStart ?: JSONObject.NULL)
        .put("returnedAtMillis", m.returnedAtMillis ?: JSONObject.NULL)
        .put("notChargingChecks", m.notChargingChecks)
        .put("notChargingSent", m.notChargingSent)
        .put("outageSoonSentFor", m.outageSoonSentFor ?: JSONObject.NULL)
        .put("reserveNextSentFor", m.reserveNextSentFor ?: JSONObject.NULL)
        .put("reserveCurrentSent", m.reserveCurrentSent)
        .put("schedulePublished", m.schedulePublished ?: JSONObject.NULL)
        .put("lastPublishedKey", m.lastPublishedKey ?: JSONObject.NULL)
        .put("lastNextStart", m.lastNextStart ?: JSONObject.NULL)
        .put("chargingStartedAtMillis", m.chargingStartedAtMillis ?: JSONObject.NULL)
        .put("fullSent", m.fullSent)
        .put("highLoadSent", m.highLoadSent)
        .put("socLowSent", m.socLowSent)
        .put("socCriticalSent", m.socCriticalSent)
        .put("tempHighSent", m.tempHighSent)
        .put("tempColdSent", m.tempColdSent)
        .toString()

    fun decode(raw: String?): AlertMemory? {
        raw ?: return null
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        fun str(k: String) = if (!j.has(k) || j.isNull(k)) null else j.optString(k)
        fun long(k: String) = if (!j.has(k) || j.isNull(k)) null else j.optLong(k)
        fun int(k: String) = if (!j.has(k) || j.isNull(k)) null else j.optInt(k)
        fun bool(k: String) = j.optBoolean(k, false)
        return AlertMemory(
            gridState = str("gridState")?.let { runCatching { GridState.valueOf(it) }.getOrNull() },
            direction = str("direction")?.let { runCatching { BatteryDirection.valueOf(it) }.getOrNull() },
            soc = int("soc"),
            hadFreshData = bool("hadFreshData"),
            staleSent = bool("staleSent"),
            outageStartedAtMillis = long("outageStartedAtMillis"),
            socAtOutageStart = int("socAtOutageStart"),
            returnedAtMillis = long("returnedAtMillis"),
            notChargingChecks = j.optInt("notChargingChecks", 0),
            notChargingSent = bool("notChargingSent"),
            outageSoonSentFor = str("outageSoonSentFor"),
            reserveNextSentFor = str("reserveNextSentFor"),
            reserveCurrentSent = bool("reserveCurrentSent"),
            schedulePublished = if (!j.has("schedulePublished") || j.isNull("schedulePublished")) null else j.optBoolean("schedulePublished"),
            lastPublishedKey = str("lastPublishedKey"),
            lastNextStart = str("lastNextStart"),
            chargingStartedAtMillis = long("chargingStartedAtMillis"),
            fullSent = bool("fullSent"),
            highLoadSent = bool("highLoadSent"),
            socLowSent = bool("socLowSent"),
            socCriticalSent = bool("socCriticalSent"),
            tempHighSent = bool("tempHighSent"),
            tempColdSent = bool("tempColdSent"),
        )
    }
}
