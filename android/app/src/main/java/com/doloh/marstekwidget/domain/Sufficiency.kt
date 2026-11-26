package com.doloh.marstekwidget.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * «Чи вистачить» + charge advice — port of server/sufficiency.py (keep in sync, same cases in
 * server/tests/test_sufficiency.py and SufficiencyTest). Times are epoch seconds.
 */
object Sufficiency {
    const val MAX_CHARGE_W = 2500.0
    private const val ADVICE_MARGIN_W = 200.0
    private const val NBSP = '\u00A0'
    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")

    data class Verdict(val ok: Boolean, val text: String)
    data class Result(val verdict: Verdict?, val advice: String?)

    private fun span(seconds: Double): String {
        val m = (max(0.0, seconds) / 60).roundToLong()
        val h = m / 60
        val r = m % 60
        return when {
            h > 0 && r > 0 -> "$h${NBSP}год $r${NBSP}хв"
            h > 0 -> "$h${NBSP}год"
            else -> "$r${NBSP}хв"
        }
    }

    private fun local(ts: Double): ZonedDateTime =
        Instant.ofEpochSecond(((ts / 60).roundToLong()) * 60).atZone(kyiv)  // nearest minute

    private fun hm(ts: Double) = "%02d:%02d".format(local(ts).hour, local(ts).minute)

    /** «» today, «завтра » tomorrow, «29.11 » later — prefix of a time range. */
    private fun day(ts: Double, now: Double): String {
        val d = local(ts).toLocalDate()
        val today = Instant.ofEpochSecond(now.toLong()).atZone(kyiv).toLocalDate()
        return when (d) {
            today -> ""
            today.plusDays(1) -> "завтра "
            else -> "%02d.%02d ".format(d.dayOfMonth, d.monthValue)
        }
    }

    /** «о 20:00», «завтра о 02:00», «о 09:00, 29.11». */
    private fun clock(ts: Double, now: Double): String {
        val t = local(ts)
        val today = Instant.ofEpochSecond(now.toLong()).atZone(kyiv).toLocalDate()
        val time = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) {
            today -> "о $time"
            today.plusDays(1) -> "завтра о $time"
            else -> "о $time, %02d.%02d".format(t.dayOfMonth, t.monthValue)
        }
    }

    fun compute(
        now: Double, light: String, storedWh: Double?, ratedWh: Double? = null, floorPct: Double? = null,
        loadW: Double? = null, chargeW: Double? = null, currentEnd: Double? = null, nextStart: Double? = null,
        nextEnd: Double? = null,
    ): Result {
        val stored = storedWh?.takeIf { it.isFinite() } ?: return Result(null, null)
        val rated = ratedWh?.takeIf { it.isFinite() && it > 0 }
        val load = loadW?.takeIf { it.isFinite() && it > 0 }
        val charge = chargeW?.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val floorWh = if (rated != null && floorPct != null && floorPct > 0) rated * floorPct / 100 else 0.0

        if (light == "off" && currentEnd != null && currentEnd > now && load != null) {
            val runtime = max(0.0, stored - floorWh) / load * 3600
            val remaining = currentEnd - now
            val verdict = if (runtime >= remaining) {
                val margin = runtime - remaining
                val tail = if (margin >= 60) " · запас ≈$NBSP${span(margin)}" else " — впритул"
                Verdict(true, "Вистачить до світла ${clock(currentEnd, now)}$tail")
            } else {
                Verdict(false, "Не вистачить ≈$NBSP${span(remaining - runtime)}: заряду до ${hm(now + runtime)}, світло ${clock(currentEnd, now)}")
            }
            return Result(verdict, null)
        }
        // Light on: only while charging — will it be full before the next outage? Idle / no outage ahead → nothing.
        if (light != "on" || nextStart == null || nextStart <= now || charge <= 0 || rated == null || stored >= rated * 0.99) {
            return Result(null, null)
        }
        val t = nextStart - now
        val whenText = clock(nextStart, now)
        val fullIn = (rated - stored) / charge * 3600
        if (fullIn <= t) return Result(Verdict(true, "Встигне зарядитися: 100$NBSP% ${clock(now + fullIn, now)}, відключення $whenText"), null)
        val at = min(99, ((stored + charge * t / 3600) / rated * 100).roundToInt())  // not full → never «100 %»
        val verdict = Verdict(false, "Не встигне зарядитися: до відключення $whenText буде ≈$NBSP$at$NBSP%")
        if (charge >= MAX_CHARGE_W - ADVICE_MARGIN_W) return Result(verdict, null)
        val fullAtMax = (rated - stored) / MAX_CHARGE_W * 3600
        val atMax = if (fullAtMax <= t) "100$NBSP% ${clock(now + fullAtMax, now)}"
        else "до ${min(99, ((stored + MAX_CHARGE_W * t / 3600) / rated * 100).roundToInt())}$NBSP%"
        return Result(verdict, "На 2,5${NBSP}кВт — $atMax: збільште потужність заряду в застосунку Marstek.")
    }

    private fun seconds(iso: String?): Double? = iso?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().epochSecond.toDouble() }.getOrNull()
    }

    /** From the app's snapshot (server or direct mode). */
    fun of(snapshot: BatterySnapshot?, info: PowerOutageInfo?, linkLost: Boolean, nowMillis: Long = System.currentTimeMillis()): Result {
        snapshot ?: return Result(null, null)
        val light = when {
            linkLost -> "unknown"
            info?.gridState == GridState.OFF -> "off"
            info?.gridState == GridState.ON -> "on"
            else -> "unknown"
        }
        val charging = snapshot.direction == BatteryDirection.CHARGING
        return compute(
            now = nowMillis / 1000.0, light = light,
            storedWh = info?.storedWh ?: snapshot.batteryCapacityWh, ratedWh = snapshot.ratedCapacityWh,
            floorPct = info?.floorPct, loadW = info?.loadWatts,
            chargeW = if (charging) snapshot.batteryPowerWatts?.let { kotlin.math.abs(it) } else null,
            currentEnd = seconds(info?.currentEnd), nextStart = seconds(info?.nextStart), nextEnd = seconds(info?.nextEnd),
        )
    }
}
