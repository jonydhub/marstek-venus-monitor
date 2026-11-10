package com.doloh.marstekwidget.ui

/** Dashboard arrangement chosen from the window's available width (not the device type), so split-screen and foldables behave. */
enum class ScreenLayoutMode { SINGLE, TWO_COLUMN, THREE_COLUMN }

data class ScreenLayoutSpec(
    val mode: ScreenLayoutMode,
    /** Upper bound of the centered content column, in dp. */
    val maxContentWidthDp: Int,
    /** Multiplier applied to every sp text size. */
    val textScale: Float,
    val sparklineHeightDp: Int,
) {
    val columns: Int get() = when (mode) {
        ScreenLayoutMode.SINGLE -> 1
        ScreenLayoutMode.TWO_COLUMN -> 2
        ScreenLayoutMode.THREE_COLUMN -> 3
    }
}

object ScreenLayoutSelector {
    /** Material window-size-class breakpoints: compact < 600 dp ≤ medium < 840 dp ≤ expanded. */
    const val MEDIUM_MIN_WIDTH_DP = 600
    const val EXPANDED_MIN_WIDTH_DP = 840

    /** Three columns need ≈ 350 dp each so the hero (SOC + light state) never wraps; below that, two wide columns. */
    const val THREE_COLUMN_MIN_WIDTH_DP = 1100

    /** Settings, onboarding and About stay a single centered column of this width. */
    const val FORM_MAX_WIDTH_DP = 640

    fun modeFor(widthDp: Int): ScreenLayoutMode = when {
        widthDp >= THREE_COLUMN_MIN_WIDTH_DP -> ScreenLayoutMode.THREE_COLUMN
        widthDp >= MEDIUM_MIN_WIDTH_DP -> ScreenLayoutMode.TWO_COLUMN
        else -> ScreenLayoutMode.SINGLE
    }

    fun specFor(widthDp: Int): ScreenLayoutSpec = when (modeFor(widthDp)) {
        ScreenLayoutMode.SINGLE -> ScreenLayoutSpec(ScreenLayoutMode.SINGLE, FORM_MAX_WIDTH_DP, 1f, 72)
        ScreenLayoutMode.TWO_COLUMN ->
            if (widthDp >= EXPANDED_MIN_WIDTH_DP) ScreenLayoutSpec(ScreenLayoutMode.TWO_COLUMN, 1100, 1.12f, 150)
            else ScreenLayoutSpec(ScreenLayoutMode.TWO_COLUMN, 1000, 1.12f, 120)
        ScreenLayoutMode.THREE_COLUMN -> ScreenLayoutSpec(ScreenLayoutMode.THREE_COLUMN, 1360, 1.15f, 160)
    }
}
