package com.doloh.marstekwidget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlertEvaluatorTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val base = ZonedDateTime.of(2026, 10, 17, 19, 0, 0, 0, kyiv)
    private val all = AlertSettings()
    private fun at(minutes: Long) = base.plusMinutes(minutes)
    private fun iso(t: ZonedDateTime) = t.toOffsetDateTime().toString()

    /** Builds a snapshot "collected" at [now] with an optional outage block. */
    private fun snap(
        now: ZonedDateTime = base,
        grid: String? = "on",
        soc: Int? = 80,
        capacityWh: Double? = soc?.let { it * 51.2 },
        direction: BatteryDirection = BatteryDirection.IDLE,
        power: Double? = null,
        eta: Long? = null,
        offgrid: Double? = null,
        temp: Double? = 24.0,
        nextStart: ZonedDateTime? = null,
        nextEnd: ZonedDateTime? = nextStart?.plusHours(4),
        currentEnd: ZonedDateTime? = null,
        runtime: Long? = null,
        covers: Boolean? = null,
        loadW: Double? = null,
        published: Boolean = true,
        avg24h: Double? = null,
        collectedAt: ZonedDateTime? = now,
    ): BatterySnapshot {
        val outage = grid?.let {
            val next = nextStart?.let { s ->
                """{"kind":"schedule","start":"${iso(s)}","end":"${iso(nextEnd!!)}","duration_seconds":${java.time.Duration.between(s, nextEnd).seconds}}"""
            } ?: "null"
            val current = currentEnd?.let { e -> """{"kind":"schedule","start":"${iso(now.minusHours(1))}","end":"${iso(e)}"}""" } ?: "null"
            val autonomy = buildList {
                runtime?.let { add("\"runtime_seconds\":$it") }
                covers?.let { add("\"covers_next_outage\":$it") }
                loadW?.let { add("\"load_w\":$it") }
            }.joinToString(",")
            """{"available":true,"group":"1.1","grid_state":"$grid","current_outage":$current,"next_outage":$next,
               "schedule_published":$published,"autonomy":{$autonomy}}"""
        }
        val insights = avg24h?.let { """{"backup_avg_w_24h":$it,"soc_history":[]}""" }
        return BatterySnapshot(
            socPercent = soc, batteryCapacityWh = capacityWh, ratedCapacityWh = 5120.0, temperatureCelsius = temp,
            batteryPowerWatts = power, direction = direction, etaSeconds = eta,
            collectedAt = collectedAt?.toInstant()?.toString(), offgridPowerWatts = offgrid,
            powerOutageJson = outage, insightsJson = insights,
        )
    }

    private fun eval(memory: AlertMemory?, snapshot: BatterySnapshot?, now: ZonedDateTime = base, settings: AlertSettings = all) =
        AlertEvaluator.evaluate(memory, snapshot, now.toInstant().toEpochMilli(), settings)

    private fun AlertEvaluation.ids() = notifications.map { it.id }
    private fun AlertEvaluation.one(id: AlertId) = notifications.single { it.id == id }

    // ---- light lost / returned (migrated from PowerTransitionDetector) ----

    @Test
    fun firstObservationDoesNotClaimALightTransition() {
        val r = eval(null, snap(grid = "off"))
        assertFalse(AlertId.LIGHT_LOST in r.ids())
        assertEquals(GridState.OFF, r.memory.gridState)
    }

    @Test
    fun lightLostFiresOnceWithBackupSocEtaAndReturnTime() {
        val on = eval(null, snap()).memory
        val lost = eval(on, snap(grid = "off", soc = 64, direction = BatteryDirection.DISCHARGING, eta = 4 * 3600L + 600,
            offgrid = 430.0, currentEnd = at(180)))
        val alert = lost.one(AlertId.LIGHT_LOST)
        assertEquals(AlertSeverity.WARN, alert.severity)
        assertEquals("Світло зникло", alert.title)
        assertTrue(alert.text, alert.text.contains("Резервний вихід 430 W"))
        assertTrue(alert.text, alert.text.contains("заряд 64%"))
        assertTrue(alert.text, alert.text.contains("вистачить на 4 год 10 хв (оцінка)"))
        assertTrue(alert.text, alert.text.contains("повернення о 22:00"))
        val still = eval(lost.memory, snap(now = at(15), grid = "off", soc = 62), at(15))
        assertFalse(AlertId.LIGHT_LOST in still.ids())
    }

    @Test
    fun lightReturnedReportsObservedDurationEnergyUsedAndChargingState() {
        val on = eval(null, snap()).memory
        val lost = eval(on, snap(grid = "off", soc = 64)).memory
        val back = eval(lost, snap(now = at(135), grid = "on", soc = 40, direction = BatteryDirection.CHARGING, power = 800.0), at(135))
        val alert = back.one(AlertId.LIGHT_RETURNED)
        assertEquals(AlertSeverity.INFO, alert.severity)
        assertTrue(alert.text, alert.text.contains("Без світла ≈ 2 год 15 хв"))
        // (64 − 40) % × 5.12 kWh = 1.23 kWh
        assertTrue(alert.text, alert.text.contains("витрачено ≈ 1,23 kWh (64% → 40%)"))
        assertTrue(alert.text, alert.text.contains("заряджання почалось"))

        val lost2 = eval(back.memory, snap(now = at(200), grid = "off", soc = 60), at(200)).memory
        val back2 = eval(lost2, snap(now = at(230), grid = "on", soc = 58), at(230))
        assertTrue(back2.one(AlertId.LIGHT_RETURNED).text.contains("заряджання ще не почалось"))
    }

    @Test
    fun unknownGridKeepsLastKnownStateSoNoFalseTransitions() {
        val on = eval(null, snap()).memory
        val unknown = eval(on, snap(grid = null))
        assertTrue(unknown.notifications.none { it.id == AlertId.LIGHT_LOST || it.id == AlertId.LIGHT_RETURNED })
        assertEquals(GridState.ON, unknown.memory.gridState)
        assertTrue(eval(unknown.memory, snap()).notifications.none { it.id == AlertId.LIGHT_RETURNED })
    }

    // ---- reserve during the current outage (migrated) ----

    @Test
    fun reserveShortCurrentFiresOncePerOutageAndRearmsWhenLightReturns() {
        val on = eval(null, snap()).memory
        val first = eval(on, snap(grid = "off", runtime = 7200, covers = false, currentEnd = at(180)))
        val alert = first.one(AlertId.RESERVE_SHORT_CURRENT)
        assertEquals(AlertSeverity.CRITICAL, alert.severity)
        assertTrue(alert.text, alert.text.contains("Запас ≈ 2 год"))
        assertTrue(alert.text, alert.text.contains("о 22:00"))
        assertTrue(AlertId.LIGHT_LOST in first.ids())
        val again = eval(first.memory, snap(grid = "off", runtime = 7000, covers = false))
        assertFalse(AlertId.RESERVE_SHORT_CURRENT in again.ids())
        assertTrue(again.active.any { it.id == AlertId.RESERVE_SHORT_CURRENT })
        val back = eval(again.memory, snap())
        val next = eval(back.memory, snap(grid = "off", runtime = 7200, covers = false))
        assertTrue(AlertId.RESERVE_SHORT_CURRENT in next.ids())
    }

    @Test
    fun reserveThatCoversOutageDoesNotAlert() {
        val on = eval(null, snap()).memory
        val r = eval(on, snap(grid = "off", runtime = 30000, covers = true))
        assertEquals(listOf(AlertId.LIGHT_LOST), r.ids())
    }

    // ---- outage soon ----

    @Test
    fun outageSoonFiresWithinSixtyMinutesOncePerWindow() {
        val start = at(45)
        val far = eval(null, snap(nextStart = at(90), runtime = 28800, covers = true))
        assertFalse(AlertId.OUTAGE_SOON in far.ids())
        val soon = eval(far.memory, snap(soc = 84, nextStart = start, runtime = 28800, covers = true))
        val alert = soon.one(AlertId.OUTAGE_SOON)
        assertEquals("Відключення о 19:45 (черга 1.1)", alert.title)
        assertTrue(alert.text, alert.text.contains("заряд 84%"))
        assertTrue(alert.text, alert.text.contains("запас ≈ 8 год — вистачить"))
        assertEquals(AlertSeverity.INFO, alert.severity)
        val dup = eval(soon.memory, snap(now = at(15), soc = 84, nextStart = start, runtime = 28800, covers = true), at(15))
        assertFalse(AlertId.OUTAGE_SOON in dup.ids())
        assertTrue(dup.active.any { it.id == AlertId.OUTAGE_SOON })
        val nextWindow = eval(dup.memory, snap(now = at(15), nextStart = at(50), runtime = 3600, covers = false), at(15))
        val short = nextWindow.one(AlertId.OUTAGE_SOON)
        assertTrue(short.text, short.text.contains("— не вистачить"))
        assertEquals(AlertSeverity.WARN, short.severity)
    }

    @Test
    fun outageSoonWakeupIsScheduledSixtyMinutesBeforeStart() {
        val now = base.toInstant().toEpochMilli()
        assertEquals(10 * 60_000L, AlertEvaluator.outageSoonWakeupDelayMillis(iso(at(70)), now))
        assertNull(AlertEvaluator.outageSoonWakeupDelayMillis(iso(at(50)), now))
        assertNull(AlertEvaluator.outageSoonWakeupDelayMillis(iso(at(120)), now))
        assertNull(AlertEvaluator.outageSoonWakeupDelayMillis(null, now))
    }

    // ---- reserve before the next outage ----

    @Test
    fun reserveShortNextComputesNeededAndAvailableEnergy() {
        // 4 h outage at 400 W needs 1.60 kWh; battery holds 1.00 kWh → short by 0.60 kWh.
        val r = eval(null, snap(soc = 20, capacityWh = 1000.0, nextStart = at(180), nextEnd = at(420), runtime = 9000, covers = false, loadW = 400.0))
        val alert = r.one(AlertId.RESERVE_SHORT_NEXT)
        assertEquals("До відключення варто дозарядити", alert.title)
        assertTrue(alert.text, alert.text.contains("треба ≈ 1,60 kWh, є 1,00 kWh"))
        assertTrue(alert.text, alert.text.contains("бракує ≈ 0,60 kWh"))
        assertTrue(alert.text, alert.text.contains("22:00–02:00"))
        assertEquals(AlertSeverity.WARN, alert.severity)
        val dup = eval(r.memory, snap(soc = 21, capacityWh = 1050.0, nextStart = at(180), nextEnd = at(420), runtime = 9400, covers = false, loadW = 400.0))
        assertFalse(AlertId.RESERVE_SHORT_NEXT in dup.ids())
        val ok = eval(null, snap(soc = 90, capacityWh = 4600.0, nextStart = at(180), nextEnd = at(420), runtime = 41000, covers = true, loadW = 400.0))
        assertFalse(AlertId.RESERVE_SHORT_NEXT in ok.ids())
    }

    // ---- schedule published / changed ----

    @Test
    fun scheduleNewlyPublishedOrChangedNotifiesButProgressionDoesNot() {
        val first = eval(null, snap(published = false))
        assertTrue(first.notifications.isEmpty())
        val published = eval(first.memory, snap(nextStart = at(120), nextEnd = at(360)))
        val alert = published.one(AlertId.SCHEDULE_PUBLISHED)
        assertEquals("Графік оновлено (черга 1.1)", alert.title)
        assertTrue(alert.text, alert.text.contains("Відключення о 21:00"))
        val same = eval(published.memory, snap(nextStart = at(120), nextEnd = at(360)))
        assertFalse(AlertId.SCHEDULE_PUBLISHED in same.ids())
        val changed = eval(same.memory, snap(nextStart = at(180), nextEnd = at(360)))
        assertTrue(AlertId.SCHEDULE_PUBLISHED in changed.ids())
        // The window started: next_outage moving on to the following window is not a schedule change.
        val progressed = eval(changed.memory, snap(now = at(190), grid = "off", nextStart = at(600), nextEnd = at(840)), at(190))
        assertFalse(AlertId.SCHEDULE_PUBLISHED in progressed.ids())
        // A transient "unpublished" blip followed by the same schedule does not re-notify.
        val blip = eval(progressed.memory, snap(now = at(195), grid = "off", published = false), at(195))
        val again = eval(blip.memory, snap(now = at(200), grid = "off", nextStart = at(600), nextEnd = at(840)), at(200))
        assertFalse(AlertId.SCHEDULE_PUBLISHED in again.ids())
    }

    // ---- charging ----

    @Test
    fun chargingStartedReportsPowerAndEtaAndIgnoresFlicker() {
        val idle = eval(null, snap(soc = 46)).memory
        val r = eval(idle, snap(soc = 46, direction = BatteryDirection.CHARGING, power = 820.0, eta = 11700))
        val alert = r.one(AlertId.CHARGING_STARTED)
        assertTrue(alert.text, alert.text.contains("≈ 820 W (оцінка)"))
        assertTrue(alert.text, alert.text.contains("до 100% ≈ 3 год 15 хв"))
        val flicker = eval(r.memory, snap(now = at(15), soc = 47), at(15)).memory
        val restart = eval(flicker, snap(now = at(30), soc = 47, direction = BatteryDirection.CHARGING, power = 800.0), at(30))
        assertFalse(AlertId.CHARGING_STARTED in restart.ids())
        assertFalse(AlertId.CHARGING_STARTED in eval(null, snap(direction = BatteryDirection.CHARGING)).ids())
    }

    @Test
    fun chargedFullAtHundredOrChargingToIdleAboveNinetyEightWithRearm() {
        val charging = eval(null, snap(soc = 97, direction = BatteryDirection.CHARGING, power = 300.0)).memory
        val full = eval(charging, snap(soc = 100, direction = BatteryDirection.CHARGING, power = 50.0))
        assertEquals("Батарею заряджено", full.one(AlertId.CHARGED_FULL).title)
        assertFalse(AlertId.CHARGED_FULL in eval(full.memory, snap(soc = 100)).ids())
        val drained = eval(full.memory, snap(soc = 94)).memory
        val charging2 = eval(drained, snap(soc = 97, direction = BatteryDirection.CHARGING, power = 300.0)).memory
        val idleAt98 = eval(charging2, snap(soc = 98, direction = BatteryDirection.IDLE))
        assertTrue(AlertId.CHARGED_FULL in idleAt98.ids())
    }

    @Test
    fun notChargingAfterReturnNeedsTwoConsecutiveChecks() {
        val on = eval(null, snap(soc = 50)).memory
        val off = eval(on, snap(grid = "off", soc = 45)).memory
        val back = eval(off, snap(now = at(60), soc = 44), at(60))
        assertFalse(AlertId.NOT_CHARGING_AFTER_RETURN in back.ids())
        val check1 = eval(back.memory, snap(now = at(75), soc = 44), at(75))
        assertFalse(AlertId.NOT_CHARGING_AFTER_RETURN in check1.ids())
        val check2 = eval(check1.memory, snap(now = at(90), soc = 44), at(90))
        val alert = check2.one(AlertId.NOT_CHARGING_AFTER_RETURN)
        assertEquals(AlertSeverity.WARN, alert.severity)
        assertTrue(alert.text, alert.text.contains("Перевірте режим роботи в застосунку Marstek"))
        assertTrue(alert.text, alert.text.contains("44%"))
        assertFalse(AlertId.NOT_CHARGING_AFTER_RETURN in eval(check2.memory, snap(now = at(105), soc = 44), at(105)).ids())

        val chargingBack = eval(off, snap(now = at(60), soc = 44, direction = BatteryDirection.CHARGING, power = 900.0), at(60)).memory
        val c1 = eval(chargingBack, snap(now = at(75), soc = 46, direction = BatteryDirection.CHARGING, power = 900.0), at(75)).memory
        val c2 = eval(c1, snap(now = at(90), soc = 48, direction = BatteryDirection.CHARGING, power = 900.0), at(90))
        assertFalse(AlertId.NOT_CHARGING_AFTER_RETURN in c2.ids())
    }

    // ---- consumption ----

    @Test
    fun highLoadUsesMaxOfThresholdAndTwiceAverageWithRuntime() {
        val on = eval(null, snap()).memory
        val off = eval(on, snap(grid = "off", capacityWh = 1400.0, offgrid = 250.0, avg24h = 150.0))
        assertFalse("250 W is below max(200, 2×150)", AlertId.HIGH_LOAD in off.ids())
        val high = eval(off.memory, snap(grid = "off", capacityWh = 1400.0, offgrid = 350.0, avg24h = 150.0))
        val alert = high.one(AlertId.HIGH_LOAD)
        assertEquals("Споживання 350 W — запасу вистачить лише на 4 год", alert.text.substringBefore(" ("))
        assertTrue(alert.text, alert.text.contains("поріг 300 W"))
        assertFalse(AlertId.HIGH_LOAD in eval(high.memory, snap(grid = "off", capacityWh = 1400.0, offgrid = 260.0, avg24h = 150.0)).ids())
        val calm = eval(high.memory, snap(grid = "off", capacityWh = 1400.0, offgrid = 200.0, avg24h = 150.0)).memory
        assertTrue(AlertId.HIGH_LOAD in eval(calm, snap(grid = "off", capacityWh = 1400.0, offgrid = 400.0, avg24h = 150.0)).ids())
        val custom = AlertSettings(highLoadThresholdWatts = 500)
        assertFalse(AlertId.HIGH_LOAD in eval(off.memory, snap(grid = "off", offgrid = 450.0, avg24h = 150.0), settings = custom).ids())
        assertFalse("grid on: Venus bypasses, no alert", AlertId.HIGH_LOAD in eval(null, snap(offgrid = 900.0)).ids())
    }

    /** Real outage: 98 %, 5030 Wh stored, backup 1330 W, floor 11 % of 5120 Wh → 3 год 22 хв (not 3 год 47 хв to 0 %). */
    private fun outage1330(autonomy: String) = BatterySnapshot(
        socPercent = 98, batteryCapacityWh = 5030.0, ratedCapacityWh = 5120.0, temperatureCelsius = 24.0,
        batteryPowerWatts = -1330.0, direction = BatteryDirection.DISCHARGING, etaSeconds = 13_615,
        collectedAt = base.toInstant().toString(), offgridPowerWatts = 1330.0,
        powerOutageJson = """{"available":true,"group":"1.1","grid_state":"off","current_outage":null,"next_outage":null,
            "schedule_published":true,"autonomy":{$autonomy}}""", insightsJson = null,
    )

    @Test
    fun highLoadRuntimeMatchesTheHeroAboveTheDischargeFloor() {
        val on = eval(null, snap()).memory
        val server = outage1330(""""load_w":1330.0,"load_basis":"measured_backup_output","runtime_seconds":12091,
            "usable_wh":4467,"stored_wh":5030,"floor_pct":11""")
        val alert = eval(on, server).one(AlertId.HIGH_LOAD)
        assertEquals(12_091L, AlertEvaluator.unifiedRuntime(server))
        assertTrue(alert.text, alert.text.contains("запасу вистачить лише на 3 год 22 хв"))
        assertFalse("measured load: no «оцінка»", alert.text.contains("оцінка"))
        // Direct mode without a server runtime: the same floor rule from stored − rated × floor %.
        val local = outage1330(""""stored_wh":5030,"floor_pct":11""")
        assertEquals(12_091L, AlertEvaluator.runtimeAtLoad(local, 1330.0))
        assertTrue(eval(on, local).one(AlertId.HIGH_LOAD).text.contains("3 год 22 хв"))
        // Light lost with a measured load: runtime above the floor, no estimate mark.
        val lost = eval(on, server).one(AlertId.LIGHT_LOST)
        assertTrue(lost.text, lost.text.contains("вистачить на 3 год 22 хв") && !lost.text.contains("оцінка"))
    }

    // ---- SOC ----

    @Test
    fun socLowAndCriticalWithHysteresis() {
        val start = eval(null, snap(soc = 20))
        assertTrue(start.notifications.isEmpty())
        val low = eval(start.memory, snap(soc = 19))
        assertEquals(AlertSeverity.WARN, low.one(AlertId.SOC_LOW).severity)
        val wobble = eval(low.memory, snap(soc = 22))
        assertFalse(AlertId.SOC_LOW in eval(wobble.memory, snap(soc = 18)).ids())
        val critical = eval(wobble.memory, snap(soc = 9))
        assertEquals(listOf(AlertId.SOC_CRITICAL), critical.ids())
        assertEquals(AlertSeverity.CRITICAL, critical.one(AlertId.SOC_CRITICAL).severity)
        assertEquals(AlertId.SOC_CRITICAL, critical.active.first().id)
        assertFalse(AlertId.SOC_CRITICAL in eval(critical.memory, snap(soc = 12)).ids())
        val rearmed = eval(critical.memory, snap(soc = 25)).memory
        assertEquals(listOf(AlertId.SOC_CRITICAL), eval(rearmed, snap(soc = 8)).ids())
    }

    // ---- temperature ----

    @Test
    fun highTemperatureWithThreeDegreeHysteresis() {
        val hot = eval(null, snap(temp = 46.0))
        assertTrue(hot.one(AlertId.TEMPERATURE_HIGH).text.contains("46,0 °C"))
        val cooler = eval(hot.memory, snap(temp = 43.0))
        assertFalse(AlertId.TEMPERATURE_HIGH in eval(cooler.memory, snap(temp = 46.0)).ids())
        val cool = eval(cooler.memory, snap(temp = 41.5)).memory
        assertTrue(AlertId.TEMPERATURE_HIGH in eval(cool, snap(temp = 45.5)).ids())
    }

    @Test
    fun coldChargingOnlyWhileCharging() {
        assertFalse(AlertId.TEMPERATURE_COLD_CHARGING in eval(null, snap(temp = 3.0)).ids())
        val cold = eval(null, snap(temp = 3.0, direction = BatteryDirection.CHARGING, power = 500.0))
        assertEquals(AlertSeverity.WARN, cold.one(AlertId.TEMPERATURE_COLD_CHARGING).severity)
        val still = eval(cold.memory, snap(temp = 6.0, direction = BatteryDirection.CHARGING, power = 500.0))
        assertFalse(AlertId.TEMPERATURE_COLD_CHARGING in eval(still.memory, snap(temp = 4.0, direction = BatteryDirection.CHARGING, power = 500.0)).ids())
        val warm = eval(still.memory, snap(temp = 8.0, direction = BatteryDirection.CHARGING, power = 500.0)).memory
        assertTrue(AlertId.TEMPERATURE_COLD_CHARGING in eval(warm, snap(temp = 4.0, direction = BatteryDirection.CHARGING, power = 500.0)).ids())
    }

    // ---- stale data ----

    @Test
    fun dataStaleOncePerIncidentOnlyAfterFreshData() {
        assertFalse(AlertId.DATA_STALE in eval(null, snap(collectedAt = at(-40))).ids())
        val fresh = eval(null, snap()).memory
        val stale = eval(fresh, snap(collectedAt = base), at(40))
        val alert = stale.one(AlertId.DATA_STALE)
        assertTrue(alert.text, alert.text.contains("Останні дані о 19:00 (40 хв тому)"))
        assertEquals(listOf(AlertId.DATA_STALE), stale.active.map { it.id })
        assertFalse(AlertId.DATA_STALE in eval(stale.memory, snap(collectedAt = base), at(60)).ids())
        val recovered = eval(stale.memory, snap(now = at(70)), at(70)).memory
        assertTrue(AlertId.DATA_STALE in eval(recovered, snap(now = at(70)), at(110)).ids())
    }

    @Test
    fun staleTelemetryDoesNotProduceTransitions() {
        val on = eval(null, snap()).memory
        val staleOff = eval(on, snap(grid = "off", collectedAt = base), at(45))
        assertFalse(AlertId.LIGHT_LOST in staleOff.ids())
        assertEquals(GridState.ON, staleOff.memory.gridState)
    }

    // ---- toggles / active list / persistence ----

    @Test
    fun disabledGroupSuppressesNotificationsButStillTracksState() {
        val noLight = AlertSettings(enabledGroups = AlertGroup.entries.toSet() - AlertGroup.LIGHT_SCHEDULE)
        val on = eval(null, snap(), settings = noLight).memory
        val off = eval(on, snap(grid = "off"), settings = noLight)
        assertFalse(AlertId.LIGHT_LOST in off.ids())
        assertEquals(GridState.OFF, off.memory.gridState)
        assertFalse(AlertId.LIGHT_LOST in eval(off.memory, snap(grid = "off")).ids())
    }

    @Test
    fun activeAlertsAreSortedBySeverityAndTopIsHighest() {
        val r = eval(null, snap(soc = 9, temp = 47.0, nextStart = at(30), runtime = 1800, covers = false, loadW = 400.0, capacityWh = 460.0))
        val ids = r.active.map { it.id }
        assertEquals(AlertId.SOC_CRITICAL, ids.first())
        assertTrue(ids.containsAll(listOf(AlertId.OUTAGE_SOON, AlertId.RESERVE_SHORT_NEXT, AlertId.TEMPERATURE_HIGH)))
        assertEquals(AlertId.SOC_CRITICAL, AlertEvaluator.top(r.active)?.id)
        assertNull(AlertEvaluator.top(emptyList()))
        assertTrue(eval(null, snap()).active.isEmpty())
    }

    @Test
    fun memoryRoundTripsThroughJson() {
        val on = eval(null, snap(soc = 50)).memory
        val m = eval(on, snap(grid = "off", soc = 45, nextStart = at(300), offgrid = 900.0)).memory
        val restored = AlertMemoryCodec.decode(AlertMemoryCodec.encode(m))
        assertEquals(m, restored)
        assertNotNull(restored!!.outageStartedAtMillis)
        assertNull(AlertMemoryCodec.decode("not json"))
    }
}
