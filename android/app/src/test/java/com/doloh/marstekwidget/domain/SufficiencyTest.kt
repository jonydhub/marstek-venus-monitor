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

    @Test fun nextOutageEnough() = assertEquals(true to "На відключення 20:00–00:00 вистачить · лишиться ≈ 43 %",
        suff(ts(15), "on", load = 457.0, nextStart = ts(20), nextEnd = ts(0, day = 27)).v())

    @Test fun nextOutageTomorrowNamesTheDay() = assertEquals("На відключення завтра 21:00–00:00 вистачить · лишиться ≈ 52 %",
        suff(ts(18), "on", load = 457.0, nextStart = ts(21, day = 27), nextEnd = ts(0, day = 28)).v()?.second)

    @Test fun nextOutageShort() = assertEquals(false to "На відключення 20:00–00:00 не вистачить ≈ 33 хв",
        suff(ts(15), "on", load = 1000.0, nextStart = ts(20), nextEnd = ts(0, day = 27)).v())

    @Test fun chargingCountsUntilTheOutage() {
        val r = suff(ts(15), "on", stored = 4000.0, load = 1000.0, charge = 1194.0, nextStart = ts(16), nextEnd = ts(20))
        assertEquals(true to "На відключення 16:00–20:00 вистачить · лишиться ≈ 22 %", r.v())
        assertNull(r.advice)
    }

    @Test fun adviceNotFullEvenAtMax() = assertEquals(
        "До відключення зарядиться лише до 51 %. На 2,5 кВт — до 63 %: збільште потужність заряду в застосунку Marstek.",
        suff(ts(15), "on", stored = 2000.0, charge = 1200.0, nextStart = ts(15, 30), nextEnd = ts(19, 30)).advice.c())

    @Test fun adviceReachesFullAtMax() = assertEquals(
        "До відключення зарядиться лише до 98 %. На 2,5 кВт — 100 % о 15:51: збільште потужність заряду в застосунку Marstek.",
        suff(ts(15), "on", stored = 3000.0, charge = 1000.0, nextStart = ts(17), nextEnd = ts(19)).advice.c())

    @Test fun adviceAlmostFullIsNot100() = assertEquals(
        "До відключення зарядиться лише до 74 %. На 2,5 кВт — до 99 %: збільште потужність заряду в застосунку Marstek.",
        suff(ts(15), "on", stored = 2600.0, charge = 1194.0, nextStart = ts(16), nextEnd = ts(20)).advice.c())

    @Test fun noAdvice() {
        assertNull(suff(ts(15), "on", stored = 4500.0, charge = 1200.0, nextStart = ts(17)).advice)
        assertNull(suff(ts(15), "on", stored = 1000.0, charge = 2450.0, nextStart = ts(15, 30)).advice)
        assertNull(suff(ts(15), "on", stored = 1000.0, nextStart = ts(15, 30)).advice)
        assertNull(suff(ts(15), "on", stored = 1000.0, charge = 1200.0).advice)
    }
}
