package com.doloh.marstekwidget.domain

/**
 * Time labels above a 0–24 h schedule bar: «07:00» at the left edge and «13:00» at the right edge of each outage
 * window, and the current time over the now marker. Pure layout (no Android types) so it is unit-tested.
 *
 * Rules: the start label is left-aligned to the block's left edge and the end label right-aligned to its right edge
 * (so neighbouring windows' labels sit inside their own blocks); the now label is centred on the marker; every label
 * is clamped inside [0, totalWidth]. If a window's two labels would touch,
 * they merge into one «07:00–13:00» centred over the block (else flush with its left / right edge); if that still collides with labels already placed, the
 * window gets no labels (the text line under the bar still lists it). Definite windows are placed before «можливе»
 * ones. The now label comes last: hidden when the marker is inside a window whose labels are shown (the window
 * labels win) or when it would collide with any label.
 */
object ScheduleTimeLabels {
    enum class Kind { WINDOW, MAYBE, NOW }

    data class Label(val text: String, val left: Float, val width: Float, val kind: Kind) {
        val right: Float get() = left + width
        val center: Float get() = left + width / 2f
    }

    /**
     * [barLeft]/[barWidth] = the bar's x range inside a canvas [totalWidth] wide; [measure] = text width in the same
     * units; [gap] = minimum free space between two labels.
     */
    fun layout(
        segments: List<ScheduleBarModel.Segment>,
        nowFraction: Float?,
        nowText: String?,
        barLeft: Float,
        barWidth: Float,
        totalWidth: Float,
        gap: Float,
        measure: (String) -> Float,
    ): List<Label> = place(segments, nowFraction, nowText, barLeft, barWidth, totalWidth, gap, measure).first

    /**
     * Windows that get no time label above the bar (port of the web `unlabeled`, keep in sync): the text line under the
     * bar lists only these, so a fully labelled bar has no repeated «Відключення …» line.
     */
    fun unlabeled(
        segments: List<ScheduleBarModel.Segment>,
        nowFraction: Float?,
        nowText: String?,
        barLeft: Float,
        barWidth: Float,
        totalWidth: Float,
        gap: Float,
        measure: (String) -> Float,
    ): List<ScheduleBarModel.Segment> = place(segments, nowFraction, nowText, barLeft, barWidth, totalWidth, gap, measure).second

    private fun place(
        segments: List<ScheduleBarModel.Segment>,
        nowFraction: Float?,
        nowText: String?,
        barLeft: Float,
        barWidth: Float,
        totalWidth: Float,
        gap: Float,
        measure: (String) -> Float,
    ): Pair<List<Label>, List<ScheduleBarModel.Segment>> {
        val placed = mutableListOf<Label>()
        fun x(f: Float) = barLeft + f.coerceIn(0f, 1f) * barWidth
        fun at(text: String, kind: Kind, leftOf: (Float) -> Float): Label {
            val w = measure(text)
            return Label(text, leftOf(w).coerceIn(0f, maxOf(0f, totalWidth - w)), w, kind)
        }
        fun label(text: String, center: Float, kind: Kind) = at(text, kind) { center - it / 2f }
        fun touches(a: Label, b: Label) = a.left < b.right + gap && b.left < a.right + gap
        fun free(l: Label) = placed.none { touches(it, l) }
        val shown = mutableListOf<ScheduleBarModel.Segment>()
        val ordered = segments.filter { it.from.isNotEmpty() && it.to.isNotEmpty() && it.end > it.start }
            .sortedWith(compareBy({ it.maybe }, { it.start }))
        for (s in ordered) {
            val kind = if (s.maybe) Kind.MAYBE else Kind.WINDOW
            val from = at(s.from, kind) { x(s.start) }
            val to = at(s.to, kind) { x(s.end) - it }
            if (!touches(from, to) && free(from) && free(to)) {
                placed += from
                placed += to
                shown += s
                continue
            }
            // one «07:00–13:00» label: centred over the block, else flush with its left or right edge (same as web)
            // a window from midnight continues yesterday's outage: «до 00:30» is shorter and says the same
            val text = if (s.from == "00:00" && s.to != "24:00") "до ${s.to}" else "${s.from}–${s.to}"
            val pick = listOf(label(text, (x(s.start) + x(s.end)) / 2f, kind), at(text, kind) { x(s.start) }, at(text, kind) { x(s.end) - it })
                .firstOrNull { free(it) }
            if (pick != null) {
                placed += pick
                shown += s
            }
        }
        if (nowFraction != null && !nowText.isNullOrEmpty()) {
            val inside = shown.any { nowFraction >= it.start && nowFraction <= it.end }
            val now = label(nowText, x(nowFraction), Kind.NOW)
            if (!inside && free(now)) placed += now
        }
        return placed.sortedBy { it.left } to ordered.filter { it !in shown }
    }
}
