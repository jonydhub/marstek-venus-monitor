package com.doloh.marstekwidget.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.doloh.marstekwidget.data.BatteryScanner
import com.doloh.marstekwidget.data.DirectEngine
import com.doloh.marstekwidget.data.NetworkInfo
import com.doloh.marstekwidget.data.OutageRepository
import com.doloh.marstekwidget.data.TelemetryFetchResult
import com.doloh.marstekwidget.data.TelemetryRepository
import com.doloh.marstekwidget.direct.DeviceInfo
import com.doloh.marstekwidget.direct.MarstekRpc
import com.doloh.marstekwidget.direct.RpcResult
import com.doloh.marstekwidget.direct.SubnetScanPlanner
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.domain.AlertGroup
import com.doloh.marstekwidget.domain.BatteryDisplayFormatter
import com.doloh.marstekwidget.domain.JournalDetector
import com.doloh.marstekwidget.notify.AlertNotifier
import com.doloh.marstekwidget.widget.WidgetPreferences
import com.doloh.marstekwidget.outage.OfficialSources
import com.doloh.marstekwidget.outage.OutageRegions
import com.doloh.marstekwidget.settings.AppSettings
import com.doloh.marstekwidget.settings.ConnectionMode
import com.doloh.marstekwidget.settings.ServerAuth
import com.doloh.marstekwidget.settings.ServerUrlValidator
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-launch onboarding and the Settings screen (same page; onboarding adds a welcome header). */
class SettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ui: Ui
    private lateinit var store: SettingsStore
    private lateinit var draft: AppSettings
    private var onboarding = false

    private lateinit var directPanel: LinearLayout
    private lateinit var serverPanel: LinearLayout
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var cidrEdit: EditText
    private lateinit var scanStatus: TextView
    private lateinit var scanResults: LinearLayout
    private lateinit var directResult: TextView
    private lateinit var urlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var tokenBox: LinearLayout
    private lateinit var cfLogin: View
    private lateinit var serverResult: TextView
    private lateinit var regionValue: TextView
    private lateinit var queueValue: TextView
    private lateinit var sourceText: TextView
    private lateinit var thresholdEdit: EditText
    private lateinit var loadEdit: EditText
    private lateinit var ratedEdit: EditText
    private lateinit var ratedNote: TextView
    private lateinit var floorEdit: EditText
    private lateinit var errorText: TextView
    private lateinit var permissionHint: TextView
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = SettingsStore(this)
        draft = store.load()
        onboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false) || !draft.configured
        val page = ui.page()
        val scroll = ui.scrollCentered(page, fillViewport = true)
        build(page)
        SystemBarsInsetHelper.apply(scroll, 0, 0, 0, 0)
        setContentView(scroll)
        bindDraft()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(page: LinearLayout) {
        if (onboarding) {
            page.addView(ui.label("Вітаємо в Marstek Energy", 22f, Ui.TEXT, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
            page.addView(ui.label("Неофіційний монітор батареї Marstek (Venus E та сумісні). Лише читання — застосунок ніколи не керує батареєю. Налаштуйте підключення, графік відключень і запас.", 12f, Ui.MUTED))
        } else {
            page.addView(ui.label("Налаштування", 20f, Ui.TEXT, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
        }

        // 1. Connection mode
        val modeCard = ui.card()
        modeCard.addView(ui.title("1 · Підключення до батареї"))
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val direct = ui.radio("Напряму по Wi‑Fi (рекомендовано)")
        val server = ui.radio("Через сервер")
        modeGroup.addView(direct)
        modeGroup.addView(server)
        modeGroup.check(if (draft.mode == ConnectionMode.SERVER) server.id else direct.id)
        modeGroup.setOnCheckedChangeListener { _, id ->
            draft = draft.copy(mode = if (id == server.id) ConnectionMode.SERVER else ConnectionMode.DIRECT)
            updatePanels()
        }
        modeCard.addView(modeGroup)
        modeCard.addView(ui.hint("Напряму: телефон опитує батарею в домашній мережі через Marstek Open API (UDP). Через сервер: ваш власний сервер із /api/status, доступний і поза домом."))

        directPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        directPanel.addView(ui.button("Знайти батарею", filled = true) { scan() }, ui.matchWrap(8))
        scanStatus = ui.hint("Пошук відповідей Marstek.GetDevice у вашій Wi‑Fi мережі (~5 с).")
        directPanel.addView(scanStatus)
        scanResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        directPanel.addView(scanResults)
        directPanel.addView(ui.label("Або вкажіть вручну", 11f, Ui.MUTED, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostEdit = ui.field("IP батареї")
        portEdit = ui.field("Порт", numeric = true)
        row.addView(hostEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        row.addView(portEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(8) })
        directPanel.addView(row)
        cidrEdit = ui.field("Підмережа для пошуку (необов'язково)")
        directPanel.addView(cidrEdit)
        directPanel.addView(ui.hint("Напр. 192.168.0.0/24 — якщо автоматичний пошук не бачить вашу мережу (VPN, гостьова Wi‑Fi)."))
        directPanel.addView(ui.button("Перевірити з'єднання") { testDirect() }, ui.matchWrap(6))
        directResult = ui.hint("Порт Open API за замовчуванням 30000. Open API вмикається в застосунку Marstek (налаштування пристрою).")
        directPanel.addView(directResult)
        modeCard.addView(directPanel)

        serverPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        urlEdit = ui.field("https://ваш-сервер або http://IP-в-домашній-мережі:8767")
        serverPanel.addView(urlEdit, ui.matchWrap(6))
        serverPanel.addView(ui.hint("HTTP дозволено лише для адрес домашньої мережі; поза домом — HTTPS."))
        val authGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val none = ui.radio("Без авторизації")
        val bearer = ui.radio("Bearer-токен")
        val cf = ui.radio("Cloudflare Access (вхід у браузері застосунку)")
        listOf(none, bearer, cf).forEach(authGroup::addView)
        authGroup.check(when (draft.serverAuth) { ServerAuth.NONE -> none.id; ServerAuth.BEARER -> bearer.id; ServerAuth.CF_ACCESS -> cf.id })
        authGroup.setOnCheckedChangeListener { _, id ->
            draft = draft.copy(serverAuth = when (id) { bearer.id -> ServerAuth.BEARER; cf.id -> ServerAuth.CF_ACCESS; else -> ServerAuth.NONE })
            updatePanels()
        }
        serverPanel.addView(authGroup)
        tokenBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tokenEdit = ui.field("Токен", password = true)
        tokenBox.addView(tokenEdit)
        tokenBox.addView(ui.hint("Зберігається зашифрованим ключем Android Keystore."))
        serverPanel.addView(tokenBox)
        cfLogin = ui.button("Увійти через Cloudflare Access") {
            val url = ServerUrlValidator.normalize(urlEdit.text.toString())
            if (url == null || !url.startsWith("https://")) {
                serverResult.text = "Для Cloudflare Access потрібна HTTPS-адреса."
            } else {
                store.save(store.load().copy(serverUrl = url, serverAuth = ServerAuth.CF_ACCESS))
                startActivity(Intent(this, AccessLoginActivity::class.java))
            }
        }
        serverPanel.addView(cfLogin, ui.matchWrap(6))
        serverPanel.addView(ui.button("Перевірити з'єднання") { testServer() }, ui.matchWrap(6))
        serverResult = ui.hint("")
        serverPanel.addView(serverResult)
        modeCard.addView(serverPanel)
        page.addView(modeCard, ui.matchWrap(12))

        // 2. Outages
        val outageCard = ui.card()
        outageCard.addView(ui.title("2 · Графік відключень"))
        outageCard.addView(ui.hint("Світло є чи ні — визначається за резервним виходом батареї: якщо через нього йде живлення, мережі немає. Графік лише показує наступні відключення й чи вистачить запасу."))
        regionValue = ui.label("", 14f, Ui.TEXT, true)
        queueValue = ui.label("", 14f, Ui.TEXT, true)
        outageCard.addView(pickerRow("Область", regionValue) { pickRegion() })
        outageCard.addView(pickerRow("Черга", queueValue) { pickQueue() })
        sourceText = ui.hint("")
        outageCard.addView(sourceText)
        page.addView(outageCard, ui.matchWrap(10))

        // 3. Reserve
        val reserveCard = ui.card()
        reserveCard.addView(ui.title("3 · Запас і споживання"))
        reserveCard.addView(ui.label("Поріг резервного виходу, Вт", 11f, Ui.MUTED, true))
        thresholdEdit = ui.field("5", numeric = true)
        reserveCard.addView(thresholdEdit)
        reserveCard.addView(ui.hint("Живлення резервного виходу вище порогу = світла немає. За замовчуванням 5 Вт."))
        reserveCard.addView(ui.label("Середнє споживання на резерві, Вт (необов'язково)", 11f, Ui.MUTED, true).apply { setPadding(0, ui.dp(6), 0, 0) })
        loadEdit = ui.field("напр. 120", numeric = true)
        reserveCard.addView(loadEdit)
        reserveCard.addView(ui.hint("Для прогнозу запасу до першого відключення. Потім застосунок рахує сам: за фактичним розрядом або за середнім резервного виходу (≥ 30 хв вимірювань)."))
        reserveCard.addView(ui.label("Ємність батареї, Wh", 11f, Ui.MUTED, true).apply { setPadding(0, ui.dp(6), 0, 0) })
        ratedEdit = ui.field("5120", numeric = true)
        reserveCard.addView(ratedEdit)
        ratedNote = ui.hint("")
        reserveCard.addView(ratedNote)
        reserveCard.addView(ui.label("Резерв розряду (поріг BMS), %", 11f, Ui.MUTED, true).apply { setPadding(0, ui.dp(6), 0, 0) })
        floorEdit = ui.field("11", numeric = true)
        reserveCard.addView(floorEdit)
        reserveCard.addView(ui.hint("Нижче цього заряду Venus не віддає енергію (типово ≈ 11–12 %; Open API його не повідомляє). " +
            "«Вистачить на…» рахує лише енергію понад цей поріг; на графіку — червона пунктирна лінія. 0 = до 0 %."))
        page.addView(reserveCard, ui.matchWrap(10))
        if (!onboarding) page.addView(notificationsCard(), ui.matchWrap(10))
        if (!onboarding && draft.mode == ConnectionMode.SERVER && draft.serverUrl != null) {
            val web = ui.card()
            web.addView(ui.title("Веб-дашборд сервера"))
            web.addView(ui.hint("Те саме, що на головному екрані, у браузерному вигляді сервера (журнал з CSV, сирі показники)."))
            web.addView(ui.button("Відкрити веб-дашборд сервера") { startActivity(Intent(this, DashboardWebActivity::class.java)) }, ui.matchWrap(6))
            page.addView(web, ui.matchWrap(10))
        }

        errorText = ui.label("", 12f, Ui.CORAL, true).apply { visibility = View.GONE }
        page.addView(errorText, ui.matchWrap(10))
        page.addView(ui.button(if (onboarding) "Готово" else "Зберегти", filled = true) { save() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)).apply { topMargin = ui.dp(12) })
        page.addView(ui.button("Про застосунок") { startActivity(Intent(this, AboutActivity::class.java)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(44)).apply { topMargin = ui.dp(8) })
        page.addView(ui.label("Неофіційний застосунок, не пов'язаний з Marstek.", 9f, Ui.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(10), 0, 0)
        })
    }

    /** «Сповіщення»: collapsed by default (like diagnostics); every change is saved immediately. */
    private fun notificationsCard(): LinearLayout {
        val card = ui.card()
        val preferences = WidgetPreferences(this)
        val alertSettings = preferences.alertSettings()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val enabledCount = AlertGroup.entries.count { it in alertSettings.enabledGroups }
        val header = ui.label("Сповіщення  ▸", 14f, Ui.TEXT, true)
        val summary = ui.hint("Увімкнено груп: $enabledCount з ${AlertGroup.entries.size}" +
            if (preferences.anomalyNotificationsEnabled()) " · аномалії" else "")
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            header.text = if (open) "Сповіщення  ▾" else "Сповіщення  ▸"
        }
        card.addView(header)
        card.addView(summary)
        listOf(
            AlertGroup.LIGHT_SCHEDULE to (R.string.main_notify_group_light to R.string.main_notify_group_light_hint),
            AlertGroup.RESERVE to (R.string.main_notify_group_reserve to R.string.main_notify_group_reserve_hint),
            AlertGroup.CHARGING to (R.string.main_notify_group_charging to R.string.main_notify_group_charging_hint),
            AlertGroup.CONSUMPTION to (R.string.main_notify_group_consumption to R.string.main_notify_group_consumption_hint),
            AlertGroup.BATTERY_HEALTH to (R.string.main_notify_group_health to R.string.main_notify_group_health_hint),
        ).forEach { (group, labels) ->
            body.addView(switch(getString(labels.first), group in alertSettings.enabledGroups) { checked ->
                preferences.setAlertGroupEnabled(group, checked)
                if (preferences.anyAlertsEnabled()) WidgetRefreshScheduler.ensurePeriodic(this)
                if (checked) requestNotificationPermission()
                updatePermissionHint()
            }, ui.matchWrap(4))
            body.addView(ui.hint(getString(labels.second)))
        }
        body.addView(ui.label(getString(R.string.main_notify_high_load_label), 11f, Ui.MUTED, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        body.addView(numberField(alertSettings.highLoadThresholdWatts.toString()) { v ->
            v.takeIf { it in 1..10_000 }?.let(preferences::setHighLoadThreshold)
        }, LinearLayout.LayoutParams(ui.dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(ui.hint(getString(R.string.main_notify_high_load_note)))

        body.addView(ui.label("Аномалії", 12f, Ui.TEXT, true).apply { setPadding(0, ui.dp(10), 0, 0) })
        body.addView(switch("Сповіщати про аномалії", preferences.anomalyNotificationsEnabled()) { checked ->
            preferences.setAnomalyNotificationsEnabled(checked)
            if (checked) requestNotificationPermission()
        }, ui.matchWrap(4))
        body.addView(ui.label("Поріг розрядки, Вт (${JournalDetector.MIN_ANOMALY_DISCHARGE_W}–${JournalDetector.MAX_ANOMALY_DISCHARGE_W})", 11f, Ui.MUTED, true).apply { setPadding(0, ui.dp(6), 0, 0) })
        body.addView(numberField(preferences.anomalyThresholdWatts().toString(), enabled = draft.mode == ConnectionMode.DIRECT) { v ->
            v.takeIf { it in JournalDetector.MIN_ANOMALY_DISCHARGE_W..JournalDetector.MAX_ANOMALY_DISCHARGE_W }?.let(preferences::setAnomalyThresholdWatts)
        }, LinearLayout.LayoutParams(ui.dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(ui.hint(
            if (draft.mode == ConnectionMode.SERVER) "Режим сервера: поріг аномалій задає сервер (MARSTEK_ANOMALY_DISCHARGE_W)."
            else "Розрядка вище порогу, розряд при наявності мережі (можливий конфлікт з ДБЖ/інвертором), перегрів."
        ))
        permissionHint = ui.label(getString(R.string.main_notify_permission), 11f, Ui.AMBER, true).apply {
            setPadding(0, ui.dp(6), 0, 0)
            setOnClickListener { requestNotificationPermission() }
        }
        body.addView(permissionHint)
        body.addView(ui.hint(getString(R.string.main_notify_note)))
        card.addView(body)
        updatePermissionHint()
        if (intent.getBooleanExtra(EXTRA_OPEN_NOTIFICATIONS, false)) {
            header.performClick()
            card.post { (card.parent?.parent?.parent as? ScrollView ?: findScroll(card))?.smoothScrollTo(0, card.top) }
        }
        return card
    }

    private fun findScroll(view: View): ScrollView? {
        var p = view.parent
        while (p != null) { if (p is ScrollView) return p; p = p.parent }
        return null
    }

    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        isChecked = checked
        textSize = 14f
        setTextColor(Ui.TEXT)
        thumbTintList = ColorStateList.valueOf(Ui.MINT)
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun numberField(value: String, enabled: Boolean = true, onValue: (Int) -> Unit) = ui.field(value, numeric = true).apply {
        setText(value)
        isEnabled = enabled
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { s?.toString()?.trim()?.toIntOrNull()?.let(onValue) }
        })
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun updatePermissionHint() {
        if (!::permissionHint.isInitialized) return
        val wanted = WidgetPreferences(this).anyAlertsEnabled()
        permissionHint.visibility = if (wanted && !AlertNotifier.canPost(this)) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionHint()
    }

    private fun pickerRow(caption: String, value: TextView, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
        background = ui.rounded(Ui.SURFACE_RAISED, ui.dp(14), Ui.STROKE)
        addView(ui.label(caption, 9f, Ui.MUTED))
        addView(value)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = ui.matchWrap(8)
    }

    private fun bindDraft() {
        hostEdit.setText(draft.deviceHost.orEmpty())
        portEdit.setText(draft.devicePort.toString())
        urlEdit.setText(draft.serverUrl.orEmpty())
        tokenEdit.setText(if (store.token() != null) TOKEN_PLACEHOLDER else "")
        thresholdEdit.setText(trim(draft.backupThresholdW))
        loadEdit.setText(draft.averageLoadW?.let(::trim).orEmpty())
        ratedEdit.setText(trim(draft.ratedCapacityWh))
        floorEdit.setText(trim(draft.dischargeFloorPct))
        ratedNote.text = draft.detectedRatedWh?.let { "Батарея повідомляє ${trim(it)} Wh — використовується це значення; поле вище — запасне." }
            ?: "Визначається автоматично з батареї (rated_capacity); поле — запасне значення."
        updatePanels()
        updateOutageLabels()
    }

    private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun updatePanels() {
        directPanel.visibility = if (draft.mode == ConnectionMode.DIRECT) View.VISIBLE else View.GONE
        serverPanel.visibility = if (draft.mode == ConnectionMode.SERVER) View.VISIBLE else View.GONE
        tokenBox.visibility = if (draft.serverAuth == ServerAuth.BEARER) View.VISIBLE else View.GONE
        cfLogin.visibility = if (draft.serverAuth == ServerAuth.CF_ACCESS) View.VISIBLE else View.GONE
    }

    private fun updateOutageLabels() {
        val region = OutageRegions.byId(draft.outageRegionId)
        regionValue.text = region?.name ?: "Вимкнено"
        queueValue.text = if (region == null) "—" else draft.outageQueue ?: "Оберіть чергу"
        queueValue.setTextColor(if (region != null && draft.outageQueue == null) Ui.AMBER else Ui.TEXT)
        sourceText.text = if (region == null) {
            "Графік вимкнено: застосунок покаже лише, чи є світло (за резервним виходом)."
        } else {
            val chain = buildList {
                OfficialSources.forRegion(region.id)?.let { add("${it.kind.displayName} (напряму)") }
                if (region.inUaOutages) add("ua-outages (офіційні дані обленерго)")
            }
            if (chain.isEmpty()) "Графік недоступний — світло визначається за резервним виходом."
            else "Джерела по черзі: ${chain.joinToString(" → ")}. Оновлення не частіше ніж раз на 10 хв."
        }
    }

    private fun pickRegion() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        val search = ui.field("Пошук області…")
        content.addView(search)
        val list = ListView(this)
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(380)))
        var items = listOf<Pair<String?, String>>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        fun refill(q: String) {
            items = (if (q.isBlank()) listOf<Pair<String?, String>>(null to "Вимкнено") else emptyList()) +
                OutageRegions.search(q).map { it.id to it.name }
            adapter.clear(); adapter.addAll(items.map { it.second }); adapter.notifyDataSetChanged()
        }
        refill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refill(s?.toString().orEmpty())
        })
        val dialog = AlertDialog.Builder(this).setTitle("Область").setView(content).setNegativeButton("Скасувати", null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val id = items[position].first
            val changed = id != draft.outageRegionId
            draft = draft.copy(outageRegionId = id, outageQueue = if (changed) null else draft.outageQueue)
            updateOutageLabels()
            dialog.dismiss()
            if (id != null && changed) pickQueue()
        }
        dialog.show()
    }

    private fun pickQueue() {
        val regionId = draft.outageRegionId ?: return
        queueValue.text = "Завантажую черги…"
        scope.launch {
            val queues = withContext(Dispatchers.IO) { runCatching { OutageRepository(this@SettingsActivity).queuesFor(regionId) }.getOrDefault(OutageRepository.DEFAULT_QUEUES) }
            updateOutageLabels()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Черга (група) відключень")
                .setItems(queues.toTypedArray()) { _, which ->
                    draft = draft.copy(outageQueue = queues[which]); updateOutageLabels()
                }
                .setNeutralButton("Ввести вручну") { _, _ -> manualQueue() }
                .setNegativeButton("Скасувати", null)
                .show()
        }
    }

    private fun manualQueue() {
        val input = ui.field("напр. 3.2")
        AlertDialog.Builder(this).setTitle("Черга").setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { draft = draft.copy(outageQueue = it); updateOutageLabels() }
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun port(): Int? = portEdit.text.toString().trim().ifEmpty { MarstekRpc.DEFAULT_PORT.toString() }.toIntOrNull()
        ?.takeIf(SubnetScanPlanner::isValidPort)

    private fun scan() {
        if (scanning) return
        val port = port() ?: run { scanStatus.text = "Невірний порт."; return }
        val plan = BatteryScanner.planFor(this, cidrEdit.text.toString())
        if (plan == null) {
            scanStatus.text = if (cidrEdit.text.isNotBlank()) "Невірна підмережа. Формат: 192.168.0.0/24 (лише приватні адреси)."
            else "Телефон не підключений до Wi‑Fi з приватною адресою. Підключіться до домашньої мережі або введіть IP вручну."
            return
        }
        scanning = true
        scanResults.removeAllViews()
        scanStatus.text = "Шукаю батарею серед ${plan.hosts.size} адрес…"
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { BatteryScanner.scan(plan, port) { device -> runOnUiThread { addDevice(device) } } }.getOrDefault(emptyList())
            }
            scanning = false
            scanStatus.text = if (found.isEmpty()) {
                "Батарею не знайдено. Перевірте, що телефон у тій самій Wi‑Fi мережі, а в застосунку Marstek увімкнено Open API (порт $port). Можна ввести IP вручну."
            } else "Знайдено: ${found.size}. Торкніться, щоб вибрати."
        }
    }

    private fun addDevice(device: DeviceInfo) {
        val label = buildString {
            append(device.model); append(" · "); append(device.ip)
            device.firmware?.let { append(" · прошивка $it") }
        }
        scanResults.addView(ui.button(label) {
            hostEdit.setText(device.ip)
            portEdit.setText(device.port.toString())
            draft = draft.copy(deviceModel = device.model)
            directResult.text = "Вибрано ${device.model}. Натисніть «Перевірити з'єднання»."
        }, ui.matchWrap(6))
    }

    private fun testDirect() {
        val host = hostEdit.text.toString().trim()
        val port = port()
        if (!SubnetScanPlanner.isValidIpv4(host) || port == null) {
            directResult.setTextColor(Ui.CORAL); directResult.text = "Вкажіть IPv4-адресу батареї й порт (1–65535)."; return
        }
        val offWifi = !NetworkInfo.onWifi(this)
        directResult.setTextColor(Ui.MUTED)
        directResult.text = "Перевіряю $host:$port…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { DirectEngine.probe(host, port) } }
            val (device, battery) = outcome.getOrElse {
                directResult.setTextColor(Ui.CORAL); directResult.text = "Мережна помилка: ${it.javaClass.simpleName}"; return@launch
            }
            when (battery) {
                is RpcResult.Ok -> {
                    val d = battery.data
                    val rated = d.optDouble("rated_capacity", Double.NaN).takeIf(Double::isFinite)
                    rated?.let(store::rememberDetectedRated)
                    draft = draft.copy(deviceModel = device?.model ?: draft.deviceModel, detectedRatedWh = rated ?: draft.detectedRatedWh)
                    directResult.setTextColor(Ui.GOOD)
                    directResult.text = "✓ ${device?.model ?: "Батарея"} відповідає: заряд ${d.optInt("soc")}% · " +
                        "${BatteryDisplayFormatter.formatKwh(d.optDouble("bat_capacity", Double.NaN))} / ${BatteryDisplayFormatter.formatKwh(rated)}"
                    ratedNote.text = rated?.let { "Батарея повідомляє ${trim(it)} Wh — використовується це значення; поле вище — запасне." } ?: ratedNote.text
                }
                is RpcResult.Error -> {
                    directResult.setTextColor(Ui.CORAL)
                    directResult.text = "Батарея відповіла помилкою: ${battery.message}. Увімкніть Open API у застосунку Marstek."
                }
                RpcResult.NoResponse -> {
                    directResult.setTextColor(Ui.CORAL)
                    directResult.text = (if (offWifi) "Телефон не в Wi‑Fi мережі. " else "") +
                        "Немає відповіді від $host:$port. Перевірте IP і що телефон у тій самій мережі. Увімкніть Open API у застосунку Marstek."
                }
            }
        }
    }

    private fun testServer() {
        val url = ServerUrlValidator.normalize(urlEdit.text.toString())
        if (url == null) {
            serverResult.setTextColor(Ui.CORAL); serverResult.text = "Невірна адреса. HTTPS, або HTTP лише для домашньої мережі."; return
        }
        persistToken()
        val candidate = draft.copy(configured = true, mode = ConnectionMode.SERVER, serverUrl = url)
        serverResult.setTextColor(Ui.MUTED)
        serverResult.text = "Перевіряю $url…"
        scope.launch {
            when (val r = TelemetryRepository.fetch(this@SettingsActivity, candidate)) {
                is TelemetryFetchResult.Success -> {
                    serverResult.setTextColor(Ui.GOOD)
                    serverResult.text = "✓ Сервер відповів: заряд ${r.snapshot.socPercent ?: "—"}% · ${BatteryDisplayFormatter.formatKwh(r.snapshot.batteryCapacityWh)}"
                }
                TelemetryFetchResult.NeedsSignIn -> { serverResult.setTextColor(Ui.AMBER); serverResult.text = "Потрібен вхід через Cloudflare Access." }
                is TelemetryFetchResult.Failure -> { serverResult.setTextColor(Ui.CORAL); serverResult.text = r.message }
            }
        }
    }

    private fun persistToken() {
        val token = tokenEdit.text.toString()
        if (token != TOKEN_PLACEHOLDER) store.setToken(token.takeIf { draft.serverAuth == ServerAuth.BEARER })
    }

    private fun save() {
        val errors = mutableListOf<String>()
        var next = draft
        if (draft.mode == ConnectionMode.DIRECT) {
            val host = hostEdit.text.toString().trim()
            val port = port()
            if (!SubnetScanPlanner.isValidIpv4(host)) errors += "Вкажіть IP батареї (або знайдіть її пошуком)."
            if (port == null) errors += "Невірний порт."
            next = next.copy(deviceHost = host, devicePort = port ?: MarstekRpc.DEFAULT_PORT)
        } else {
            val url = ServerUrlValidator.normalize(urlEdit.text.toString())
            if (url == null) errors += "Вкажіть адресу сервера (HTTPS, або HTTP у домашній мережі)."
            if (draft.serverAuth == ServerAuth.CF_ACCESS && url?.startsWith("https://") != true) errors += "Cloudflare Access потребує HTTPS."
            next = next.copy(serverUrl = url)
        }
        if (next.outageRegionId != null && next.outageQueue == null) errors += "Оберіть чергу відключень або вимкніть графік."
        val threshold = thresholdEdit.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.5..500.0 }
        if (threshold == null) errors += "Поріг резервного виходу: 0,5–500 Вт."
        val load = loadEdit.text.toString().replace(',', '.').trim().let { if (it.isEmpty()) null else it.toDoubleOrNull() }
        if (loadEdit.text.isNotBlank() && (load == null || load !in 1.0..20_000.0)) errors += "Середнє споживання: 1–20000 Вт або порожньо."
        val rated = ratedEdit.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it in 100.0..100_000.0 }
        if (rated == null) errors += "Ємність батареї: 100–100000 Wh."
        val floor = floorEdit.text.toString().replace(',', '.').trim().ifEmpty { "11" }.toDoubleOrNull()?.takeIf { it in 0.0..50.0 }
        if (floor == null) errors += "Резерв розряду: 0–50 %."
        if (errors.isNotEmpty()) {
            errorText.visibility = View.VISIBLE
            errorText.text = errors.joinToString("\n")
            return
        }
        persistToken()
        next = next.copy(configured = true, backupThresholdW = threshold!!, averageLoadW = load, ratedCapacityWh = rated!!, dischargeFloorPct = floor!!)
        store.save(next)
        WidgetRefreshScheduler.ensurePeriodic(this)
        WidgetRefreshScheduler.refreshNow(this)
        if (onboarding) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    companion object {
        const val EXTRA_ONBOARDING = "onboarding"
        const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
        private const val TOKEN_PLACEHOLDER = "••••••••"
        private const val REQUEST_NOTIFICATIONS = 42
    }
}
