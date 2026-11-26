package com.doloh.marstekwidget.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView

/** LinearLayout that never measures wider than [maxWidthPx] (0 = unbounded); centre it with a parent gravity. */
class MaxWidthLinearLayout(context: Context, var maxWidthPx: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val clamped = if (maxWidthPx in 1 until size) MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(clamped, heightMeasureSpec)
    }
}

/** Small programmatic UI kit shared by settings/about screens (same palette as the dashboard). */
class Ui(private val activity: Activity) {
    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    /** Form page: a single column clamped to [ScreenLayoutSelector.FORM_MAX_WIDTH_DP] so lines stay readable on tablets. */
    fun page() = MaxWidthLinearLayout(activity, dp(ScreenLayoutSelector.FORM_MAX_WIDTH_DP)).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(18))
        setBackgroundColor(BG)
    }

    fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(SURFACE, dp(18), STROKE)
    }

    fun title(value: String) = label(value, 12f, TEXT, true).apply { setPadding(0, 0, 0, dp(8)) }

    fun label(value: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    fun hint(value: String) = label(value, 10f, DIM).apply { setPadding(0, dp(2), 0, dp(4)) }

    fun field(hintText: String, numeric: Boolean = false, password: Boolean = false) = EditText(activity).apply {
        hint = hintText
        textSize = 14f
        setTextColor(TEXT)
        setHintTextColor(DIM)
        maxLines = 1
        isSingleLine = true
        backgroundTintList = ColorStateList.valueOf(STROKE)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
    }

    fun radio(text: String) = RadioButton(activity).apply {
        this.text = text
        textSize = 14f
        setTextColor(TEXT)
        buttonTintList = ColorStateList.valueOf(MINT)
        id = android.view.View.generateViewId()
    }

    fun button(value: String, filled: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        setTextColor(if (filled) BG else TEXT)
        background = rounded(if (filled) MINT else SURFACE_RAISED, dp(13), if (filled) MINT else STROKE)
        minHeight = dp(42)
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { action() }
    }

    fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    /** Scrollable screen with [page] horizontally centered (fills the width on phones, a centered column on tablets). */
    fun scrollCentered(page: View, fillViewport: Boolean = false) = ScrollView(activity).apply {
        isFillViewport = fillViewport
        setBackgroundColor(BG)
        addView(centered(page), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun centered(page: View) = FrameLayout(activity).apply {
        setBackgroundColor(BG)
        addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }

    fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    /** Screen header: a 48 dp «‹» back target (finishes the activity) + title (+ optional muted subtitle). */
    fun topBar(title: String, subtitle: String? = null) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(activity).apply {
            text = "‹"
            textSize = 30f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "Назад"
            background = rounded(SURFACE_RAISED, dp(14), STROKE)
            isClickable = true
            isFocusable = true
            setOnClickListener { activity.finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 20f, TEXT, true).apply { includeFontPadding = false })
        subtitle?.let { texts.addView(label(it, 11f, MUTED).apply { includeFontPadding = false; setPadding(0, dp(3), 0, 0) }) }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    /**
     * One table row: the first cell takes the slack (left-aligned), the rest are fixed-share, right-aligned, tabular
     * figures. [header] rows are small and dim; [highlight] colours the row coral (e.g. a running outage).
     */
    fun tableRow(cells: List<String>, weights: List<Float>, header: Boolean = false, highlight: Boolean = false, firstBold: Boolean = false) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(if (header) 0 else 5), 0, dp(if (header) 4 else 5))
            cells.forEachIndexed { i, value ->
                val color = when {
                    header -> DIM
                    highlight -> CORAL
                    i == 0 -> TEXT
                    value == "—" -> DIM
                    else -> MUTED
                }
                addView(label(value, if (header) 10f else 12.5f, color, !header && (highlight || (i == 0 && firstBold))).apply {
                    maxLines = 1
                    fontFeatureSettings = "tnum"
                    gravity = if (i == 0) Gravity.START else Gravity.END
                    setLineSpacing(0f, 1f)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights.getOrElse(i) { 1f }))
            }
        }

    /** Hairline divider for tables. */
    fun divider() = View(activity).apply { setBackgroundColor(STROKE) }

    companion object {
        val BG = Color.rgb(7, 21, 34)
        val SURFACE = Color.rgb(21, 31, 42)
        val SURFACE_RAISED = Color.rgb(28, 41, 53)
        val STROKE = Color.rgb(42, 61, 75)
        val TEXT = Color.rgb(243, 247, 250)
        val MUTED = Color.rgb(160, 173, 184)
        val DIM = Color.rgb(113, 128, 141)
        val MINT = Color.rgb(54, 198, 213)
        val AMBER = Color.rgb(245, 183, 46)
        val GOOD = Color.rgb(127, 227, 178)
        val CORAL = Color.rgb(255, 128, 111)
    }
}
