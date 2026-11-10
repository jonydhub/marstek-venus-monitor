package com.doloh.marstekwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.domain.Alert
import com.doloh.marstekwidget.domain.AlertEvaluator
import com.doloh.marstekwidget.domain.AlertSeverity
import com.doloh.marstekwidget.domain.BatteryDisplayFormatter
import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.BatteryWarning
import com.doloh.marstekwidget.domain.BatteryWarningResolver
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.OperatingModeFormatter
import com.doloh.marstekwidget.domain.PowerOutageFormatter
import com.doloh.marstekwidget.domain.SummaryLine
import com.doloh.marstekwidget.domain.SummaryLineKind
import com.doloh.marstekwidget.domain.SummaryMode
import com.doloh.marstekwidget.domain.SummaryTone
import com.doloh.marstekwidget.domain.WidgetSummary
import com.doloh.marstekwidget.domain.WidgetSummaryResolver
import com.doloh.marstekwidget.notify.AlertNotifier
import com.doloh.marstekwidget.ui.MainActivity
import com.doloh.marstekwidget.ui.WidgetConfigActivity
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class WidgetVariant { MINI, STRIP, MEDIUM, LARGE }

/** Everything a widget needs to render, decoupled from SharedPreferences so the debug preview can reuse it. */
data class WidgetRenderInput(
    val snapshot: BatterySnapshot?,
    val state: WidgetFetchState,
    val options: WidgetDisplayOptions,
    val lastFetchedAtMillis: Long,
    val now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")),
    /** Highest-severity active alert, shown as a warning line in the 4×2+ shapes. */
    val alert: Alert? = null,
)

object WidgetRenderer {
    private const val SEP = " · "
    private val LINE_IDS = intArrayOf(R.id.widget_line_1, R.id.widget_line_2, R.id.widget_line_3, R.id.widget_line_4, R.id.widget_line_5)

    fun update(context: Context, widgetId: Int) {
        update(context, widgetId, variantOf(context, widgetId))
    }

    fun variantOf(context: Context, widgetId: Int): WidgetVariant {
        val providerName = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        return when {
            providerName.endsWith("MarstekCompactWidgetProvider") -> WidgetVariant.MINI
            providerName.endsWith("MarstekStripWidgetProvider") -> WidgetVariant.STRIP
            providerName.endsWith("MarstekEnergyWidgetProvider") -> WidgetVariant.LARGE
            else -> WidgetVariant.MEDIUM
        }
    }

