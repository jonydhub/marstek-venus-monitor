package com.doloh.marstekwidget.widget

/** Size buckets of the «Світло» widget; picked by real width AND height (see [LightWidgetShapeSelector]). */
enum class LightWidgetShape(val minWidthDp: Float, val minHeightDp: Float) {
    /** 2×1: headline (+ duration) on one line, a second line when the cell is tall enough. */
    STRIP_NARROW(100f, 40f),

    /** 4×1: «Світла нема · вже 2 год 15 хв» + the schedule line, refresh button. */
    STRIP(220f, 40f),

    /** 2×2: header, big duration, schedule line (+ queue when there is room). */
    SQUARE(100f, 100f),

    /** 4×2 and up: everything + reserve verdict + freshness. */
    WIDE(250f, 100f);

    val isStrip: Boolean get() = this == STRIP || this == STRIP_NARROW
}

object LightWidgetShapeSelector {
    fun select(widthDp: Float, heightDp: Float): LightWidgetShape = when {
        heightDp < 100f -> if (widthDp >= LightWidgetShape.STRIP.minWidthDp) LightWidgetShape.STRIP else LightWidgetShape.STRIP_NARROW
        widthDp >= LightWidgetShape.WIDE.minWidthDp -> LightWidgetShape.WIDE
        else -> LightWidgetShape.SQUARE
    }

    /** A strip shows the second line only when one fits under the first (≈ 56 dp and up). */
    fun stripHasTwoLines(heightDp: Float): Boolean = heightDp >= 52f
}

/** Type sizes (sp) and optional rows of the 2×2 / 4×2 card, sized from the real cell. */
data class LightBlockPlan(
    val headerSp: Float,
    val durationSp: Float,
    val bodySp: Float,
    val smallSp: Float,
    val iconDp: Float,
    val showNext: Boolean,
    val showGroup: Boolean,
    val showReserve: Boolean,
    /** Height of the rows without the flexible spacers, dp. */
    val contentDp: Float,
)

object LightBlockPlanner {
    /** Card padding (layout: 12 dp top/bottom, 14 dp start/end). */
    const val PAD_V = 24f
    const val PAD_H = 28f
    const val GAP = 4f

    /** One text line in dp with includeFontPadding=false (≈ 1.18 × the type size). */
    fun lineDp(sp: Float, fontScale: Float = 1f): Float = sp * fontScale * 1.18f

    fun plan(widthDp: Float, heightDp: Float, shape: LightWidgetShape, hasGroup: Boolean, hasReserve: Boolean, fontScale: Float = 1f): LightBlockPlan {
        val wide = shape == LightWidgetShape.WIDE
        val inner = heightDp - PAD_V
        val base = if (wide) floatArrayOf(17f, 28f, 13.5f, 12f) else floatArrayOf(15f, 24f, 12.5f, 11f)
        // Taller cells get bigger type (Samsung 2×2 ≈ 170×200 dp); narrow ones are capped by width.
        val byHeight = if (wide) heightDp / 160f else minOf(widthDp, heightDp) / 150f
        var k = byHeight.coerceIn(0.85f, 1.5f)
        var group = hasGroup
        var reserve = wide && hasReserve
        var next = true
        fun total(k: Float): Float {
            val header = maxOf(base[0] * k * 1.35f, lineDp(base[0] * k, fontScale))
            var sum = header + GAP + lineDp(base[1] * k, fontScale)
            if (next) sum += GAP + lineDp(base[2] * k, fontScale)
            if (reserve) sum += GAP + lineDp(base[2] * k, fontScale)
            if (group) sum += GAP + lineDp(base[3] * k, fontScale)
            return sum
        }
        while (total(k) > inner) {
            when {
                k > 0.75f -> k -= 0.05f
                group -> { group = false; k = byHeight.coerceIn(0.85f, 1.5f) }
                reserve -> { reserve = false; k = byHeight.coerceIn(0.85f, 1.5f) }
                next -> { next = false; k = byHeight.coerceIn(0.85f, 1.5f) }
                else -> break
            }
        }
        return LightBlockPlan(
            headerSp = base[0] * k, durationSp = base[1] * k, bodySp = base[2] * k, smallSp = base[3] * k,
            iconDp = base[0] * k * 1.35f, showNext = next, showGroup = group, showReserve = reserve, contentDp = total(k),
        )
    }
}
