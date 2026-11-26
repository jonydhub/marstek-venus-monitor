package com.doloh.marstekwidget.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.domain.EnergyCalc
import com.doloh.marstekwidget.widget.WidgetPreferences

/**
 * «Калькулятор»: power × time → energy, cost, share of the battery, charge left and how long the current charge lasts
 * at that power. Pure logic in [EnergyCalc]; inputs are remembered between visits.
 */
class CalcActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var wattsEdit: EditText
    private lateinit var hoursEdit: EditText
    private lateinit var minutesEdit: EditText
    private lateinit var tariffEdit: EditText
    private lateinit var energyText: TextView
    private lateinit var resultRows: LinearLayout
    private lateinit var warning: TextView
    private val prefs by lazy { getSharedPreferences("marstek_calc", Context.MODE_PRIVATE) }
    private var battery: EnergyCalc.Battery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        battery = WidgetPreferences(this).snapshot()?.let { s ->
            EnergyCalc.Battery(s.ratedCapacityWh, s.batteryCapacityWh, s.powerOutage?.usableWh, s.powerOutage?.floorPct)
        }
        val page = ui.page()
        page.addView(ui.topBar("Калькулятор"), ui.matchWrap())

        // inputs
        val input = ui.card()
        input.addView(ui.title("Що вмикаєте"))
        input.addView(presetGrid())
        wattsEdit = ui.field("1500", numeric = true)
        hoursEdit = ui.field("0", numeric = true)
        minutesEdit = ui.field("30", numeric = true)
        input.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(labeled("Потужність, Вт", wattsEdit), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(labeled("Годин", hoursEdit), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
            addView(labeled("Хвилин", minutesEdit), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
        }, ui.matchWrap(10))
        page.addView(input, ui.matchWrap(8))

        // result
        val result = ui.card()
        energyText = ui.label("—", 30f, Ui.MINT, true).apply { includeFontPadding = false }
        result.addView(energyText)
        resultRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(8), 0, 0) }
        result.addView(resultRows)
        warning = ui.label("", 12f, Ui.AMBER, true).apply { visibility = View.GONE; setPadding(0, ui.dp(8), 0, 0) }
        result.addView(warning)
        page.addView(result, ui.matchWrap(8))

        // tariff
        tariffEdit = ui.field(EnergyCalc.DEFAULT_TARIFF.toString().replace('.', ','), numeric = true)
        page.addView(ui.card().apply {
            addView(labeled("Тариф, грн за кВт·год", tariffEdit))
            addView(ui.hint("4,32 — для населення до 30.04.2027; ніч на двозонному — 2,16"))
        }, ui.matchWrap(8))

        wattsEdit.setText(prefs.getString("watts", "1500"))
        hoursEdit.setText(prefs.getString("hours", "0"))
        minutesEdit.setText(prefs.getString("minutes", "30"))
        tariffEdit.setText(prefs.getString("tariff", "4,32"))
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update()
        }
        listOf(wattsEdit, hoursEdit, minutesEdit, tariffEdit).forEach { it.addTextChangedListener(watcher) }
        update()

        val scroll = ui.scrollCentered(page, fillViewport = true)
        SystemBarsInsetHelper.apply(scroll, 0, 0, 0, 0)
        setContentView(scroll)
    }

    private fun labeled(title: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(ui.label(title, 10f, Ui.DIM))
        addView(field, ui.matchWrap())
    }

    /** Presets three per row; a tap fills power + usual time. */
    private fun presetGrid() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        EnergyCalc.PRESETS.chunked(3).forEachIndexed { i, row ->
            addView(LinearLayout(this@CalcActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { j, p ->
                    addView(TextView(this@CalcActivity).apply {
                        text = "${p.name}\n${p.watts}\u00A0Вт"
                        textSize = 11f
                        setTextColor(Ui.TEXT)
                        gravity = Gravity.CENTER
                        setPadding(ui.dp(4), ui.dp(7), ui.dp(4), ui.dp(7))
                        background = ui.rounded(Ui.SURFACE_RAISED, ui.dp(12), Ui.STROKE)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            wattsEdit.setText(p.watts.toString())
                            hoursEdit.setText((p.minutes / 60).toString())
                            minutesEdit.setText((p.minutes % 60).toString())
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (j > 0) marginStart = ui.dp(6) })
                }
            }, ui.matchWrap(if (i == 0) 0 else 6))
        }
    }

    private fun EditText.number(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun update() {
        prefs.edit()
            .putString("watts", wattsEdit.text.toString()).putString("hours", hoursEdit.text.toString())
            .putString("minutes", minutesEdit.text.toString()).putString("tariff", tariffEdit.text.toString())
            .apply()
        val seconds = ((hoursEdit.number() ?: 0.0) * 3600 + (minutesEdit.number() ?: 0.0) * 60).toLong()
        val r = EnergyCalc.calc(wattsEdit.number(), seconds, battery, tariffEdit.number())
        resultRows.removeAllViews()
        if (r == null) {
            energyText.text = "—"
            warning.visibility = View.GONE
            resultRows.addView(ui.label("Вкажіть потужність і час", 12f, Ui.MUTED))
            return
        }
        energyText.text = r.energy
        r.cost?.let { row("Коштуватиме", it) }
        r.batteryShare?.let { row("Від повної батареї", it) }
        when (r.fits) {
            true -> r.socAfter?.let { row("Заряд після", it) }
            false -> row("Від поточного заряду", "не вистачить", Ui.CORAL)
            null -> Unit
        }
        r.runtime?.let { row("Заряду вистачить на", "$it · ${r.runtimeTo}") }
        warning.visibility = if (r.overLimit) View.VISIBLE else View.GONE
        warning.text = "Понад 2,5 кВт — резервний вихід станції не потягне"
    }

    private fun row(title: String, value: String, color: Int = Ui.TEXT) {
        resultRows.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            addView(ui.label(title, 12.5f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.label(value, 13f, color, true).apply { gravity = Gravity.END; fontFeatureSettings = "tnum" })
        })
    }
}