    internal fun update(context: Context, widgetId: Int, variant: WidgetVariant) {
        val manager = AppWidgetManager.getInstance(context)
        val preferences = WidgetPreferences(context)
        val input = WidgetRenderInput(
            snapshot = preferences.snapshot(),
            state = preferences.state(),
            options = preferences.options(widgetId),
            lastFetchedAtMillis = preferences.lastFetchedAtMillis(),
            alert = AlertEvaluator.top(AlertNotifier.activeAlerts(context)),
        )
        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher picks the entry that fits the real cell size, so no layout leaves empty bands.
            RemoteViews(WidgetShape.entries.associate { shape ->
                SizeF(shape.minWidthDp, shape.minHeightDp) to build(context, shape, variant, input, widgetId)
            })
        } else {
            build(context, legacyShape(manager, widgetId, variant), variant, input, widgetId)
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun legacyShape(manager: AppWidgetManager, widgetId: Int, variant: WidgetVariant): WidgetShape {
        val options = manager.getAppWidgetOptions(widgetId)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (width <= 0 || height <= 0) return WidgetShapeSelector.defaultFor(variant)
        return WidgetShapeSelector.select(width.toFloat(), height.toFloat())
    }

    fun layoutFor(shape: WidgetShape): Int = when (shape) {
        WidgetShape.FLAT_NARROW, WidgetShape.FLAT -> R.layout.widget_shape_flat
        WidgetShape.SMALL -> R.layout.widget_shape_small
        WidgetShape.WIDE -> R.layout.widget_shape_wide
        WidgetShape.TALL -> R.layout.widget_shape_tall
        WidgetShape.LARGE -> R.layout.widget_shape_large
        WidgetShape.XLARGE -> R.layout.widget_shape_xlarge
    }

    private fun lineCount(shape: WidgetShape): Int = when (shape) {
        WidgetShape.WIDE, WidgetShape.LARGE -> 4
        WidgetShape.XLARGE -> 5
        WidgetShape.TALL -> 5
        else -> 0
    }

    /** Builds one size-specific RemoteViews; only touches view IDs that exist in that shape's layout. */
    fun build(context: Context, shape: WidgetShape, variant: WidgetVariant, input: WidgetRenderInput, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layoutFor(shape))
        val snapshot = input.snapshot
        val options = input.options
        val summary = snapshot?.let { WidgetSummaryResolver.resolve(it, input.now) }
        val warning = snapshot?.let(BatteryWarningResolver::resolve)

        // SOC + light-state dot (all shapes).
        views.setTextViewText(R.id.widget_soc_text, socText(context, snapshot))
        views.setViewVisibility(R.id.widget_soc_text, visibility(options.showSoc))
        val light = summary?.lightState ?: GridState.UNKNOWN
        views.setTextColor(R.id.widget_light_dot, lightColor(light))
        views.setContentDescription(R.id.widget_light_dot, lightDescription(context, snapshot))

        // Primary state line.
        // 4×4 has its own light block («Світло є · черга 1.1»), so its headline names only the battery state.
        val primary = summary?.let { if (shape.isBlock && snapshot?.powerOutage != null) it.batteryState else it.primary }
            ?: noDataText(context, input.state)
        views.setTextViewText(R.id.widget_primary, primary)
        views.setTextColor(R.id.widget_primary, summary?.let { modeColor(it.mode) } ?: noDataColor(context, input.state))

        // Freshness.
        val compactUpdated = shape == WidgetShape.FLAT || shape == WidgetShape.FLAT_NARROW || shape == WidgetShape.SMALL || shape == WidgetShape.WIDE
        views.setTextViewText(
            R.id.widget_updated,
            if (compactUpdated) compactUpdatedText(context, snapshot, input) else fullUpdatedText(context, snapshot, input, variant),
        )
        views.setTextColor(R.id.widget_updated, if (input.state == WidgetFetchState.OK) Color.rgb(139, 155, 167) else healthColor(context, input.state))
        views.setContentDescription(R.id.widget_updated, fullUpdatedText(context, snapshot, input, variant))
        views.setViewVisibility(R.id.widget_updated, visibility(options.showLastUpdated || input.state != WidgetFetchState.OK))

        when (shape) {
            WidgetShape.FLAT_NARROW, WidgetShape.FLAT -> renderFlat(views, context, shape, summary, warning, input)
            WidgetShape.SMALL -> renderSmall(views, context, summary, warning, input)
            WidgetShape.WIDE, WidgetShape.TALL -> {
                renderCapacity(views, context, snapshot, options, full = true)
                renderLines(views, context, shape, summary, warning, input)
            }
            WidgetShape.LARGE, WidgetShape.XLARGE -> {
                renderCapacity(views, context, snapshot, options, full = true)
                renderLines(views, context, shape, summary, warning, input)
                renderPowerBlock(views, context, snapshot, summary, input)
                renderFlows(views, context, snapshot, options)
            }
        }
        bindActions(context, views, widgetId, shape)
        return views
    }

