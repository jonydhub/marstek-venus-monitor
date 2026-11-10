package com.doloh.marstekwidget.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.data.AccessCookie
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
    private lateinit var stateLine: TextView
    private lateinit var capacityLine: TextView
    private lateinit var socProgress: ProgressBar
    private lateinit var etaLine: TextView
    private lateinit var outageLine: TextView
    private lateinit var reserveLine: TextView
    private lateinit var heroMeta: TextView
    private lateinit var historyCard: LinearLayout
    private lateinit var sparkline: SocSparklineView
    private lateinit var historyCaption: TextView
    private lateinit var powerGroup: TextView
    private lateinit var powerNote: TextView
    private lateinit var modeMeta: TextView
    private lateinit var permissionHint: TextView
    private lateinit var updatedText: TextView
    private lateinit var refreshButton: Button
    private lateinit var alertsCard: LinearLayout
    private lateinit var alertsList: LinearLayout
    private lateinit var powerMaybe: TextView
    private lateinit var webButton: Button
    private var refreshing = false
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
        val content = buildDashboard()
        SystemBarsInsetHelper.apply(content, 14, 10, 14, 18)
        setContentView(content)
        render(WidgetPreferences(this).snapshot(), WidgetPreferences(this).state())
    }

    override fun onResume() {
        super.onResume()
        render(WidgetPreferences(this).snapshot(), WidgetPreferences(this).state())
        updatePermissionHint()
        refreshTelemetry()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionHint()
    }

    private fun buildDashboard(): ScrollView {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(18))
            setBackgroundColor(BG)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(BG)
            addView(page, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // One-row header: icon · brand · status.
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
            maxWidth = dp(150)
        }
        header.addView(statusText)
        header.addView(label("⚙", 20f, MUTED, false).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.main_settings)
            setPadding(dp(12), dp(2), dp(4), dp(2))
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
        page.addView(alertsCard, sectionMargin())

        // Hero: SOC + light state + ETA + outage.
        val hero = card()
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val socColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        socColumn.addView(label(getString(R.string.main_hero_soc), 8f, MUTED, true))
        socValue = label("—%", 46f, MINT, true).apply { includeFontPadding = false }
        socColumn.addView(socValue)
        heroRow.addView(socColumn)
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
        lightHeadline = label(getString(R.string.widget_power_status_unknown), 17f, TEXT, true)
        lightRow.addView(lightHeadline)
        stateColumn.addView(lightRow)
        stateLine = label(getString(R.string.main_dashboard_loading), 12f, MUTED, true).apply { setPadding(0, dp(3), 0, 0) }
        stateColumn.addView(stateLine)
        heroRow.addView(stateColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow)
        socProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(MINT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(48, 68, 82))
        }
        hero.addView(socProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
        capacityLine = label("— kWh", 10f, MUTED, false).apply { setPadding(0, dp(4), 0, 0) }
        hero.addView(capacityLine)
        etaLine = label(getString(R.string.main_eta_none), 15f, TEXT, true).apply { setPadding(0, dp(10), 0, 0) }
        hero.addView(etaLine)
        outageLine = label("", 13f, TEXT, false).apply { setPadding(0, dp(4), 0, 0) }
        hero.addView(outageLine)
        powerMaybe = label("", 12f, AMBER, false).apply { setPadding(0, dp(3), 0, 0); visibility = View.GONE }
        hero.addView(powerMaybe)
        reserveLine = label("", 13f, TEXT, true).apply { setPadding(0, dp(3), 0, 0) }
        hero.addView(reserveLine)
        heroMeta = label("", 10f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) }
        hero.addView(heroMeta)
        // Light-schedule context (region · queue · source, forecast basis) lives in the hero as small print.
        powerGroup = label("", 9f, MUTED, false).apply { setPadding(0, dp(6), 0, 0) }
        hero.addView(powerGroup)
        powerNote = label(getString(R.string.main_power_unavailable), 9f, DIM, false).apply { setPadding(0, dp(2), 0, 0) }
        hero.addView(powerNote)
        page.addView(hero, sectionMargin())

        // 24 h SOC sparkline.
        historyCard = card()
        historyCard.addView(sectionTitle(getString(R.string.main_history_title)))
        sparkline = SocSparklineView(this)
        historyCard.addView(sparkline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
        historyCaption = label("", 9f, DIM, false).apply { setPadding(0, dp(5), 0, 0) }
        historyCard.addView(historyCaption)
        page.addView(historyCard, sectionMargin())

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = actionButton(getString(R.string.main_refresh), filled = false) { refreshTelemetry() }
        buttonRow.addView(refreshButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        webButton = actionButton(getString(R.string.main_open_web_dashboard), filled = false) {
            startActivity(Intent(this@MainActivity, DashboardWebActivity::class.java))
        }
        buttonRow.addView(webButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        if (settings.mode != ConnectionMode.SERVER) webButton.visibility = View.GONE
        page.addView(buttonRow, sectionMargin())

        // Battery & flows.
        val flowCard = card()
        flowCard.addView(sectionTitle(getString(R.string.main_details_title)))
        flowCard.addView(metricRow("battery", R.string.main_battery_flow, "temperature", R.string.main_temperature))
        flowCard.addView(metricRow("backup", R.string.main_backup, "grid", R.string.main_grid), rowMargin())
        flowCard.addView(metricRow("backupAvg", R.string.main_backup_avg, "backupToday", R.string.main_backup_today), rowMargin())
        modeMeta = label(getString(R.string.main_mode, "—"), 9f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) }
        flowCard.addView(modeMeta)
        flowCard.addView(label(getString(R.string.main_grid_sign_note), 9f, DIM, false).apply { setPadding(0, dp(4), 0, 0) })
        page.addView(flowCard, sectionMargin())

        // Notifications.
        val notifyCard = card()
        notifyCard.addView(sectionTitle(getString(R.string.main_notify_title)))
        val preferences = WidgetPreferences(this)
        val settings = preferences.alertSettings()
        listOf(
            AlertGroup.LIGHT_SCHEDULE to (R.string.main_notify_group_light to R.string.main_notify_group_light_hint),
            AlertGroup.RESERVE to (R.string.main_notify_group_reserve to R.string.main_notify_group_reserve_hint),
            AlertGroup.CHARGING to (R.string.main_notify_group_charging to R.string.main_notify_group_charging_hint),
            AlertGroup.CONSUMPTION to (R.string.main_notify_group_consumption to R.string.main_notify_group_consumption_hint),
            AlertGroup.BATTERY_HEALTH to (R.string.main_notify_group_health to R.string.main_notify_group_health_hint),
        ).forEach { (group, labels) ->
            val switch = Switch(this).apply {
                text = getString(labels.first)
                isChecked = group in settings.enabledGroups
                textSize = 13f
                setTextColor(TEXT)
                thumbTintList = ColorStateList.valueOf(MINT)
                setPadding(0, dp(4), 0, 0)
                setOnCheckedChangeListener { _, checked -> onNotifyToggled(group, checked) }
            }
            notifyCard.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            notifyCard.addView(label(getString(labels.second), 9f, DIM, false).apply { setPadding(0, 0, dp(56), dp(4)) })
        }
        notifyCard.addView(label(getString(R.string.main_notify_high_load_label), 10f, MUTED, true).apply { setPadding(0, dp(8), 0, 0) })
        notifyCard.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.highLoadThresholdWatts.toString())
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(DIM)
            hint = "200"
            maxLines = 1
            backgroundTintList = ColorStateList.valueOf(STROKE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.takeIf { it in 1..10_000 }?.let { WidgetPreferences(this@MainActivity).setHighLoadThreshold(it) }
                }
            })
        }, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        notifyCard.addView(label(getString(R.string.main_notify_high_load_note), 9f, DIM, false))
        permissionHint = label(getString(R.string.main_notify_permission), 10f, AMBER, true).apply {
            setPadding(0, dp(6), 0, 0)
            setOnClickListener { requestNotificationPermission() }
        }
        notifyCard.addView(permissionHint)
        notifyCard.addView(label(getString(R.string.main_notify_note), 9f, DIM, false).apply { setPadding(0, dp(6), 0, 0) })
        page.addView(notifyCard, sectionMargin())

        // Accumulated counters (moved off the large widget).
        val energyCard = card()
        energyCard.addView(sectionTitle(getString(R.string.main_energy_title)))
        energyCard.addView(metricRow("energyGridIn", R.string.main_energy_grid_in, "energyGridOut", R.string.main_energy_grid_out))
        energyCard.addView(metricColumn(getString(R.string.main_energy_load), "energyLoad"), rowMargin())
        energyCard.addView(label(getString(R.string.main_energy_moved_note), 9f, DIM, false).apply { setPadding(0, dp(6), 0, 0) })
        page.addView(energyCard, sectionMargin())

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

    private fun onNotifyToggled(group: AlertGroup, checked: Boolean) {
        val preferences = WidgetPreferences(this)
        preferences.setAlertGroupEnabled(group, checked)
        if (preferences.anyAlertsEnabled()) WidgetRefreshScheduler.ensurePeriodic(this)
        if (checked) requestNotificationPermission()
        updatePermissionHint()
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun updatePermissionHint() {
        if (!::permissionHint.isInitialized) return
        val wanted = WidgetPreferences(this).anyAlertsEnabled()
        permissionHint.visibility = if (wanted && !AlertNotifier.canPost(this)) View.VISIBLE else View.GONE
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

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(SURFACE, dp(18), STROKE)
    }

    private fun sectionTitle(value: String) = label(value, 12f, TEXT, true).apply { setPadding(0, 0, 0, dp(8)) }

    private fun label(value: String, sizeSp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun actionButton(value: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 11f
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

        socValue.text = snapshot?.socPercent?.let { getString(R.string.main_soc_value, it) } ?: "—%"
        socValue.setTextColor(when {
            (snapshot?.socPercent ?: 100) < 20 -> CORAL
            else -> MINT
        })
        socProgress.progress = snapshot?.socPercent ?: 0
        capacityLine.text = if (snapshot?.batteryCapacityWh != null) {
            getString(R.string.widget_capacity_value, BatteryDisplayFormatter.formatKwh(snapshot.batteryCapacityWh), BatteryDisplayFormatter.formatKwh(snapshot.ratedCapacityWh))
        } else getString(R.string.widget_capacity_unknown)

        val summary = snapshot?.let { WidgetSummaryResolver.resolve(it) }
        val info = snapshot?.powerOutage
        lightDot.setTextColor(WidgetRenderer.lightColor(info?.gridState ?: GridState.UNKNOWN))
        lightHeadline.text = info?.let(PowerOutageFormatter::headline) ?: getString(R.string.widget_power_status_unknown)
        lightHeadline.setTextColor(when (info?.gridState) {
            GridState.ON -> POWER_ON
            GridState.OFF -> AMBER
            else -> MUTED
        })
        stateLine.text = when {
            snapshot == null -> when (state) {
                WidgetFetchState.NEEDS_SIGN_IN -> getString(R.string.main_dashboard_needs_signin)
                WidgetFetchState.ERROR -> getString(R.string.main_dashboard_error)
                else -> getString(R.string.main_dashboard_no_data)
            }
            warning != null && warning != BatteryWarning.HIGH_POWER -> warningLabel(warning)
            else -> summary?.batteryState.orEmpty()
        }
        stateLine.setTextColor(if (warning != null && warning != BatteryWarning.HIGH_POWER) AMBER else MUTED)

        val reserve = summary?.lines?.firstOrNull { it.kind == SummaryLineKind.RESERVE }
        val outage = summary?.mode == SummaryMode.OUTAGE
        // During an outage the hero shows ONE runtime (autonomy, else ETA to 0%) with its verdict.
        val etaText = if (outage) reserve?.text else summary?.lines?.firstOrNull { it.kind == SummaryLineKind.ETA }?.text
        etaLine.text = (if (outage) etaText else summary?.etaText) ?: getString(R.string.main_eta_none)
        etaLine.setTextColor(if (outage) toneColor(reserve?.tone) else TEXT)
        val outageText = summary?.lines?.firstOrNull { it.kind == SummaryLineKind.RETURN_TIME }?.text
            ?: info?.let { PowerOutageFormatter.detail(it) }
        outageLine.visibility = if (outageText == null) View.GONE else View.VISIBLE
        outageLine.text = outageText.orEmpty()
        reserveLine.visibility = if (reserve == null || outage) View.GONE else View.VISIBLE
        reserveLine.text = reserve?.text.orEmpty()
        reserveLine.setTextColor(toneColor(reserve?.tone))
        val temperature = (snapshot?.temperatureCelsius ?: snapshot?.insights?.temperatureCelsius)?.let(WidgetSummaryResolver::formatTemperature)
        heroMeta.text = listOfNotNull(
            temperature?.let { "${getString(R.string.main_temperature)} $it" },
            // In an outage the state line already names the backup output; don't repeat it.
            (snapshot?.insights?.backupOutputWatts ?: snapshot?.offgridPowerWatts)?.takeIf { !outage }?.let { "${getString(R.string.main_backup)} ${WidgetSummaryResolver.formatWatts(it)}" },
        ).joinToString(" · ")
        heroMeta.visibility = if (heroMeta.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        val history = snapshot?.insights?.socHistory.orEmpty()
        historyCard.visibility = if (history.size >= 2) View.VISIBLE else View.GONE
        if (history.size >= 2) {
            sparkline.setPoints(history)
            historyCaption.text = getString(R.string.main_history_range, history.minOf { it.soc }, history.maxOf { it.soc }, history.size)
        }

        metricValues["battery"]?.text = BatteryDisplayFormatter.formatPowerKw(snapshot?.batteryPowerWatts?.takeIf {
            snapshot.direction == com.doloh.marstekwidget.domain.BatteryDirection.CHARGING ||
                snapshot.direction == com.doloh.marstekwidget.domain.BatteryDirection.DISCHARGING
        })
        metricValues["temperature"]?.text = temperature ?: "—"
        metricValues["grid"]?.text = BatteryDisplayFormatter.formatSignedWatts(snapshot?.gridPowerWatts)
        metricValues["backup"]?.text = BatteryDisplayFormatter.formatSignedWatts(snapshot?.insights?.backupOutputWatts ?: snapshot?.offgridPowerWatts)
        metricValues["backupAvg"]?.text = snapshot?.insights?.backupAverageWatts24h?.let(WidgetSummaryResolver::formatWatts) ?: "—"
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

        renderPower(snapshot)
        renderAlerts(AlertNotifier.activeAlerts(this))
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
        val regionName = OutageRegions.byId(settings.outageRegionId)?.name
        powerGroup.text = if (!info.scheduleEnabled) getString(R.string.main_power_group_disabled)
        else listOfNotNull(
            if (regionName != null) getString(R.string.main_power_group, regionName, info.group ?: "—") else info.group?.let { "Черга $it" },
            PowerOutageFormatter.source(info),
        ).joinToString(" · ")
        powerGroup.visibility = if (powerGroup.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val maybe = PowerOutageFormatter.maybe(info)
        powerMaybe.text = maybe.orEmpty()
        powerMaybe.visibility = if (maybe == null || maybe == outageLine.text.toString()) View.GONE else View.VISIBLE
        val watts = (info.loadWatts ?: 0.0).toInt()
        val basis = when (info.loadBasis) {
            "measured_discharge" -> getString(R.string.main_power_basis_measured, watts)
            "measured_average_24h" -> getString(R.string.main_power_basis_measured_24h, watts)
            "configured_average" -> getString(R.string.main_power_basis_configured, watts)
            else -> getString(R.string.main_power_basis_missing)
        }
        powerNote.text = if (info.sourceError != null) "$basis ${getString(R.string.main_power_source_error)}" else basis
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
        refreshButton.text = getString(R.string.main_dashboard_loading)
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
                refreshButton.text = getString(R.string.main_refresh)
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
        const val DIRECT_INTERVAL_MS = 5_000L
        const val SERVER_INTERVAL_MS = 30_000L
        const val REQUEST_NOTIFICATIONS = 41
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
