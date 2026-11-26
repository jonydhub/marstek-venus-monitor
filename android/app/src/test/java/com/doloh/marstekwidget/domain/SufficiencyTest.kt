package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Same cases as server/tests/test_sufficiency.py (server/sufficiency.py). */
class SufficiencyTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private fun ts(h: Int, m: Int = 0, day: Int = 26) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, kyiv).toEpochSecond().toDouble()
    private fun suff(
        now: Double, light: String, stored: Double = 4014.0, load: Double? = null, charge: Double? = null,
        currentEnd: Double? = null, nextStart: Double? = null, nextEnd: Double? = null,
    ) = Sufficiency.compute(now, light, stored, 5120.0, 11.0, load, charge, currentEnd, nextStart, nextEnd)
    private fun String?.c() = this?.replace('\u00A0', ' ')
    private fun Sufficiency.Result.v() = verdict?.let { it.ok to it.text.c() }

    @Test fun outageEnough() {
        val r = suff(ts(17), "off", load = 975.0, currentEnd = ts(20))
        assertEquals(true to "Вистачить до світла о 20:00 · запас ≈ 32 хв", r.v())
        assertNull(r.advice)
    }

    @Test fun outageShort() = assertEquals(false to "Не вистачить ≈ 42 хв: заряду до 19:18, світло о 20:00",
        suff(ts(17), "off", load = 1500.0, currentEnd = ts(20)).v())

    @Test fun outageEndsTomorrow() = assertEquals("Вистачить до світла завтра о 02:00 · запас ≈ 7 год 30 хв",
        suff(ts(22), "off", load = 300.0, currentEnd = ts(2, day = 27)).v()?.second)

    @Test fun outageWithoutEndOrLoad() {
        assertNull(suff(ts(17), "off", load = 975.0).verdict)
        assertNull(suff(ts(17), "off", currentEnd = ts(20)).verdict)
    }

    @Test fun idleShowsNothing() {
        assertNull(suff(ts(18), "on", stored = 5120.0, load = 494.0, nextStart = ts(21, day = 27), nextEnd = ts(0, day = 28)).verdict)
        val r = suff(ts(15), "on", stored = 2000.0, load = 1000.0, nextStart = ts(20))
        assertNull(r.verdict)
        assertNull(r.advice)
    }

    @Test fun chargingFullInTime() {
        val r = suff(ts(15), "on", stored = 4000.0, charge = 1194.0, nextStart = ts(16), nextEnd = ts(20))
        assertEquals(true to "Встигне зарядитися: 100 % о 15:56, відключення о 16:00", r.v())
        assertNull(r.advice)
    }

    @Test fun chargingFullBeforeTomorrowsOutage() = assertEquals("Встигне зарядитися: 100 % о 20:36, відключення завтра о 21:00",
        suff(ts(18), "on", stored = 2000.0, charge = 1200.0, nextStart = ts(21, day = 27)).v()?.second)

    @Test fun notFullEvenAtMax() {
        val r = suff(ts(15), "on", stored = 2000.0, charge = 1200.0, nextStart = ts(15, 30), nextEnd = ts(19, 30))
        assertEquals(false to "Не встигне зарядитися: до відключення о 15:30 буде ≈ 51 %", r.v())
        assertEquals("На 2,5 кВт — до 63 %: збільште потужність заряду в застосунку Marstek.", r.advice.c())
    }

    @Test fun fullAtMax() {
        val r = suff(ts(15), "on", stored = 3000.0, charge = 1000.0, nextStart = ts(17), nextEnd = ts(19))
        assertEquals("Не встигне зарядитися: до відключення о 17:00 буде ≈ 98 %", r.v()?.second)
        assertEquals("На 2,5 кВт — 100 % о 15:51: збільште потужність заряду в застосунку Marstek.", r.advice.c())
    }

    @Test fun almostFullAtMaxIsNot100() {
        val r = suff(ts(15), "on", stored = 2600.0, charge = 1194.0, nextStart = ts(16), nextEnd = ts(20))
        assertEquals("Не встигне зарядитися: до відключення о 16:00 буде ≈ 74 %", r.v()?.second)
        assertEquals("На 2,5 кВт — до 99 %: збільште потужність заряду в застосунку Marstek.", r.advice.c())
    }

    @Test fun noAdviceAtFullPowerNothingWithoutOutageOrWhenFull() {
        val r = suff(ts(15), "on", stored = 1000.0, charge = 2450.0, nextStart = ts(15, 30))
        assertEquals("Не встигне зарядитися: до відключення о 15:30 буде ≈ 43 %", r.v()?.second)
        assertNull(r.advice)
        assertNull(suff(ts(15), "on", stored = 1000.0, charge = 1200.0).verdict)
        assertNull(suff(ts(15), "on", stored = 5080.0, charge = 100.0, nextStart = ts(16)).verdict)
    }
}