    private fun renderFlat(views: RemoteViews, context: Context, shape: WidgetShape, summary: WidgetSummary?, warning: BatteryWarning?, input: WidgetRenderInput) {
        val alert = warning?.takeIf { it != BatteryWarning.HIGH_POWER }
        val narrow = shape == WidgetShape.FLAT_NARROW
        if (narrow && summary != null) views.setTextViewText(R.id.widget_primary, summary.primary.substringBefore(SEP))
        val secondary = when {
            alert != null -> warningText(context, alert)
            narrow && summary != null -> summary.trailingPrimary
            summary != null -> summary.secondary
            else -> noDataHint(context, input.state)
        }
        views.setTextViewText(R.id.widget_secondary, secondary.orEmpty())
        views.setTextColor(R.id.widget_secondary, when {
            alert != null -> warningColor(context, alert)
            summary?.mode == SummaryMode.OUTAGE -> toneColor(reserveTone(summary), Color.rgb(201, 213, 220))
            else -> Color.rgb(168, 184, 194)
        })
        views.setViewVisibility(R.id.widget_secondary, visibility(!secondary.isNullOrBlank()))

        // 2x1: no room for the refresh button; tapping the card opens the app instead.
        views.setViewVisibility(R.id.widget_refresh, visibility(!narrow))
        val showTrailing = shape == WidgetShape.FLAT && summary != null
        views.setViewVisibility(R.id.widget_trailing_group, visibility(showTrailing))
        if (showTrailing && summary != null) setTrailing(views, summary, input.options)
    }

    private fun renderSmall(views: RemoteViews, context: Context, summary: WidgetSummary?, warning: BatteryWarning?, input: WidgetRenderInput) {
        val snapshot = input.snapshot
        val soc = snapshot?.socPercent?.coerceIn(0, 100)
        views.setImageViewBitmap(R.id.widget_soc_ring_image, socRingBitmap(context, soc))
        views.setViewVisibility(R.id.widget_soc_ring_container, visibility(input.options.showSoc))
        views.setTextViewText(R.id.widget_capacity_text, compactEnergyText(context, snapshot))
        views.setViewVisibility(R.id.widget_capacity_text, visibility(input.options.showCapacity))
        views.setContentDescription(
            R.id.widget_soc_ring_container,
            context.getString(R.string.widget_soc_energy_description, socText(context, snapshot), compactEnergyText(context, snapshot)),
        )
        if (summary != null) setTrailing(views, summary, input.options)
        else {
            views.setTextViewText(R.id.widget_trailing_primary, "—")
            views.setViewVisibility(R.id.widget_trailing_secondary, View.GONE)
        }
        val alert = warning?.takeIf { it != BatteryWarning.HIGH_POWER }
        // 2x2 header holds only the state word; the rest of the primary line moves into the body text.
        summary?.let { views.setTextViewText(R.id.widget_primary, it.primary.substringBefore(SEP)) }
        val tail = summary?.primary?.substringAfter(SEP, "")?.takeIf(String::isNotBlank)
        val secondary = when {
            alert != null -> warningText(context, alert)
            summary != null -> listOfNotNull(tail, summary.secondary).joinToString(SEP).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            else -> noDataHint(context, input.state)
        }
        views.setTextViewText(R.id.widget_secondary, secondary.orEmpty())
        views.setTextColor(R.id.widget_secondary, alert?.let { warningColor(context, it) }
            ?: if (summary?.mode == SummaryMode.OUTAGE) toneColor(reserveTone(summary), Color.rgb(201, 213, 220)) else Color.rgb(201, 213, 220))
        views.setViewVisibility(R.id.widget_secondary, visibility(!secondary.isNullOrBlank()))
    }

    private fun setTrailing(views: RemoteViews, summary: WidgetSummary, options: WidgetDisplayOptions) {
        val etaDriven = summary.mode == SummaryMode.CHARGING || summary.mode == SummaryMode.DISCHARGING
        val showPrimary = !etaDriven || options.showEta
        views.setTextViewText(R.id.widget_trailing_primary, summary.trailingPrimary)
        views.setTextColor(R.id.widget_trailing_primary, if (summary.mode == SummaryMode.OUTAGE) Color.rgb(255, 210, 100) else Color.WHITE)
        views.setViewVisibility(R.id.widget_trailing_primary, visibility(showPrimary))
        val secondary = summary.trailingSecondary
        views.setTextViewText(R.id.widget_trailing_secondary, secondary.orEmpty())
        views.setViewVisibility(R.id.widget_trailing_secondary, visibility(secondary != null && (summary.mode != SummaryMode.OUTAGE || options.showEta)))
    }

