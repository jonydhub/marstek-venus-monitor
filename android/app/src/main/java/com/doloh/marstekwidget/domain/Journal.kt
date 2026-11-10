package com.doloh.marstekwidget.domain

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class JournalCategory(val title: String) {
    LIGHT("Світло"), BATTERY("Батарея"), CONNECTION("Зв'язок"), ANOMALY("Аномалія");

    val id: String get() = name.lowercase()

    companion object {
        fun fromId(value: String?): JournalCategory? = entries.firstOrNull { it.id == value?.lowercase() }
    }
}

/** One journal line; same shape as the server's `/api/events` items ([tsMillis] instead of an ISO string). */
data class JournalEvent(val tsMillis: Long, val kind: String, val category: JournalCategory, val text: String, val value: Double? = null) {
    fun toJson(): JSONObject = JSONObject().put("ts", tsMillis).put("kind", kind).put("category", category.id)
        .put("text", text).put("value", value ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): JournalEvent? {
            val category = JournalCategory.fromId(json.optString("category")) ?: return null
            val ts = when (val raw = json.opt("ts")) {
                is Number -> raw.toLong().let { if (it < 10_000_000_000L) it * 1000 else it }
                is String -> runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
                else -> null
            } ?: return null
            val text = json.optString("text").takeIf(String::isNotBlank) ?: return null
            val value = if (json.has("value") && !json.isNull("value")) json.optDouble("value").takeIf(Double::isFinite) else null
            return JournalEvent(ts, json.optString("kind"), category, text, value)
        }
    }
}

/** One poll for the detector; `ok` = the battery answered. Light comes only from the backup output (gridState). */
data class JournalObservation(
    val ok: Boolean,
    val gridState: GridState = GridState.UNKNOWN,
    val direction: BatteryDirection = BatteryDirection.UNKNOWN,
    val batteryWatts: Double? = null,
    val backupWatts: Double? = null,
    val soc: Int? = null,
    val temperatureC: Double? = null,
)

data class DetectorMemory(
    val grid: GridState? = null,
    val direction: BatteryDirection? = null,
    val soc: Int? = null,
    val failures: Int = 0,
    val connectionLost: Boolean = false,
    val lostAtMillis: Long? = null,
    val outageStartedAtMillis: Long? = null,
    val fullSent: Boolean = false,
    val highDischargeSent: Boolean = false,
    val onGridSent: Boolean = false,
    val tempSent: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("grid", grid?.name ?: JSONObject.NULL).put("direction", direction?.name ?: JSONObject.NULL)
        .put("soc", soc ?: JSONObject.NULL).put("failures", failures).put("connectionLost", connectionLost)
        .put("lostAt", lostAtMillis ?: JSONObject.NULL).put("outageStartedAt", outageStartedAtMillis ?: JSONObject.NULL)
        .put("fullSent", fullSent).put("highDischargeSent", highDischargeSent).put("onGridSent", onGridSent).put("tempSent", tempSent)

    companion object {
        fun fromJson(raw: String?): DetectorMemory? = runCatching {
            val j = JSONObject(raw ?: return null)
            fun long(k: String) = if (j.isNull(k)) null else j.optLong(k)
            DetectorMemory(
                grid = j.optString("grid").takeIf { !j.isNull("grid") }?.let { runCatching { GridState.valueOf(it) }.getOrNull() },
                direction = j.optString("direction").takeIf { !j.isNull("direction") }?.let { runCatching { BatteryDirection.valueOf(it) }.getOrNull() },
                soc = if (j.isNull("soc")) null else j.optInt("soc"),
                failures = j.optInt("failures"), connectionLost = j.optBoolean("connectionLost"),
                lostAtMillis = long("lostAt"), outageStartedAtMillis = long("outageStartedAt"),
                fullSent = j.optBoolean("fullSent"), highDischargeSent = j.optBoolean("highDischargeSent"),
                onGridSent = j.optBoolean("onGridSent"), tempSent = j.optBoolean("tempSent"),
            )
        }.getOrNull()
    }
}

