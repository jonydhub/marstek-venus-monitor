package com.doloh.marstekwidget.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.doloh.marstekwidget.data.JournalStore
import com.doloh.marstekwidget.data.MarstekApiClient
import com.doloh.marstekwidget.domain.JournalCategory
import com.doloh.marstekwidget.domain.JournalDetector
import com.doloh.marstekwidget.domain.JournalEvent
import com.doloh.marstekwidget.domain.JournalExport
import com.doloh.marstekwidget.settings.ConnectionMode
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.widget.WidgetPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Журнал подій»: light lost/returned, charging, connection lost/restored, anomalies. Direct mode reads the phone's
 * own journal, server mode the server's GET /api/events. Export = timestamps, categories, values and texts only.
 */
class JournalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ui: Ui
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val chips = mutableMapOf<JournalCategory?, TextView>()
    private var events: List<JournalEvent> = emptyList()
    private var filter: JournalCategory? = null

    private val showcase: Boolean
        get() = intent.getBooleanExtra("showcase", false) &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val page = ui.page()
        val scroll = ui.scrollCentered(page, fillViewport = true)
        build(page)
        SystemBarsInsetHelper.apply(scroll, 0, 0, 0, 0)
        setContentView(scroll)
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(page: LinearLayout) {
        page.addView(ui.label("Журнал подій", 20f, Ui.TEXT, true))
        page.addView(ui.hint("Світло (за резервним виходом), заряджання, зв'язок з батареєю та аномалії — з точним часом, 30 днів."))

        val settings = SettingsStore(this).load()
        val preferences = WidgetPreferences(this)
        val anomalies = ui.card()
        anomalies.addView(ui.title("Аномалії"))
        anomalies.addView(Switch(this).apply {
            text = "Сповіщати про аномалії"
            textSize = 14f
            setTextColor(Ui.TEXT)
            thumbTintList = ColorStateList.valueOf(Ui.MINT)
            isChecked = preferences.anomalyNotificationsEnabled()
            setOnCheckedChangeListener { _, checked -> preferences.setAnomalyNotificationsEnabled(checked) }
        }, ui.matchWrap())
        anomalies.addView(ui.label("Поріг розрядки, Вт (${JournalDetector.MIN_ANOMALY_DISCHARGE_W}–${JournalDetector.MAX_ANOMALY_DISCHARGE_W})", 10f, Ui.MUTED, true), ui.matchWrap(8))
        anomalies.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setTextColor(Ui.TEXT)
            setText(preferences.anomalyThresholdWatts().toString())
            backgroundTintList = ColorStateList.valueOf(Ui.STROKE)
            isEnabled = settings.mode == ConnectionMode.DIRECT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.takeIf { it in JournalDetector.MIN_ANOMALY_DISCHARGE_W..JournalDetector.MAX_ANOMALY_DISCHARGE_W }
                        ?.let(preferences::setAnomalyThresholdWatts)
                }
            })
        }, LinearLayout.LayoutParams(ui.dp(140), LinearLayout.LayoutParams.WRAP_CONTENT))
        anomalies.addView(ui.hint(
            if (settings.mode == ConnectionMode.SERVER) "Режим сервера: поріг задає сервер (MARSTEK_ANOMALY_DISCHARGE_W), застосунок показує його журнал."
            else "Сповіщення, якщо розрядка перевищує поріг, батарея розряджається при наявності мережі (можливий конфлікт з ДБЖ/інвертором) або перегрівається."
        ))
        page.addView(anomalies, ui.matchWrap(12))

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(null to "Все", JournalCategory.ANOMALY to "Аномалії", JournalCategory.CONNECTION to "Зв'язок",
            JournalCategory.LIGHT to "Світло", JournalCategory.BATTERY to "Батарея").forEach { (category, title) ->
            val chip = ui.label(title, 12f, Ui.MUTED, true).apply {
                setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
                setOnClickListener { filter = category; render() }
            }
            chips[category] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(6) })
        }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }, ui.matchWrap(12))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ui.button("Поділитись текстом") { share("text/plain", JournalExport.text(visible())) },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { marginEnd = ui.dp(6) })
        actions.addView(ui.button("Експорт CSV") { share("text/csv", JournalExport.csv(visible())) },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { marginStart = ui.dp(6) })
        page.addView(actions, ui.matchWrap(10))

        status = ui.hint(if (settings.mode == ConnectionMode.SERVER) "Завантажую журнал сервера…" else "Журнал цього телефона (режим «Напряму»).")
        page.addView(status, ui.matchWrap(8))
        list = ui.card()
        page.addView(list, ui.matchWrap(6))
    }

    private fun visible() = events.filter { filter == null || it.category == filter }

    private fun load() {
        if (showcase) {
            events = demoEvents()
            status.text = "Демонстраційні дані"
            render()
            return
        }
        val settings = SettingsStore(this).load()
        scope.launch {
            events = if (settings.mode == ConnectionMode.SERVER) {
                val remote = MarstekApiClient(this@JournalActivity, settings, SettingsStore(this@JournalActivity).token()).fetchEvents(200)
                status.text = if (remote == null) "Сервер не віддав журнал (потрібен сервер 1.2+)." else "Журнал сервера · ${remote.size} подій"
                remote.orEmpty()
            } else withContext(Dispatchers.IO) { JournalStore(this@JournalActivity).events(200) }.also {
                status.text = "Журнал цього телефона · ${it.size} подій (оновлюється, коли застосунок опитує батарею)"
            }
            render()
        }
    }

    private fun render() {
        chips.forEach { (category, chip) ->
            val active = category == filter
            chip.setTextColor(if (active) Ui.MINT else Ui.MUTED)
            chip.background = ui.rounded(if (active) Ui.SURFACE_RAISED else Ui.SURFACE, ui.dp(16), if (active) Ui.MINT else Ui.STROKE)
        }
        list.removeAllViews()
        val rows = visible()
        if (rows.isEmpty()) {
            list.addView(ui.hint("Подій ще немає"))
            return
        }
        rows.forEachIndexed { index, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, if (index == 0) 0 else ui.dp(8), 0, ui.dp(8))
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(ui.label(JournalExport.time(e.tsMillis), 10f, Ui.DIM))
            head.addView(ui.label("  ${e.category.title}", 10f, colorFor(e.category), true))
            row.addView(head)
            row.addView(ui.label(e.text, 13f, Ui.TEXT))
            list.addView(row)
            if (index < rows.size - 1) list.addView(View(this).apply { setBackgroundColor(Ui.STROKE) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun colorFor(category: JournalCategory) = when (category) {
        JournalCategory.ANOMALY -> Ui.CORAL
        JournalCategory.LIGHT -> Ui.AMBER
        JournalCategory.CONNECTION -> Ui.MINT
        JournalCategory.BATTERY -> Ui.GOOD
    }

    private fun share(type: String, body: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_SUBJECT, "Marstek Venus — журнал подій")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, "Поділитись журналом"))
    }

    companion object {
    /** Demo journal for debug showcase screenshots only; realistic numbers, no personal data. */
    fun demoEvents(): List<JournalEvent> {
        val now = System.currentTimeMillis()
        fun ago(minutes: Long) = now - minutes * 60_000L
        return listOf(
            JournalEvent(ago(35), "light_lost", JournalCategory.LIGHT, "Світло зникло: резервний вихід 121 Вт, заряд 82%", 121.0),
            JournalEvent(ago(190), "anomaly_discharge_on_grid", JournalCategory.ANOMALY,
                "Розряд ≈ 177 Вт при наявності мережі, резервний вихід не живить навантаження — можливий конфлікт з ДБЖ/інвертором", 177.0),
            JournalEvent(ago(260), "connection_restored", JournalCategory.CONNECTION, "Зв'язок з батареєю відновлено після 6 хв"),
            JournalEvent(ago(266), "connection_lost", JournalCategory.CONNECTION,
                "Немає зв'язку з батареєю: 3 опитування поспіль без відповіді (стан світла невідомий)"),
            JournalEvent(ago(420), "charged_full", JournalCategory.BATTERY, "Батарею заряджено: 100%", 100.0),
            JournalEvent(ago(530), "charging_started", JournalCategory.BATTERY, "Почалось заряджання ≈ 820 Вт (оцінка), заряд 64%", 820.0),
            JournalEvent(ago(531), "light_returned", JournalCategory.LIGHT, "Світло повернулось після 4 год 0 хв, заряд 64%"),
            JournalEvent(ago(700), "anomaly_high_discharge", JournalCategory.ANOMALY, "Розрядка 2128 Вт перевищує поріг 2000 Вт, заряд 78%", 2128.0),
            JournalEvent(ago(771), "light_lost", JournalCategory.LIGHT, "Світло зникло: резервний вихід 96 Вт, заряд 100%", 96.0),
        )
    }
    }
}