    private fun renderCapacity(views: RemoteViews, context: Context, snapshot: BatterySnapshot?, options: WidgetDisplayOptions, full: Boolean) {
        views.setProgressBar(R.id.widget_soc_progress, 100, snapshot?.socPercent ?: 0, false)
        views.setViewVisibility(R.id.widget_soc_progress, visibility(options.showSoc))
        views.setTextViewText(R.id.widget_capacity_text, if (full) capacityText(context, snapshot) else compactEnergyText(context, snapshot))
        views.setViewVisibility(R.id.widget_capacity_text, visibility(options.showCapacity))
    }

    private fun renderLines(views: RemoteViews, context: Context, shape: WidgetShape, summary: WidgetSummary?, warning: BatteryWarning?, input: WidgetRenderInput) {
        val options = input.options
        val excluded = if (shape.isBlock) setOf(SummaryLineKind.LIGHT, SummaryLineKind.RESERVE, SummaryLineKind.RETURN_TIME) else emptySet()
        val lines = buildList<SummaryLine> {
            val alert = input.alert
            if (alert != null) {
                add(SummaryLine(SummaryLineKind.TEMPERATURE, alert.title, if (alert.severity == AlertSeverity.CRITICAL) SummaryTone.CRITICAL else SummaryTone.WARNING))
            } else {
                warning?.takeIf { it != BatteryWarning.HIGH_POWER }?.let { add(SummaryLine(SummaryLineKind.TEMPERATURE, warningText(context, it), SummaryTone.WARNING)) }
            }
            summary?.lines?.filter { it.kind !in excluded && allowed(it.kind, options) }?.forEach { line ->
                add(if (line.kind == SummaryLineKind.TEMPERATURE) withBackup(line, summary, input) else line)
                // Idle at full charge: say so instead of leaving the ETA slot empty.
                if (line.kind == SummaryLineKind.LIGHT && summary.mode == SummaryMode.IDLE && options.showEta) {
                    summary.etaText?.let { add(SummaryLine(SummaryLineKind.ETA, it, SummaryTone.GOOD)) }
                }
            }
            if (summary != null && summary.mode == SummaryMode.IDLE && options.showEta && none { it.kind == SummaryLineKind.ETA }) {
                summary.etaText?.let { add(0, SummaryLine(SummaryLineKind.ETA, it, SummaryTone.GOOD)) }
            }
            if (summary == null) add(SummaryLine(SummaryLineKind.LIGHT, noDataHint(context, input.state).orEmpty()))
        }.filter { it.text.isNotBlank() }
        val count = lineCount(shape)
        for (index in 0 until count) {
            val id = LINE_IDS[index]
            val line = lines.getOrNull(index)
            views.setViewVisibility(id, visibility(line != null))
            if (line != null) {
                views.setTextViewText(id, line.text)
                views.setTextColor(id, toneColor(line.tone, Color.rgb(201, 213, 220)))
            }
        }
    }

    /** «Температура 27,0 °C · вихід 0 W» — the backup output joins the temperature row outside an outage. */
    private fun withBackup(line: SummaryLine, summary: WidgetSummary, input: WidgetRenderInput): SummaryLine {
        if (summary.mode == SummaryMode.OUTAGE || !input.options.showBackupPower) return line
        val backup = input.snapshot?.insights?.backupOutputWatts ?: input.snapshot?.offgridPowerWatts ?: return line
        return line.copy(text = line.text + SEP + "вихід " + WidgetSummaryResolver.formatWatts(backup))
    }

    private fun allowed(kind: SummaryLineKind, options: WidgetDisplayOptions): Boolean = when (kind) {
        SummaryLineKind.POWER -> options.showPower
        SummaryLineKind.ETA -> options.showEta
        SummaryLineKind.TEMPERATURE -> options.showTemperature
        SummaryLineKind.BACKUP_OUTPUT -> options.showBackupPower
        SummaryLineKind.LIGHT, SummaryLineKind.RESERVE, SummaryLineKind.RETURN_TIME -> true
    }