/**
 * Port of server/events.py. A lost connection is its own event and never «світла нема»: without an answer the
 * grid state is unknown and the last known state is kept, so no light event fires.
 */
object JournalDetector {
    const val CONNECTION_LOST_AFTER = 3
    const val DEFAULT_ANOMALY_DISCHARGE_W = 2000
    const val MIN_ANOMALY_DISCHARGE_W = 200
    const val MAX_ANOMALY_DISCHARGE_W = 10_000
    private const val DISCHARGE_ON_GRID_W = 100.0
    private const val TEMP_HIGH = 45.0
    private const val TEMP_REARM = 42.0
    private const val FULL_SOC = 99
    private const val REARM_FACTOR = 0.8

    fun clampThreshold(watts: Int): Int = watts.coerceIn(MIN_ANOMALY_DISCHARGE_W, MAX_ANOMALY_DISCHARGE_W)

    fun step(previous: DetectorMemory?, obs: JournalObservation, nowMillis: Long, dischargeThresholdW: Int = DEFAULT_ANOMALY_DISCHARGE_W):
        Pair<List<JournalEvent>, DetectorMemory> {
        var m = previous ?: DetectorMemory()
        val events = mutableListOf<JournalEvent>()
        fun add(kind: String, category: JournalCategory, text: String, value: Double? = null) {
            events += JournalEvent(nowMillis, kind, category, text, value?.let { Math.round(it * 10.0) / 10.0 })
        }
        if (!obs.ok) {
            val failures = m.failures + 1
            m = m.copy(failures = failures)
            if (failures >= CONNECTION_LOST_AFTER && !m.connectionLost) {
                add("connection_lost", JournalCategory.CONNECTION,
                    "Немає зв'язку з батареєю: $failures опитування поспіль без відповіді (стан світла невідомий)")
                m = m.copy(connectionLost = true, lostAtMillis = nowMillis)
            }
            return events to m
        }
        if (m.connectionLost) {
            val gap = m.lostAtMillis?.let { " після ${durationText((nowMillis - it) / 1000)}" }.orEmpty()
            add("connection_restored", JournalCategory.CONNECTION, "Зв'язок з батареєю відновлено$gap")
        }
        m = m.copy(failures = 0, connectionLost = false, lostAtMillis = null)

        val soc = obs.soc
        val socText = soc?.let { ", заряд $it%" }.orEmpty()
        val backup = obs.backupWatts?.takeIf(Double::isFinite)
        val battery = obs.batteryWatts?.takeIf(Double::isFinite)
        val grid = obs.gridState.takeIf { it != GridState.UNKNOWN }
        if (grid != null && m.grid != null && grid != m.grid) {
            if (grid == GridState.OFF) {
                val watts = backup?.let { "резервний вихід ${abs(it).roundToInt()} Вт" } ?: "живлення від батареї"
                add("light_lost", JournalCategory.LIGHT, "Світло зникло: $watts$socText", backup)
                m = m.copy(outageStartedAtMillis = nowMillis)
            } else {
                val lasted = m.outageStartedAtMillis?.let { " після ${durationText((nowMillis - it) / 1000)}" }.orEmpty()
                add("light_returned", JournalCategory.LIGHT, "Світло повернулось$lasted$socText")
                m = m.copy(outageStartedAtMillis = null)
            }
        }
        if (grid != null) m = m.copy(grid = grid)

        val direction = obs.direction.takeIf { it != BatteryDirection.UNKNOWN }
        val prevDirection = m.direction
        if (direction != null && prevDirection != null && direction != prevDirection) {
            if (direction == BatteryDirection.CHARGING) {
                add("charging_started", JournalCategory.BATTERY,
                    "Почалось заряджання${battery?.let { " ≈ ${abs(it).roundToInt()} Вт (оцінка)" }.orEmpty()}$socText", battery)
            } else if (prevDirection == BatteryDirection.CHARGING && !(soc != null && soc >= FULL_SOC)) {
                add("charging_stopped", JournalCategory.BATTERY, "Заряджання зупинилось$socText")
            }
        }
        if (soc != null) {
            if (soc < 95) m = m.copy(fullSent = false)
            val rising = prevDirection == BatteryDirection.CHARGING || (m.soc != null && m.soc!! < FULL_SOC)
            if (soc >= FULL_SOC && !m.fullSent && rising) {
                add("charged_full", JournalCategory.BATTERY, "Батарею заряджено: $soc%", soc.toDouble())
                m = m.copy(fullSent = true)
            }
        }
        if (direction != null) m = m.copy(direction = direction)

        val threshold = clampThreshold(dischargeThresholdW).toDouble()
        val discharge = maxOf(
            if (backup != null && m.grid == GridState.OFF) abs(backup) else 0.0,
            if (battery != null && direction == BatteryDirection.DISCHARGING) abs(battery) else 0.0,
        )
        if (discharge > threshold) {
            if (!m.highDischargeSent) {
                add("anomaly_high_discharge", JournalCategory.ANOMALY,
                    "Розрядка ${discharge.roundToLong()} Вт перевищує поріг ${threshold.roundToLong()} Вт$socText", discharge)
                m = m.copy(highDischargeSent = true)
            }
        } else if (discharge < threshold * REARM_FACTOR) {
            m = m.copy(highDischargeSent = false)
        }
        val onGrid = grid == GridState.ON && direction == BatteryDirection.DISCHARGING && battery != null &&
            abs(battery) > DISCHARGE_ON_GRID_W && (backup == null || abs(backup) < 5.0)
        if (onGrid && !m.onGridSent) {
            add("anomaly_discharge_on_grid", JournalCategory.ANOMALY,
                "Розряд ≈ ${abs(battery!!).roundToInt()} Вт при наявності мережі, резервний вихід не живить навантаження — " +
                    "можливий конфлікт з ДБЖ/інвертором", abs(battery))
            m = m.copy(onGridSent = true)
        } else if (!onGrid && direction != BatteryDirection.DISCHARGING) {
            m = m.copy(onGridSent = false)
        }
        obs.temperatureC?.takeIf(Double::isFinite)?.let { t ->
            if (t > TEMP_HIGH && !m.tempSent) {
                add("anomaly_temperature", JournalCategory.ANOMALY,
                    String.format(Locale.US, "Висока температура батареї: %.1f °C (поріг %.0f °C)", t, TEMP_HIGH), t)
                m = m.copy(tempSent = true)
            } else if (t < TEMP_REARM) {
                m = m.copy(tempSent = false)
            }
        }
        if (soc != null) m = m.copy(soc = soc)
        return events to m
    }

    fun durationText(seconds: Long): String {
        val minutes = ((seconds + 30) / 60).coerceAtLeast(0)
        val hours = minutes / 60
        return if (hours > 0) "$hours год ${minutes % 60} хв" else "$minutes хв"
    }
}

/** «Поділитись»: plain text or CSV (time;category;kind;value;text). Only timestamps, values and texts — no addresses. */
object JournalExport {
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun time(millis: Long): String = Instant.ofEpochMilli(millis).atZone(kyiv).format(stamp)

    fun text(events: List<JournalEvent>): String =
        events.joinToString("\n") { "${time(it.tsMillis)} | ${it.category.title} | ${it.text}" }

    fun csv(events: List<JournalEvent>): String = buildString {
        append("time;category;kind;value;text\n")
        events.forEach { e ->
            append(listOf(time(e.tsMillis), e.category.id, e.kind, e.value?.toString().orEmpty(), e.text).joinToString(";") { cell(it) })
            append('\n')
        }
    }

    private fun cell(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == ',' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
