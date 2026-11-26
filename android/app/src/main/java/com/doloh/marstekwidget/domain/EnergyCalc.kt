package com.doloh.marstekwidget.domain

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * «Калькулятор»: how much energy a load takes over a time, what it costs, what share of the battery it is and how long
 * the current charge lasts at that power. Port of the web `energyCalc` (keep in sync, same cases in
 * server/tests/test_energy_calc.py).
 */
object EnergyCalc {
    /** Fixed household price, грн/кВт·год with VAT (КМУ, valid until 30.04.2027). */
    const val DEFAULT_TARIFF = 4.32
    /** Venus E backup output limit — above it the station cannot carry the load during an outage. */
    const val BACKUP_LIMIT_W = 2500.0
    private const val NBSP = '\u00A0'
    private val uk = Locale.forLanguageTag("uk-UA")

    data class Battery(val ratedWh: Double?, val storedWh: Double?, val usableWh: Double?, val floorPct: Double?)

    data class Preset(val name: String, val watts: Int, val minutes: Int)

    /** Typical household loads: tap → power + a usual run time. */
    val PRESETS = listOf(
        Preset("Чайник", 2000, 5),
        Preset("Мікрохвильовка", 1000, 5),
        Preset("Бойлер", 1500, 60),
        Preset("Обігрівач", 1500, 60),
        Preset("Пральна", 500, 90),
        Preset("Холодильник", 100, 24 * 60),
        Preset("Телевізор", 100, 180),
        Preset("Ноутбук", 65, 240),
        Preset("Роутер", 15, 24 * 60),
    )

    data class Result(
        /** «750 Вт·год» / «6,00 кВт·год». */
        val energy: String,
        /** «3,24 грн»; null without a tariff. */
        val cost: String?,
        /** «15 %» of the rated capacity; null without the capacity. */
        val batteryShare: String?,
        /** Charge after running it from the battery, «64 %»; null when it does not fit or no data. */
        val socAfter: String?,
        /** Whether the usable charge covers the whole run; null without battery data. */
        val fits: Boolean?,
        /** «2 год 18 хв» the usable charge lasts at this power; null without battery data. */
        val runtime: String?,
        /** «до 11 %» — the floor the runtime is computed to. */
        val runtimeTo: String,
        /** Power above the backup output limit. */
        val overLimit: Boolean,
    )

    fun energyText(wh: Double): String =
        if (wh < 999.5) "${wh.roundToLong()}${NBSP}Вт·год" else "${String.format(uk, "%.2f", wh / 1000.0)}${NBSP}кВт·год"

    fun money(value: Double): String = "${String.format(uk, "%.2f", value)}${NBSP}грн"

    /** Null when the power or the time is not positive. */
    fun calc(watts: Double?, seconds: Long?, battery: Battery? = null, tariff: Double? = DEFAULT_TARIFF): Result? {
        if (watts == null || !watts.isFinite() || watts <= 0 || seconds == null || seconds <= 0) return null
        val wh = watts * seconds / 3600.0
        val rated = battery?.ratedWh?.takeIf { it.isFinite() && it > 0 }
        val stored = battery?.storedWh?.takeIf { it.isFinite() && it >= 0 }
        val floorPct = battery?.floorPct?.takeIf { it.isFinite() && it > 0 }
        val usable = battery?.usableWh?.takeIf { it.isFinite() }
            ?: stored?.let { s -> s - (if (rated != null && floorPct != null) rated * floorPct / 100.0 else 0.0) }
        val usableClamped = usable?.coerceAtLeast(0.0)
        val fits = usableClamped?.let { wh <= it }
        return Result(
            energy = energyText(wh),
            cost = tariff?.takeIf { it.isFinite() && it > 0 }?.let { money(wh / 1000.0 * it) },
            batteryShare = rated?.let { "${(wh / it * 100).roundToInt()}$NBSP%" },
            socAfter = if (fits == true && rated != null && stored != null) "${((stored - wh) / rated * 100).roundToInt()}$NBSP%" else null,
            fits = fits,
            runtime = usableClamped?.let { TodayFormatter.short((it / watts * 3600.0).roundToLong()) },
            runtimeTo = "до ${floorPct?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(uk, "%.1f", it) } ?: "0"}$NBSP%",
            overLimit = watts > BACKUP_LIMIT_W,
        )
    }
}