    private fun renderPowerBlock(views: RemoteViews, context: Context, snapshot: BatterySnapshot?, summary: WidgetSummary?, input: WidgetRenderInput) {
        val info = snapshot?.powerOutage
        views.setViewVisibility(R.id.widget_power_group, visibility(info != null))
        info ?: return
        val headline = PowerOutageFormatter.headline(info)
        views.setTextViewText(R.id.widget_power_status, context.getString(R.string.widget_power_headline_group, headline, info.group ?: "—"))
        views.setTextColor(R.id.widget_power_status, lightColor(info.gridState))
        views.setTextViewText(R.id.widget_power_detail, PowerOutageFormatter.detail(info, input.now))
        // Same single runtime + verdict as the summary (autonomy runtime, else battery ETA to 0%).
        val reserveLine = summary?.lines?.firstOrNull { it.kind == SummaryLineKind.RESERVE }
        val reserve = reserveLine?.text ?: PowerOutageFormatter.reserve(info)
            ?: if (info.gridState == GridState.OFF) context.getString(R.string.widget_power_reserve_measuring) else null
        views.setViewVisibility(R.id.widget_power_reserve, visibility(reserve != null))
        reserve?.let { views.setTextViewText(R.id.widget_power_reserve, it) }
        views.setTextColor(R.id.widget_power_reserve, toneColor(reserveLine?.tone ?: SummaryTone.NEUTRAL, Color.rgb(201, 213, 220)))
    }

