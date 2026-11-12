package com.doloh.marstekwidget.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.data.AccessCookie
import com.doloh.marstekwidget.data.JournalStore
import com.doloh.marstekwidget.data.MarstekApiClient
import com.doloh.marstekwidget.domain.ActualDays
import com.doloh.marstekwidget.domain.ChartEventClusters
import com.doloh.marstekwidget.domain.EtaTextFormatter
import com.doloh.marstekwidget.domain.JournalExport
import com.doloh.marstekwidget.domain.LightKind
import com.doloh.marstekwidget.domain.LightStatusResolver
import com.doloh.marstekwidget.domain.ScheduleDays
import com.doloh.marstekwidget.domain.TodayFormatter
import kotlin.math.roundToInt
import com.doloh.marstekwidget.data.TelemetryFetchResult
import com.doloh.marstekwidget.data.TelemetryRepository
import com.doloh.marstekwidget.outage.OutageRegions
import com.doloh.marstekwidget.settings.AppSettings
import com.doloh.marstekwidget.settings.ConnectionMode
import com.doloh.marstekwidget.settings.ServerAuth
import com.doloh.marstekwidget.settings.ServerUrlValidator
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.domain.BatteryDisplayFormatter
import com.doloh.marstekwidget.domain.BatterySnapshot
import com.doloh.marstekwidget.domain.BatteryWarning
import com.doloh.marstekwidget.domain.BatteryWarningResolver
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.domain.Alert
import com.doloh.marstekwidget.domain.AlertGroup
import com.doloh.marstekwidget.domain.AlertSeverity
import com.doloh.marstekwidget.domain.SummaryMode
import com.doloh.marstekwidget.domain.OperatingModeFormatter
import com.doloh.marstekwidget.domain.PowerOutageFormatter
import com.doloh.marstekwidget.domain.SummaryLineKind
import com.doloh.marstekwidget.domain.SummaryTone
import com.doloh.marstekwidget.domain.WidgetSummaryResolver
import com.doloh.marstekwidget.notify.AlertNotifier
import com.doloh.marstekwidget.widget.WidgetFetchState
import com.doloh.marstekwidget.widget.WidgetPreferences
import com.doloh.marstekwidget.widget.WidgetRefreshScheduler
import com.doloh.marstekwidget.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val metricValues = mutableMapOf<String, TextView>()
    private lateinit var statusText: TextView
    private lateinit var signInBanner: LinearLayout
    private lateinit var socValue: TextView
    private lateinit var lightDot: TextView
    private lateinit var lightHeadline: TextView
    private lateinit var modeChip: TextView
    private lateinit var stateLine: TextView
    private lateinit var capacityLine: TextView
    private lateinit var socProgress: SocBarView
    private lateinit var etaLine: TextView
    private lateinit var outageLine: TextView
    private lateinit var reserveLine: TextView
    private lateinit var historyCard: LinearLayout
    private lateinit var sparkline: SocChartView
    private lateinit var historyCaption: TextView
    private lateinit var powerGroup: TextView
    private lateinit var powerNote: TextView
    private lateinit var modeMeta: TextView
    private lateinit var updatedText: TextView
    private lateinit var refreshButton: TextView
    private lateinit var alertsCard: LinearLayout
    private lateinit var alertsList: LinearLayout
    private lateinit var powerMaybe: TextView
    private lateinit var subLine: TextView
    private lateinit var todayCaption: TextView
    private lateinit var scheduleCaption: TextView
    private lateinit var scheduleDays: ScheduleDaysView
    private lateinit var todayTiles: LinearLayout
    private lateinit var journalPreview: LinearLayout
    private lateinit var diagnosticsText: TextView
    private var serverJournal: List<com.doloh.marstekwidget.domain.JournalEvent> = emptyList()
    private var refreshing = false
    private var spec = ScreenLayoutSelector.specFor(0)
    private val settings: AppSettings get() = SettingsStore(this).load()
    private val refreshIntervalMs: Long get() = if (settings.mode == ConnectionMode.DIRECT) DIRECT_INTERVAL_MS else SERVER_INTERVAL_MS

    /** Debug-only fixture showcase: no network refresh, no sign-in banner. Ignored in release builds. */
    private val showcase: Boolean
        get() = intent.getBooleanExtra("showcase", false) &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val refreshLoop = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                refreshTelemetry()
                handler.postDelayed(this, refreshIntervalMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!showcase && !settings.configured) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_ONBOARDING, true))
            finish()
            return
        }
        WidgetRefreshScheduler.ensurePeriodic(this)
        AlertNotifier.ensureChannels(this)
        showDashboard()
    }

    /**
     * Rotation, split-screen and window resizes are handled in place (see manifest configChanges): rebuild the
     * width-dependent arrangement from the cached snapshot without restarting the activity or re-polling.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::statusText.isInitialized) return
        val newSpec = ScreenLayoutSelector.specFor(newConfig.screenWidthDp)
        if (newSpec == spec) return
        showDashboard()
    }

    private fun showDashboard() {
        val content = buildDashboard()
        SystemBarsInsetHelper.apply(content, 14, 10, 14, 18)
        setContentView(content)
        render(WidgetPreferences(this).snapshot(), WidgetPreferences(this).state())
    }

    override fun onResume() {
        super.onResume()
        render(WidgetPreferences(this).snapshot(), WidgetPreferences(this).state())
        refreshTelemetry()
        loadServerJournal()
        handler.removeCallbacks(refreshLoop)
        handler.postDelayed(refreshLoop, refreshIntervalMs)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildDashboard(): ScrollView {
        spec = ScreenLayoutSelector.specFor(resources.configuration.screenWidthDp)
        metricValues.clear()
        val wide = spec.mode != ScreenLayoutMode.SINGLE
        val page = MaxWidthLinearLayout(this, dp(spec.maxContentWidthDp)).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(18))
            setBackgroundColor(BG)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(BG)
            addView(FrameLayout(this@MainActivity).apply {
                setBackgroundColor(BG)
                addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // One-row header: icon · brand · status · refresh · settings.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
        header.addView(label(getString(R.string.main_brand_name), 14f, TEXT, true).apply { letterSpacing = 0.06f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusText = label("…", 9f, MINT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = rounded(SURFACE_RAISED, dp(20), STROKE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(if (wide) 320 else 150)
        }
        header.addView(statusText)
        refreshButton = label("↻", 20f, MINT, false).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.main_refresh)
            setPadding(dp(12), dp(2), dp(4), dp(2))
            setOnClickListener { refreshTelemetry() }
        }
        header.addView(refreshButton)
        header.addView(label("⚙", 20f, MUTED, false).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.main_settings)
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        page.addView(header)

        // Single sign-in banner.
        signInBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(Color.rgb(48, 38, 18), dp(16), Color.rgb(120, 92, 32))
        }
        signInBanner.addView(label(getString(R.string.main_signin_banner), 11f, AMBER, false))
        signInBanner.addView(actionButton(getString(R.string.main_sign_in), filled = true) {
            startActivity(Intent(this@MainActivity, AccessLoginActivity::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        page.addView(signInBanner, sectionMargin())

        // Active alerts («На що звернути увагу»), hidden when nothing needs attention.
        alertsCard = card().apply { background = rounded(Color.rgb(40, 30, 22), dp(18), Color.rgb(120, 86, 40)) }
        alertsCard.addView(sectionTitle(getString(R.string.main_alerts_title)).apply { setTextColor(AMBER) })
        alertsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alertsCard.addView(alertsList)

        // Hero: SOC + light state (once) + runtime / ETA + today tiles.
        val hero = card()
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        socValue = label("—%", 46f, MINT, true).apply { includeFontPadding = false }
        heroRow.addView(socValue)
        val stateColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val lightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lightDot = label("●", 14f, DIM, false).apply { setPadding(0, 0, dp(6), 0) }
        lightRow.addView(lightDot)
        lightHeadline = label(getString(R.string.widget_power_status_unknown), 17f, TEXT, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lightRow.addView(lightHeadline, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Operating mode (ES.GetMode) as a small chip: «UPS», «Ручний»… — leaving UPS is also a journal anomaly.
        modeChip = label("", 9f, MUTED, true).apply {
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = rounded(SURFACE_RAISED, dp(10), STROKE)
            maxLines = 1
            visibility = View.GONE
        }
        lightRow.addView(modeChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        stateColumn.addView(lightRow)
        stateLine = label(getString(R.string.main_dashboard_loading), 12f, MUTED, true).apply { setPadding(0, dp(3), 0, 0) }
        stateColumn.addView(stateLine)
        heroRow.addView(stateColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow)
        socProgress = SocBarView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        hero.addView(socProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(12) })
        capacityLine = label(getString(R.string.widget_kwh_unknown), 10f, MUTED, false).apply { setPadding(0, dp(4), 0, 0) }
        hero.addView(capacityLine)
        etaLine = label("", 19f, TEXT, true).apply { setPadding(0, dp(10), 0, 0) }
        hero.addView(etaLine)
        subLine = label("", 13f, MUTED, false).apply { setPadding(0, dp(2), 0, 0) }
        hero.addView(subLine)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        todayTiles = tiles
        listOf("todayCharge" to "Заряд", "todayDischarge" to "Розряд", "todayOutage" to "Без світла").forEachIndexed { i, (key, caption) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = rounded(SURFACE_RAISED, dp(14), STROKE)
            }
            // One line always: «10 год 28 хв» shrinks (16→10 sp) instead of wrapping, so all three tiles stay equal height.
            val value = label("—", 16f, TEXT, true).apply {
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            metricValues[key] = value
            box.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
            // Second line: today's energy «0,81 кВт·год» (shrinks instead of wrapping, like the duration above).
            val energy = label("", 11f, Color.rgb(201, 213, 220), false).apply {
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            metricValues[key + "Kwh"] = energy
            box.addView(energy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))
            box.addView(label(caption, 10f, if (key == "todayOutage") CORAL else MUTED, false).apply { maxLines = 1 })
            tiles.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(4)
                if (i < 2) marginEnd = dp(4)
            })
        }
        hero.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        todayCaption = label("Сьогодні з 00:00", 9f, DIM, false).apply { setPadding(0, dp(4), 0, 0) }
        hero.addView(todayCaption)

        // Schedule: «Сьогодні» / «Завтра» rows with an hour scale + windows as text. Tap → «Графік відключень».
        val scheduleCard = if (wide) card().apply { addView(sectionTitle(getString(R.string.main_schedule_title))) } else null
        val scheduleHost = scheduleCard ?: hero
        val scheduleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.schedule_screen_title)
            setOnClickListener { startActivity(Intent(this@MainActivity, ScheduleActivity::class.java)) }
        }
        scheduleCaption = label("Графік відключень", 11f, MUTED, true).apply { setPadding(0, dp(14), 0, dp(6)) }
        scheduleBlock.addView(scheduleCaption)
        scheduleDays = ScheduleDaysView(this, spec.textScale)
        scheduleBlock.addView(scheduleDays)
        outageLine = label("", 12f, TEXT, false).apply { setPadding(0, dp(6), 0, 0) }
        powerMaybe = label("", 12f, AMBER, false).apply { setPadding(0, dp(3), 0, 0); visibility = View.GONE }
        reserveLine = label("", 12f, TEXT, true).apply { setPadding(0, dp(3), 0, 0) }
        powerGroup = label("", 9f, MUTED, false).apply { setPadding(0, dp(6), 0, 0) }
        powerNote = label(getString(R.string.main_power_unavailable), 9f, DIM, false).apply { setPadding(0, dp(2), 0, 0) }
        listOf(outageLine, powerMaybe, reserveLine).forEach(scheduleHost::addView)
        scheduleHost.addView(scheduleBlock)
        scheduleBlock.addView(powerGroup)
        scheduleHost.addView(powerNote)

        // 24 h SOC chart: outage shading, event markers, axis labels, discharge floor.
        historyCard = card()
        historyCard.addView(sectionTitle(getString(R.string.main_history_title)))
        sparkline = SocChartView(this).apply {
            // Chart markers merge into counted clusters; the journal lists every event.
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, JournalActivity::class.java)) }
        }
        historyCard.addView(sparkline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(maxOf(150, spec.sparklineHeightDp + 70))))
        historyCaption = label("", 9f, DIM, false).apply { setPadding(0, dp(5), 0, 0) }
        historyCard.addView(historyCaption)

        // Battery & flows.
        val flowCard = card()
        flowCard.addView(sectionTitle(getString(R.string.main_details_title)))
        flowCard.addView(metricRow("battery", R.string.main_battery_flow, "temperature", R.string.main_temperature))
        flowCard.addView(metricRow("backup", R.string.main_backup, "grid", R.string.main_grid), rowMargin())
        flowCard.addView(metricRow("backupAvg", R.string.main_backup_avg, "backupToday", R.string.main_backup_today), rowMargin())

        // Journal entry point: last events + «Відкрити журнал».
        val journalCard = card()
        journalCard.addView(sectionTitle("Журнал подій"))
        journalPreview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        journalCard.addView(journalPreview)
        journalCard.addView(actionButton("Відкрити журнал · аномалії · експорт", filled = false) {
            startActivity(Intent(this@MainActivity, JournalActivity::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })

        // Accumulated counters.
        val energyCard = card()
        energyCard.addView(sectionTitle(getString(R.string.main_energy_title)))
        energyCard.addView(metricRow("energyGridIn", R.string.main_energy_grid_in, "energyGridOut", R.string.main_energy_grid_out))
        energyCard.addView(metricColumn(getString(R.string.main_energy_load), "energyLoad"), rowMargin())
        energyCard.addView(label(getString(R.string.main_energy_moved_note), 9f, DIM, false).apply { setPadding(0, dp(6), 0, 0) })

        // Diagnostics, collapsed by default.
        val diagCard = card()
        val diagBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        diagCard.addView(label("Діагностика  ▸", 12f, TEXT, true).apply {
            setOnClickListener {
                val open = diagBody.visibility != View.VISIBLE
                diagBody.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "Діагностика  ▾" else "Діагностика  ▸"
            }
        })
        modeMeta = label(getString(R.string.main_mode, "—"), 10f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) }
        diagBody.addView(modeMeta)
        diagnosticsText = label("", 10f, MUTED, false).apply { setPadding(0, dp(4), 0, 0) }
        diagBody.addView(diagnosticsText)
        diagBody.addView(label(getString(R.string.main_grid_sign_note), 9f, DIM, false).apply { setPadding(0, dp(4), 0, 0) })
        diagBody.addView(label("Світло є / нема — лише за потужністю на резервному виході батареї. Без зв'язку з батареєю стан невідомий.", 9f, DIM, false).apply { setPadding(0, dp(4), 0, 0) })
        diagCard.addView(diagBody)

        when (spec.mode) {
            ScreenLayoutMode.SINGLE -> listOf(alertsCard, hero, historyCard, flowCard, journalCard, energyCard, diagCard)
                .forEach { page.addView(it, sectionMargin()) }
            ScreenLayoutMode.TWO_COLUMN -> page.addView(columns(
                listOf(hero, historyCard, flowCard, energyCard),
                listOf(scheduleCard!!, alertsCard, journalCard, diagCard),
            ))
            ScreenLayoutMode.THREE_COLUMN -> page.addView(columns(
                listOf(hero, flowCard, energyCard),
                listOf(scheduleCard!!, alertsCard, historyCard),
                listOf(journalCard, diagCard),
            ))
        }

        updatedText = label(getString(R.string.widget_not_updated), 9f, DIM, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(3))
        }
        page.addView(updatedText)
        page.addView(label(getString(R.string.main_read_only), 9f, DIM, false).apply { gravity = Gravity.CENTER })
        page.addView(label(getString(R.string.main_refresh_note), 8f, DIM, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        return scroll
    }

    private fun renderAlerts(alerts: List<Alert>) {
        alertsList.removeAllViews()
        alertsCard.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
        alerts.take(MAX_ALERT_ROWS).forEachIndexed { index, alert ->
            val color = when (alert.severity) {
                AlertSeverity.CRITICAL -> CORAL
                AlertSeverity.WARN -> AMBER
                AlertSeverity.INFO -> MINT
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, if (index == 0) 0 else dp(7), 0, 0)
            }
            row.addView(label("●", 10f, color, false).apply { setPadding(0, dp(1), dp(7), 0) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(alert.title, 12f, color, true))
            texts.addView(label(alert.text, 10f, MUTED, false))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            alertsList.addView(row)
        }
    }

    private fun metricRow(leftKey: String, leftLabel: Int, rightKey: String, rightLabel: Int): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(metricColumn(getString(leftLabel), leftKey), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(metricColumn(getString(rightLabel), rightKey), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        return row
    }

    private fun metricColumn(caption: String, key: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(SURFACE_RAISED, dp(14), STROKE)
        }
        box.addView(label(caption, 9f, MUTED, false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        val value = label("—", 14f, TEXT, true).apply {
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        metricValues[key] = value
        box.addView(value)
        return box
    }

    /** Equal-width, top-aligned columns; each card keeps its own height. */
    private fun columns(vararg groups: List<View>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        groups.forEachIndexed { index, views ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            views.forEach { column.addView(it, sectionMargin()) }
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(6)
                if (index < groups.size - 1) marginEnd = dp(6)
            })
        }
        return row
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(SURFACE, dp(18), STROKE)
    }

    private fun sectionTitle(value: String) = label(value, 12f, TEXT, true).apply { setPadding(0, 0, 0, dp(8)) }

    private fun label(value: String, sizeSp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = sizeSp * spec.textScale
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun actionButton(value: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 11f * spec.textScale
        isAllCaps = false
        setTextColor(if (filled) BG else TEXT)
        background = rounded(if (filled) AMBER else SURFACE_RAISED, dp(13), if (filled) AMBER else STROKE)
        minHeight = dp(40)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun render(snapshot: BatterySnapshot?, state: WidgetFetchState) {
        val warning = snapshot?.let(BatteryWarningResolver::resolve)
        statusText.text = when (state) {
            WidgetFetchState.NEEDS_SIGN_IN -> getString(R.string.main_status_sign_in)
            WidgetFetchState.ERROR -> getString(R.string.main_status_offline)
            WidgetFetchState.NEVER -> getString(R.string.main_status_waiting)
            WidgetFetchState.STALE -> getString(R.string.main_status_stale)
            WidgetFetchState.OK -> getString(R.string.main_status_online)
        }
        statusText.setTextColor(when (state) {
            WidgetFetchState.ERROR -> CORAL
            WidgetFetchState.NEEDS_SIGN_IN, WidgetFetchState.NEVER, WidgetFetchState.STALE -> AMBER
            WidgetFetchState.OK -> MINT
        })
        signInBanner.visibility = if (!showcase && needsAccess() && (!hasAccessCookie() || state == WidgetFetchState.NEEDS_SIGN_IN)) View.VISIBLE else View.GONE

        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Kyiv"))
        val linkLost = state == WidgetFetchState.ERROR
        val light = LightStatusResolver.resolve(snapshot, linkLost, now)
        socValue.text = snapshot?.socPercent?.let { getString(R.string.main_soc_value, it) } ?: "—%"
        socValue.setTextColor(if ((snapshot?.socPercent ?: 100) < 20) CORAL else MINT)
        socProgress.setSoc(snapshot?.socPercent)
        val temperature = (snapshot?.temperatureCelsius ?: snapshot?.insights?.temperatureCelsius)?.let(WidgetSummaryResolver::formatTemperature)
        capacityLine.text = listOfNotNull(
            if (snapshot?.batteryCapacityWh != null) getString(R.string.widget_capacity_value, BatteryDisplayFormatter.formatKwh(snapshot.batteryCapacityWh), BatteryDisplayFormatter.formatKwh(snapshot.ratedCapacityWh))
            else getString(R.string.widget_capacity_unknown),
            temperature,
        ).joinToString(" · ")

        val summary = snapshot?.let { WidgetSummaryResolver.resolve(it, now, linkLost) }
        val info = snapshot?.powerOutage
        lightDot.setTextColor(WidgetRenderer.lightColor(light.gridState))
        lightHeadline.text = if (snapshot == null && !linkLost) getString(R.string.widget_power_status_unknown) else light.headline
        lightHeadline.setTextColor(when (light.kind) {
            LightKind.ON -> POWER_ON
            LightKind.OFF -> AMBER
            LightKind.NO_LINK -> CORAL
            LightKind.UNKNOWN -> MUTED
        })
        stateLine.text = when {
            snapshot == null -> when (state) {
                WidgetFetchState.NEEDS_SIGN_IN -> getString(R.string.main_dashboard_needs_signin)
                WidgetFetchState.ERROR -> getString(R.string.main_dashboard_error)
                else -> getString(R.string.main_dashboard_no_data)
            }
            light.kind == LightKind.NO_LINK -> "Дані на екрані — останні збережені"
            warning != null && warning != BatteryWarning.HIGH_POWER -> warningLabel(warning)
            else -> summary?.batteryState?.takeIf { light.kind != LightKind.OFF } ?: directionWord(snapshot)
        }
        stateLine.setTextColor(if (warning != null && warning != BatteryWarning.HIGH_POWER) AMBER else MUTED)

        // One big line: runtime during an outage, ETA while moving, «Заряджено» when full, reserve when idle.
        val reserve = summary?.lines?.firstOrNull { it.kind == SummaryLineKind.RESERVE }
        val runtime = info?.runtimeSeconds
        var eta: String? = null
        var etaColor = TEXT
        var sub: String? = null
        when {
            light.kind == LightKind.NO_LINK -> {
                eta = "Стан світла невідомий"; etaColor = MUTED
                sub = listOfNotNull(light.hint?.replaceFirstChar { it.titlecase() }, info?.let { PowerOutageFormatter.detail(it, now, GridState.UNKNOWN) }).joinToString(" · ")
            }
            light.kind == LightKind.OFF -> {
                val covers = info?.coversOutage
                // «≈»: the runtime follows the 10-min average load (a cycling compressor), not the last reading.
                val approx = if (info?.loadBasis == com.doloh.marstekwidget.direct.AutonomyCalculator.MEASURED_BACKUP_AVG) "≈ " else ""
                eta = when {
                    runtime == null -> reserve?.text ?: "Запас: оцінюю навантаження…"
                    covers == false -> "Вистачить лише на $approx${EtaTextFormatter.formatSeconds(runtime)}"
                    else -> "Вистачить на $approx${EtaTextFormatter.formatSeconds(runtime)}"
                }
                etaColor = if (covers == false) CORAL else POWER_ON
                // Only a positive backup-output reading is the house load (negative = noise). 0 W while the light is
                // still off = a load pause (or a trip): «Світла нема · навантаження зараз 0 Вт».
                val backup = WidgetSummaryResolver.backupLoadWatts(snapshot)
                sub = listOfNotNull(
                    // The overload trip is already the «На що звернути увагу» card; not repeated here.
                    backup?.let { "Споживання ${it.roundToInt()} Вт" } ?: "навантаження зараз 0 Вт",
                    info?.currentEnd?.let { PowerOutageFormatter.shortDetail(info, now)?.replace("до ", "світло за графіком о ") } ?: "час повернення невідомий",
                ).joinToString(" · ")
            }
            summary?.etaText != null -> {
                eta = summary.etaText
                etaColor = if (summary.etaText == "Заряджено") POWER_ON else TEXT
                sub = info?.let { PowerOutageFormatter.detail(it, now) }
            }
            else -> {
                eta = runtime?.let { "Запасу на ${EtaTextFormatter.formatSeconds(it)}" }
                sub = info?.let { PowerOutageFormatter.detail(it, now) }
            }
        }
        // The schedule rows below already say «не опубліковано» / «без відключень» — do not repeat it in the hero.
        val days = ScheduleDays.days(info, System.currentTimeMillis())
        val shownBySchedule = if (info != null && info.scheduleEnabled && !info.scheduleUnavailable)
            setOf(days[0].windowsText, "Графік не опубліковано", "За графіком без відключень") else emptySet()
        if (sub in shownBySchedule) sub = null
        etaLine.text = eta.orEmpty()
        etaLine.visibility = if (eta.isNullOrBlank()) View.GONE else View.VISIBLE
        etaLine.setTextColor(etaColor)
        subLine.text = sub.orEmpty()
        subLine.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE

        val today = snapshot?.insights?.today
        metricValues["todayCharge"]?.text = TodayFormatter.short(today?.chargeSeconds)
        metricValues["todayDischarge"]?.text = TodayFormatter.short(today?.dischargeSeconds)
        metricValues["todayOutage"]?.text = TodayFormatter.short(today?.outageSeconds)
        metricValues["todayChargeKwh"]?.apply {
            text = TodayFormatter.energy(today?.chargeWh, today?.chargeEstimated == true) ?: "—"
            contentDescription = TodayFormatter.chargedText(today)
        }
        metricValues["todayDischargeKwh"]?.apply {
            text = TodayFormatter.energy(today?.dischargeWh, today?.dischargeEstimated == true) ?: "—"
            contentDescription = TodayFormatter.dischargedText(today)
        }
        metricValues["todayOutageKwh"]?.apply {
            // energy the house took from the backup output today (all of it during outages)
            text = snapshot?.insights?.backupEnergyTodayWh?.takeIf { (today?.outageSeconds ?: 0L) > 0L }?.let { TodayFormatter.energy(it) } ?: "—"
        }
        val modeName = snapshot?.operatingMode
        modeChip.visibility = if (modeName == null) View.GONE else View.VISIBLE
        modeChip.text = modeName?.let { OperatingModeFormatter.format(it) }.orEmpty()
        modeChip.contentDescription = modeName?.let { "Режим роботи: ${OperatingModeFormatter.format(it)}" }
        // No «— — —» tiles: without today's data the row is hidden and a short note says why.
        todayTiles.visibility = if (today == null) View.GONE else View.VISIBLE
        todayCaption.text = if (today == null) {
            if (snapshot == null) "" else "Тривалості за сьогодні (заряд, розряд, без світла) з'являться, коли збереться історія"
        } else "Сьогодні з 00:00 · проміжки без даних не враховано"
        todayCaption.visibility = if (todayCaption.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Schedule: today / tomorrow rows + windows text (nothing repeated from the hero).
        val hasSchedule = info != null && info.scheduleEnabled && !info.scheduleUnavailable
        val actual = ActualDays.day(snapshot?.insights?.lightTimeline, System.currentTimeMillis())
        scheduleDays.visibility = if (hasSchedule || actual != null) View.VISIBLE else View.GONE
        scheduleDays.setDays(if (hasSchedule) days else emptyList(), actual, ActualDays.scheduleNowText(info, now))
        scheduleCaption.text = listOfNotNull("Графік відключень", info?.group?.let { "черга $it" }).joinToString(" · ") + "  ›"
        val nextText = info?.takeIf { light.kind != LightKind.OFF && light.kind != LightKind.NO_LINK }?.let { PowerOutageFormatter.detail(it, now) }
            ?.takeIf { it != sub && it !in shownBySchedule }
        outageLine.text = nextText.orEmpty()
        outageLine.visibility = if (nextText.isNullOrBlank()) View.GONE else View.VISIBLE
        val reserveText = reserve?.text?.takeIf { light.kind == LightKind.ON && it != eta }
        reserveLine.visibility = if (reserveText == null) View.GONE else View.VISIBLE
        reserveLine.text = reserveText.orEmpty()
        reserveLine.setTextColor(toneColor(reserve?.tone))

        val history = snapshot?.insights?.socHistory.orEmpty()
        historyCard.visibility = if (history.size >= 2) View.VISIBLE else View.GONE
        if (history.size >= 2) {
            sparkline.setInput(SocChartPainter.Input(history, snapshot?.insights?.outagePeriods.orEmpty(),
                snapshot?.insights?.chartEvents.orEmpty(), info?.floorPct, System.currentTimeMillis()))
            val density = resources.displayMetrics.density
            val chartWidth = (sparkline.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(60))) - 30 * density
            val nowMs = System.currentTimeMillis()
            val clusters = ChartEventClusters.cluster(snapshot?.insights?.chartEvents.orEmpty().filter { it.epochMillis >= nowMs - 24 * 3600_000L },
                com.doloh.marstekwidget.ui.SocChartPainter.markerPx(density)) { t -> ((t - (nowMs - 24 * 3600_000L)).toFloat() / (24 * 3600_000f)) * chartWidth }
            historyCaption.text = (listOfNotNull(
                getString(R.string.main_history_range, history.minOf { it.soc }, history.maxOf { it.soc }, history.size),
                "заштриховано — без світла",
                info?.floorPct?.let { "червона лінія — резерв розряду ${it.roundToInt()}%" },
                "цифра на мітці — кілька подій поруч (торкніться графіка — журнал)".takeIf { clusters.any { it.count > 1 } },
            ).joinToString(" · ") + ChartEventClusters.legend(clusters).joinToString("") { "\n• $it" })
        }

        metricValues["battery"]?.text = BatteryDisplayFormatter.formatPowerKw(snapshot?.batteryPowerWatts?.takeIf {
            snapshot.direction == com.doloh.marstekwidget.domain.BatteryDirection.CHARGING ||
                snapshot.direction == com.doloh.marstekwidget.domain.BatteryDirection.DISCHARGING
        })
        metricValues["temperature"]?.text = temperature ?: "—"
        metricValues["grid"]?.text = BatteryDisplayFormatter.formatSignedWatts(snapshot?.gridPowerWatts)
        metricValues["backup"]?.text = BatteryDisplayFormatter.formatSignedWatts(snapshot?.insights?.backupOutputWatts ?: snapshot?.offgridPowerWatts)
        // Mean of REAL load only (pauses at 0 Вт excluded), and only once ≥ 30 min of load is behind it — the old 24 h
        // average counted every idle hour and read a misleading «7 Вт».
        metricValues["backupAvg"]?.text = snapshot?.insights?.takeIf {
            it.backupLoadCoverage7dSeconds >= com.doloh.marstekwidget.domain.AlertEvaluator.LOAD_MEAN_MIN_COVERAGE_SECONDS
        }?.backupLoadMean7dWatts?.let { "≈ " + WidgetSummaryResolver.formatWatts(it) } ?: "—"
        metricValues["backupToday"]?.text = BatteryDisplayFormatter.formatKwh(snapshot?.insights?.backupEnergyTodayWh)
        metricValues["energyGridIn"]?.text = BatteryDisplayFormatter.formatKwh(snapshot?.totalGridInputEnergyWh)
        metricValues["energyGridOut"]?.text = BatteryDisplayFormatter.formatKwh(snapshot?.totalGridOutputEnergyWh)
        metricValues["energyLoad"]?.text = BatteryDisplayFormatter.formatKwh(snapshot?.totalLoadEnergyWh)
        val mode = OperatingModeFormatter.format(snapshot?.operatingMode)
        modeMeta.text = when (snapshot?.ctState) {
            1 -> getString(R.string.main_mode_ct, mode, getString(R.string.main_ct_connected))
            0 -> getString(R.string.main_mode_ct, mode, "—")
            else -> getString(R.string.main_mode, mode)
        }
        diagnosticsText.text = listOfNotNull(
            "Підключення: " + if (settings.mode == ConnectionMode.SERVER) "сервер" else "напряму (UDP, лише читання)",
            settings.deviceModel?.let { "Модель: $it" },
            WidgetPreferences(this).errorMessage()?.takeIf { state == WidgetFetchState.ERROR }?.let { "Остання помилка: $it" },
            snapshot?.operatingMode?.let { "Режим роботи (ES.GetMode): ${OperatingModeFormatter.format(it)}" },
            snapshot?.insights?.standbyWatts?.let { "Власне споживання в очікуванні ≈ ${Math.round(it)} Вт (за ритмом підзаряду до 100%)" },
            snapshot?.insights?.lastGridChargeWatts?.let { w ->
                "Останній підзаряд від мережі: ${Math.round(w)} Вт" + (snapshot.insights?.lastGridChargeMillis?.let { " о ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))}" } ?: "")
            },
            snapshot?.insights?.backupAverageCoverageHours?.let { "Історія резервного виходу: ${String.format(Locale.getDefault(), "%.1f", it)} год за 24 год" },
        ).joinToString("\n")

        renderPower(snapshot)
        renderAlerts(AlertNotifier.activeAlerts(this))
        renderJournalPreview()
        val lastFetchedAt = WidgetPreferences(this).lastFetchedAtMillis()
        val collected = snapshot?.collectedAt?.let(::formatCollectedAt)
            ?: lastFetchedAt.takeIf { it > 0L }?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
            ?: "—"
        updatedText.text = when (state) {
            WidgetFetchState.STALE -> getString(R.string.widget_stale_at, collected)
            WidgetFetchState.ERROR, WidgetFetchState.NEEDS_SIGN_IN -> getString(R.string.widget_cached_at, collected)
            WidgetFetchState.NEVER, WidgetFetchState.OK -> getString(R.string.main_status_updated, collected)
        }
    }

    private fun directionWord(snapshot: BatterySnapshot?): String = when (snapshot?.direction) {
        com.doloh.marstekwidget.domain.BatteryDirection.CHARGING -> "Заряджається"
        com.doloh.marstekwidget.domain.BatteryDirection.DISCHARGING -> "Розряджається від батареї"
        com.doloh.marstekwidget.domain.BatteryDirection.IDLE -> "Очікування"
        else -> "Стан батареї невідомий"
    }

    private fun renderJournalPreview() {
        if (!::journalPreview.isInitialized) return
        val events = if (showcase) (intent.getStringExtra("showcase_events")?.let { raw ->
            runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).mapNotNull { com.doloh.marstekwidget.domain.JournalEvent.fromJson(a.getJSONObject(it)) } } }.getOrNull()
        } ?: JournalActivity.demoEvents()) else if (settings.mode == ConnectionMode.DIRECT) runCatching { JournalStore(this).events(3) }.getOrDefault(emptyList()) else serverJournal
        journalPreview.removeAllViews()
        if (events.isEmpty()) {
            journalPreview.addView(label("Подій ще немає", 10f, DIM, false))
            return
        }
        events.take(3).forEach { e ->
            journalPreview.addView(label("${JournalExport.time(e.tsMillis).substring(5, 16)} · ${e.category.title}", 9f, DIM, false))
            journalPreview.addView(label(e.text, 11f, TEXT, false).apply { setPadding(0, 0, 0, dp(5)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        }
    }

    private fun loadServerJournal() {
        if (showcase || settings.mode != ConnectionMode.SERVER) return
        val current = settings
        scope.launch {
            val events = runCatching { MarstekApiClient(this@MainActivity, current, SettingsStore(this@MainActivity).token()).fetchEvents(3) }.getOrNull()
            if (events != null) {
                serverJournal = events
                renderJournalPreview()
            }
        }
    }

    private fun toneColor(tone: SummaryTone?) = when (tone) {
        SummaryTone.CRITICAL -> CORAL
        SummaryTone.GOOD -> POWER_ON
        else -> TEXT
    }

    private fun renderPower(snapshot: BatterySnapshot?) {
        val info = snapshot?.powerOutage
        if (info == null) {
            powerGroup.visibility = View.GONE
            powerMaybe.visibility = View.GONE
            powerNote.text = getString(R.string.main_power_unavailable)
            return
        }
        val regionName = OutageRegions.byId(settings.outageRegionId ?: info.regionId)?.name
        // The queue is already in the schedule caption; here only region + source.
        powerGroup.text = listOfNotNull(regionName, PowerOutageFormatter.source(info)).joinToString(" · ")
        powerGroup.visibility = if (powerGroup.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // «Можливе …» is listed under each day of the schedule rows.
        powerMaybe.visibility = View.GONE
        val watts = (info.loadWatts ?: 0.0).toInt()
        val floor = info.floorPct?.let { " Рахується енергія понад резерв розряду ${it.roundToInt()}% (налаштування)." }.orEmpty()
        val basis = when (info.loadBasis) {
            "measured_backup_avg_10min" -> "За середнім навантаженням резервного виходу за 10 хв ≈ $watts Вт."
            "measured_backup_output" -> "За виміряним навантаженням резервного виходу $watts Вт."
            "measured_discharge" -> getString(R.string.main_power_basis_measured, watts)
            "measured_average_24h" -> getString(R.string.main_power_basis_measured_24h, watts)
            "configured_average" -> getString(R.string.main_power_basis_configured, watts)
            else -> getString(R.string.main_power_basis_missing)
        }
        powerNote.text = (if (info.sourceError != null) "$basis ${getString(R.string.main_power_source_error)}" else basis) + floor
    }

    private fun warningLabel(warning: BatteryWarning): String = when (warning) {
        BatteryWarning.CRITICAL_TEMPERATURE -> getString(R.string.widget_warning_critical_temp)
        BatteryWarning.HIGH_TEMPERATURE -> getString(R.string.widget_warning_high_temp)
        BatteryWarning.LOW_SOC -> getString(R.string.widget_warning_low_soc)
        BatteryWarning.HIGH_POWER -> getString(R.string.widget_warning_high_power)
    }

    private fun refreshTelemetry() {
        if (refreshing || showcase) return
        if (needsAccess() && !hasAccessCookie()) {
            WidgetPreferences(this).setNeedsSignIn()
            render(WidgetPreferences(this).snapshot(), WidgetFetchState.NEEDS_SIGN_IN)
            return
        }
        refreshing = true
        refreshButton.alpha = 0.4f
        scope.launch {
            try {
                val result = TelemetryRepository.refreshAndStore(this@MainActivity)
                val preferences = WidgetPreferences(this@MainActivity)
                when (result) {
                    is TelemetryFetchResult.Success -> render(result.snapshot, preferences.state())
                    TelemetryFetchResult.NeedsSignIn -> render(preferences.snapshot(), WidgetFetchState.NEEDS_SIGN_IN)
                    is TelemetryFetchResult.Failure -> render(preferences.snapshot(), preferences.state())
                }
            } finally {
                refreshing = false
                refreshButton.alpha = 1f
            }
        }
    }

    /** API timestamps carry an offset (usually UTC); show them in the phone's local time. */
    private fun formatCollectedAt(value: String): String = runCatching {
        val instant = java.time.OffsetDateTime.parse(value).toInstant()
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(instant.toEpochMilli()))
    }.getOrDefault(value)

    private fun sectionMargin() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    private fun rowMargin() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }

    private fun needsAccess(): Boolean = settings.let { it.mode == ConnectionMode.SERVER && it.serverAuth == ServerAuth.CF_ACCESS }

    private fun hasAccessCookie(): Boolean =
        AccessCookie.present(settings.serverUrl?.let(ServerUrlValidator::normalize)?.let(ServerUrlValidator::statusUrl))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Direct mode polls the battery like the server: every 15 s (a poll = up to 4 paced requests). */
        const val DIRECT_INTERVAL_MS = 15_000L
        const val SERVER_INTERVAL_MS = 30_000L
        const val MAX_ALERT_ROWS = 4
        val BG = Color.rgb(7, 21, 34)
        val SURFACE = Color.rgb(21, 31, 42)
        val SURFACE_RAISED = Color.rgb(28, 41, 53)
        val STROKE = Color.rgb(42, 61, 75)
        val TEXT = Color.rgb(243, 247, 250)
        val MUTED = Color.rgb(160, 173, 184)
        val DIM = Color.rgb(113, 128, 141)
        val MINT = Color.rgb(54, 198, 213)
        val AMBER = Color.rgb(245, 183, 46)
        val POWER_ON = Color.rgb(127, 227, 178)
        val CORAL = Color.rgb(255, 128, 111)
    }
}
