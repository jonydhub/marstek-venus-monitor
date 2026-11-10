package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetSummaryResolverTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val now = ZonedDateTime.of(2026, 10, 17, 19, 0, 0, 0, kyiv)

    private val outageOff = JSONObject("""
        {"available":true,"group":"1.1","grid_state":"off",
         "current_outage":{"kind":"schedule","start":"2026-10-17T18:00:00+03:00","end":"2026-10-17T22:00:00+03:00","duration_seconds":14400,"remaining_seconds":10800},
         "schedule_published":true,
         "autonomy":{"load_w":420.0,"load_basis":"measured_discharge","runtime_seconds":12000,"outage_seconds":10800,"covers_next_outage":true}}
    """.trimIndent()).toString()

    private val outageOn = JSONObject("""
        {"available":true,"group":"1.1","grid_state":"on","current_outage":null,
         "next_outage":{"kind":"schedule","start":"2026-10-17T20:00:00+03:00","end":"2026-10-17T22:00:00+03:00","duration_seconds":7200},
         "schedule_published":true,
         "autonomy":{"load_w":300.0,"load_basis":"measured_average_24h","runtime_seconds":28800,"outage_seconds":7200,"covers_next_outage":true}}
    """.trimIndent()).toString()

    private fun snapshot(
        direction: BatteryDirection,
        power: Double? = null,
        eta: Long? = null,
        outage: String? = null,
        offgrid: Double? = null,
        temp: Double? = 24.5,
        insights: String? = null,
    ) = BatterySnapshot(
        socPercent = 64, batteryCapacityWh = 3277.0, ratedCapacityWh = 5120.0, temperatureCelsius = temp,
        batteryPowerWatts = power, direction = direction, etaSeconds = eta, collectedAt = null,
        offgridPowerWatts = offgrid, powerOutageJson = outage, insightsJson = insights,
    )

    @Test
    fun outageShowsBackupOutputReturnTimeOneRuntimeWithVerdictAndTemperature() {
        val s = WidgetSummaryResolver.resolve(
            snapshot(BatteryDirection.DISCHARGING, power = -430.0, eta = 4 * 3600L + 10 * 60, outage = outageOff, offgrid = 420.0),
            now,
        )
        assertEquals(SummaryMode.OUTAGE, s.mode)
        assertEquals(GridState.OFF, s.lightState)
        assertEquals("Світла нема · вихід 420 W", s.primary)
        assertEquals("Розряджається · резервний вихід 420 W", s.batteryState)
        // Autonomy runtime wins over the battery ETA: one number (trailing), verdict in the middle line.
        assertEquals("Запасу вистачить", s.secondary)
        assertEquals("до 22:00", s.trailingPrimary)
        assertEquals("0% · 3 год 20 хв · 24,5 °C", s.trailingSecondary)
        val kinds = s.lines.map { it.kind }
        // No backup-output line (already in the headline) and no second runtime (ETA) line.
        assertEquals(listOf(SummaryLineKind.RETURN_TIME, SummaryLineKind.RESERVE, SummaryLineKind.TEMPERATURE), kinds)
        assertEquals("Повернення о 22:00", s.lines[0].text)
        assertEquals(SummaryTone.GOOD, s.lines[1].tone)
        assertEquals("Запас ≈ 3 год 20 хв · вистачить", s.lines[1].text)
        assertEquals("Температура 24,5 °C", s.lines[2].text)
        assertFalse(s.lines.any { it.text.contains("Резервний вихід") })
    }

    @Test
    fun outageFallsBackToBatteryEtaWhenAutonomyRuntimeMissingAndComputesVerdict() {
        val noRuntime = """{"available":true,"group":"1.1","grid_state":"off",
            "current_outage":{"kind":"schedule","start":"2026-10-17T18:00:00+03:00","end":"2026-10-17T22:00:00+03:00"},
            "schedule_published":true,"autonomy":{}}"""
        val enough = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.DISCHARGING, eta = 4 * 3600L, outage = noRuntime, offgrid = 400.0), now)
        assertEquals("Запасу вистачить", enough.secondary)
        assertEquals("Запас ≈ 4 год 0 хв · вистачить", enough.lines.first { it.kind == SummaryLineKind.RESERVE }.text)
        assertEquals("0% · 4 год 0 хв · 24,5 °C", enough.trailingSecondary)
        val short = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.DISCHARGING, eta = 2 * 3600L, outage = noRuntime, offgrid = 400.0), now)
        assertEquals("Запасу не вистачить", short.secondary)
        assertEquals("Запас ≈ 2 год 0 хв · не вистачить", short.lines.first { it.kind == SummaryLineKind.RESERVE }.text)
        assertEquals(SummaryTone.CRITICAL, short.lines.first { it.kind == SummaryLineKind.RESERVE }.tone)
    }

    @Test
    fun outagePrefersInsightsBackupOutputAndFlagsShortReserve() {
        val shortReserve = outageOff.replace("\"covers_next_outage\":true", "\"covers_next_outage\":false")
        val s = WidgetSummaryResolver.resolve(
            snapshot(BatteryDirection.DISCHARGING, outage = shortReserve, offgrid = 100.0,
                insights = """{"backup_output_w":515.0,"soc_history":[]}"""),
            now,
        )
        assertEquals("Світла нема · вихід 515 W", s.primary)
        assertEquals("Запасу не вистачить", s.secondary)
        assertEquals("Запас ≈ 3 год 20 хв · не вистачить", s.lines.first { it.kind == SummaryLineKind.RESERVE }.text)
        assertEquals(SummaryTone.CRITICAL, s.lines.first { it.kind == SummaryLineKind.RESERVE }.tone)
        assertEquals("0% · 3 год 20 хв · 24,5 °C", s.trailingSecondary)
    }

    @Test
    fun outageWithoutEstimatesSaysSoInsteadOfInventing() {
        val bare = """{"available":true,"group":"1.1","grid_state":"off","schedule_published":false,"autonomy":{}}"""
        val s = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.IDLE, outage = bare, temp = null), now)
        assertEquals("Світла нема", s.primary)
        assertEquals("Запас: оцінюю розряд…", s.secondary)
        assertEquals("до —", s.trailingPrimary)
        assertEquals("0% · —", s.trailingSecondary)
        assertEquals("Час повернення невідомий", s.lines.first { it.kind == SummaryLineKind.RETURN_TIME }.text)
        assertFalse(s.lines.any { it.kind == SummaryLineKind.TEMPERATURE })
    }

    @Test
    fun chargingShowsTimeToFullAndEstimatedPower() {
        val s = WidgetSummaryResolver.resolve(
            snapshot(BatteryDirection.CHARGING, power = 800.0, eta = 80 * 60L, outage = outageOn),
            now,
        )
        assertEquals(SummaryMode.CHARGING, s.mode)
        assertEquals(GridState.ON, s.lightState)
        assertEquals("Заряд ≈ 0,80 кВт", s.primary)
        assertEquals("Заряджається · ≈ 0,80 кВт", s.batteryState)
        assertEquals("1 год 20 хв", s.trailingPrimary)
        assertEquals("до 100% · 24,5 °C", s.trailingSecondary)
        assertEquals("Світло є · відкл. 20:00", s.secondary)
        assertEquals(
            listOf(SummaryLineKind.ETA, SummaryLineKind.LIGHT, SummaryLineKind.RESERVE, SummaryLineKind.TEMPERATURE),
            s.lines.map { it.kind },
        )
        // Power is the headline; it must not be repeated as the first detail line.
        assertFalse(s.lines.any { it.text.startsWith("Заряд ≈") })
        assertEquals("До 100% ≈ 1 год 20 хв · оцінка", s.lines[0].text)
        assertEquals("Світло є · Відключення 20:00–22:00", s.lines[1].text)
    }

    @Test
    fun dischargingWithGridOnTargetsZero() {
        val s = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.DISCHARGING, power = -300.0, eta = 5 * 3600L), now)
        assertEquals(SummaryMode.DISCHARGING, s.mode)
        assertEquals(GridState.UNKNOWN, s.lightState)
        assertEquals("Розряд ≈ 0,30 кВт", s.primary)
        assertEquals("5 год 0 хв", s.trailingPrimary)
        assertEquals("до 0% · 24,5 °C", s.trailingSecondary)
        assertNull(s.secondary)
    }

    @Test
    fun idleShowsLightNextOutageReserveAndTemperatureWithoutZeroPower() {
        val s = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.IDLE, power = 0.0, outage = outageOn), now)
        assertEquals(SummaryMode.IDLE, s.mode)
        assertEquals("Світло є · очікування", s.primary)
        assertEquals("Очікування", s.batteryState)
        assertEquals("Відключення 20:00–22:00", s.secondary)
        assertEquals("≈ 8 год 0 хв", s.trailingPrimary)
        assertEquals("запас · 24,5 °C", s.trailingSecondary)
        assertFalse(s.lines.any { it.kind == SummaryLineKind.POWER })
        assertFalse(s.primary.contains("0,00"))
        assertEquals(listOf(SummaryLineKind.LIGHT, SummaryLineKind.RESERVE, SummaryLineKind.TEMPERATURE), s.lines.map { it.kind })
    }

    @Test
    fun idleWithoutOutageInfoStillUseful() {
        val s = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.IDLE), now)
        assertEquals("Очікування", s.primary)
        assertEquals("24,5 °C", s.trailingPrimary)
        assertNull(s.trailingSecondary)
        assertTrue(s.lines.any { it.kind == SummaryLineKind.TEMPERATURE })
    }

    @Test
    fun unknownDirectionNeverShowsEta() {
        val s = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.UNKNOWN, eta = 100L), now)
        assertEquals(SummaryMode.UNKNOWN, s.mode)
        assertEquals("Стан невідомий", s.primary)
        assertFalse(s.lines.any { it.kind == SummaryLineKind.ETA })
    }

    @Test
    fun temperatureFallsBackToInsights() {
        val s = WidgetSummaryResolver.resolve(
            snapshot(BatteryDirection.IDLE, temp = null, insights = """{"temperature_c":19.0,"soc_history":[]}"""),
            now,
        )
        assertEquals("19,0 °C", s.trailingPrimary)
    }

    @Test
    fun chargingWithoutEtaShowsDash() {
        val s = WidgetSummaryResolver.resolve(snapshot(BatteryDirection.CHARGING, power = null, eta = null), now)
        assertEquals("Заряджається", s.primary)
        assertEquals("—", s.trailingPrimary)
        assertEquals("До 100%: —", s.lines.first { it.kind == SummaryLineKind.ETA }.text)
    }
}