    private fun renderFlows(views: RemoteViews, context: Context, snapshot: BatterySnapshot?, options: WidgetDisplayOptions) {
        val gridText = context.getString(R.string.widget_grid_power, BatteryDisplayFormatter.formatSignedWatts(snapshot?.gridPowerWatts))
        views.setTextViewText(R.id.widget_grid_power, gridText)
        views.setContentDescription(R.id.widget_grid_power, context.getString(R.string.widget_grid_power_accessibility, gridText))
        val backup = snapshot?.insights?.backupOutputWatts ?: snapshot?.offgridPowerWatts
        views.setTextViewText(R.id.widget_backup_power, context.getString(R.string.widget_backup_power_short, BatteryDisplayFormatter.formatSignedWatts(backup)))
        views.setViewVisibility(R.id.widget_grid_power, visibility(options.showGridPower))
        views.setViewVisibility(R.id.widget_backup_power, visibility(options.showBackupPower))
        views.setViewVisibility(R.id.widget_flow_sources_group, visibility(options.showGridPower || options.showBackupPower))
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        widgetProviders().forEach { (provider, variant) ->
            manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id -> update(context, id, variant) }
        }
    }

    fun allWidgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return widgetProviders().flatMap { (provider, _) -> manager.getAppWidgetIds(ComponentName(context, provider)).toList() }.toIntArray()
    }

    fun hasWidgets(context: Context): Boolean = allWidgetIds(context).isNotEmpty()

    private fun widgetProviders(): List<Pair<Class<out AppWidgetProvider>, WidgetVariant>> = listOf(
        MarstekCompactWidgetProvider::class.java to WidgetVariant.MINI,
        MarstekStripWidgetProvider::class.java to WidgetVariant.STRIP,
        MarstekWidgetProvider::class.java to WidgetVariant.MEDIUM,
        MarstekEnergyWidgetProvider::class.java to WidgetVariant.LARGE,
    )

    private fun bindActions(context: Context, views: RemoteViews, widgetId: Int, shape: WidgetShape) {
        val refreshIntent = Intent(context, MarstekWidgetProvider::class.java).apply {
            action = BaseMarstekWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            PendingIntent.getBroadcast(context, widgetId * 10 + 1, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(context, widgetId * 10 + 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        if (shape == WidgetShape.WIDE || shape == WidgetShape.TALL || shape.isBlock) {
            val configIntent = Intent(context, WidgetConfigActivity::class.java).apply { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) }
            views.setOnClickPendingIntent(
                R.id.widget_settings,
                PendingIntent.getActivity(context, widgetId * 10 + 3, configIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
    }

    private fun reserveTone(summary: WidgetSummary): SummaryTone =
        summary.lines.firstOrNull { it.kind == SummaryLineKind.RESERVE }?.tone ?: SummaryTone.NEUTRAL

    private fun noDataText(context: Context, state: WidgetFetchState): String = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN -> context.getString(R.string.widget_state_sign_in)
        WidgetFetchState.ERROR, WidgetFetchState.STALE -> context.getString(R.string.widget_state_offline)
        WidgetFetchState.NEVER -> context.getString(R.string.widget_state_waiting_data)
        WidgetFetchState.OK -> context.getString(R.string.widget_state_unknown)
    }

    private fun noDataHint(context: Context, state: WidgetFetchState): String? = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN -> context.getString(R.string.widget_hint_sign_in)
        WidgetFetchState.ERROR, WidgetFetchState.STALE -> context.getString(R.string.widget_update_problem)
        WidgetFetchState.NEVER, WidgetFetchState.OK -> null
    }

    private fun noDataColor(context: Context, state: WidgetFetchState): Int = when (state) {
        WidgetFetchState.ERROR -> context.getColor(R.color.widget_status_critical)
        WidgetFetchState.OK -> context.getColor(R.color.widget_state_unknown)
        else -> context.getColor(R.color.widget_status_warning)
    }

    private fun modeColor(mode: SummaryMode): Int = when (mode) {
        SummaryMode.OUTAGE -> Color.rgb(255, 184, 107)
        SummaryMode.CHARGING -> Color.rgb(127, 227, 178)
        SummaryMode.DISCHARGING -> Color.rgb(142, 230, 238)
        SummaryMode.IDLE -> Color.rgb(243, 247, 250)
        SummaryMode.UNKNOWN -> Color.rgb(160, 174, 184)
    }

    private fun toneColor(tone: SummaryTone, neutral: Int): Int = when (tone) {
        SummaryTone.NEUTRAL -> neutral
        SummaryTone.GOOD -> Color.rgb(127, 227, 178)
        SummaryTone.WARNING -> Color.rgb(255, 210, 100)
        SummaryTone.CRITICAL -> Color.rgb(255, 140, 128)
    }

    fun lightColor(state: GridState): Int = when (state) {
        GridState.ON -> Color.rgb(127, 227, 178)
        GridState.OFF -> Color.rgb(255, 159, 90)
        GridState.UNKNOWN -> Color.rgb(111, 130, 144)
    }

    private fun lightDescription(context: Context, snapshot: BatterySnapshot?): String =
        snapshot?.powerOutage?.let(PowerOutageFormatter::headline) ?: context.getString(R.string.widget_power_status_unknown)

    private fun healthText(context: Context, state: WidgetFetchState): String = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN -> context.getString(R.string.widget_health_sign_in)
        WidgetFetchState.ERROR -> context.getString(R.string.widget_health_offline)
        WidgetFetchState.STALE -> context.getString(R.string.widget_health_stale)
        WidgetFetchState.NEVER -> context.getString(R.string.widget_health_waiting)
        WidgetFetchState.OK -> context.getString(R.string.widget_health_online)
    }

    private fun healthColor(context: Context, state: WidgetFetchState): Int = when (state) {
        WidgetFetchState.NEEDS_SIGN_IN, WidgetFetchState.STALE, WidgetFetchState.NEVER -> context.getColor(R.color.widget_status_warning)
        WidgetFetchState.ERROR -> context.getColor(R.color.widget_status_critical)
        WidgetFetchState.OK -> context.getColor(R.color.widget_status_ok)
    }

    private fun warningText(context: Context, warning: BatteryWarning): String = when (warning) {
        BatteryWarning.CRITICAL_TEMPERATURE -> context.getString(R.string.widget_warning_critical_temp_short)
        BatteryWarning.HIGH_TEMPERATURE -> context.getString(R.string.widget_warning_high_temp_short)
        BatteryWarning.LOW_SOC -> context.getString(R.string.widget_warning_low_soc_short)
        BatteryWarning.HIGH_POWER -> context.getString(R.string.widget_warning_high_power_short)
    }

    private fun warningColor(context: Context, warning: BatteryWarning): Int = when (warning) {
        BatteryWarning.CRITICAL_TEMPERATURE -> context.getColor(R.color.widget_critical_text)
        else -> context.getColor(R.color.widget_warning_text)
    }

    private fun socText(context: Context, snapshot: BatterySnapshot?) =
        snapshot?.socPercent?.let { context.getString(R.string.widget_soc_compact, it) } ?: context.getString(R.string.widget_soc_compact_unknown)

    private fun capacityText(context: Context, snapshot: BatterySnapshot?): String {
        val stored = snapshot?.batteryCapacityWh ?: return context.getString(R.string.widget_kwh_unknown)
        val rated = snapshot.ratedCapacityWh?.let(BatteryDisplayFormatter::formatKwh) ?: "—"
        return context.getString(R.string.widget_capacity_short, BatteryDisplayFormatter.formatKwh(stored), rated)
    }

    private fun compactEnergyText(context: Context, snapshot: BatterySnapshot?): String =
        snapshot?.batteryCapacityWh?.takeIf(Double::isFinite)?.let(BatteryDisplayFormatter::formatKwh) ?: context.getString(R.string.widget_kwh_unknown)

    private fun timestamp(snapshot: BatterySnapshot?, input: WidgetRenderInput): Long =
        snapshot?.collectedAt?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            ?: input.lastFetchedAtMillis

    private fun clock(millis: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    private fun compactUpdatedText(context: Context, snapshot: BatterySnapshot?, input: WidgetRenderInput): String {
        val ts = timestamp(snapshot, input)
        val time = if (ts > 0L) clock(ts) else "—"
        return when (input.state) {
            WidgetFetchState.OK -> time
            WidgetFetchState.NEVER -> context.getString(R.string.widget_health_waiting)
            else -> "${healthText(context, input.state)} $time"
        }
    }

    private fun fullUpdatedText(context: Context, snapshot: BatterySnapshot?, input: WidgetRenderInput, variant: WidgetVariant): String {
        val ts = timestamp(snapshot, input)
        val base = if (ts <= 0L) context.getString(R.string.widget_not_updated) else when (input.state) {
            WidgetFetchState.STALE -> context.getString(R.string.widget_stale_at, clock(ts))
            WidgetFetchState.NEEDS_SIGN_IN, WidgetFetchState.ERROR -> context.getString(R.string.widget_cached_at, clock(ts))
            WidgetFetchState.NEVER, WidgetFetchState.OK -> context.getString(R.string.widget_online_updated_at, healthText(context, input.state), clock(ts))
        }
        val mode = snapshot?.operatingMode?.takeIf { variant == WidgetVariant.LARGE }?.let(OperatingModeFormatter::format)
        return if (mode != null) "$base · $mode" else base
    }

    private fun socRingBitmap(context: Context, socPercent: Int?): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (56f * density).roundToInt()
        val stroke = 5f * density
        val inset = stroke / 2f + density
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val arc = RectF(inset, inset, size - inset, size - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = context.getColor(R.color.widget_ring_track)
        }
        canvas.drawArc(arc, -90f, 360f, false, paint)
        val progress = socPercent?.coerceIn(0, 100) ?: 0
        if (progress > 0) {
            paint.color = when {
                progress <= 15 -> context.getColor(R.color.widget_ring_critical)
                progress <= 30 -> context.getColor(R.color.widget_ring_warning)
                else -> context.getColor(R.color.widget_ring_accent)
            }
            canvas.drawArc(arc, -90f, progress * 3.6f, false, paint)
        }
        return bitmap
    }

    private fun visibility(show: Boolean) = if (show) View.VISIBLE else View.GONE
}
