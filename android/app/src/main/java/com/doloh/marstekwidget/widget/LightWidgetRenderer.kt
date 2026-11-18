package com.doloh.marstekwidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.domain.AlertEvaluator
import com.doloh.marstekwidget.domain.LightKind
import com.doloh.marstekwidget.notify.AlertNotifier

/**
 * Standalone «Світло» widget: «Світла нема» / «Світло є» / «Немає зв'язку», «вже 2 год 15 хв», the scheduled return or
 * next outage, and on 4×2 the queue and reserve verdict. Renders only the cached snapshot (server or direct mode);
 * the shared [MarstekRefreshWorker] redraws it through [WidgetRenderer.updateAll]. Texts come from [LightWidgetTexts].
 */
object LightWidgetRenderer {
    private const val SP = TypedValue.COMPLEX_UNIT_SP
    private val NEUTRAL = Color.rgb(201, 213, 220)
    private val MUTED = Color.rgb(139, 155, 167)

    fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MarstekLightWidgetProvider::class.java))

    fun updateAll(context: Context) = widgetIds(context).forEach { update(context, it) }

    fun update(context: Context, widgetId: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val preferences = WidgetPreferences(context)
        val input = WidgetRenderInput(
            snapshot = preferences.snapshot(),
            state = preferences.state(),
            options = preferences.options(widgetId),
            lastFetchedAtMillis = preferences.lastFetchedAtMillis(),
            alert = AlertEvaluator.top(AlertNotifier.activeAlerts(context)),
        )
        val sizes = WidgetRenderer.reportedSizes(manager, widgetId)
        val views = when {
            sizes.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                RemoteViews(sizes.associate { (w, h) -> SizeF(w, h) to build(context, input, widgetId, w to h) })
            sizes.isNotEmpty() -> {
                val portrait = context.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                build(context, input, widgetId, if (portrait) sizes.first() else sizes.last())
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> RemoteViews(LightWidgetShape.entries.associate { shape ->
                SizeF(shape.minWidthDp, shape.minHeightDp) to build(context, input, widgetId, shape.minWidthDp to shape.minHeightDp)
            })
            else -> build(context, input, widgetId, LightWidgetShape.SQUARE.minWidthDp to LightWidgetShape.SQUARE.minHeightDp)
        }
        manager.updateAppWidget(widgetId, views)
    }

    /** One size-specific RemoteViews for a real cell of [sizeDp] (dp). */
    fun build(context: Context, input: WidgetRenderInput, widgetId: Int, sizeDp: Pair<Float, Float>): RemoteViews {
        val (w, h) = sizeDp
        val shape = LightWidgetShapeSelector.select(w, h)
        val linkLost = input.state == WidgetFetchState.ERROR
        val content = input.snapshot?.let { LightWidgetTexts.content(it, linkLost, input.now) }
            ?: LightWidgetContent(
                kind = LightKind.UNKNOWN,
                headlines = listOfNotNull(WidgetRenderer.noDataText(context, input.state),
                    context.getString(R.string.widget_light_waiting_short).takeIf { input.state == WidgetFetchState.NEVER }),
                durations = emptyList(),
                next = listOfNotNull(WidgetRenderer.noDataHint(context, input.state)
                    ?: context.getString(R.string.widget_light_first_update).takeIf { input.state == WidgetFetchState.NEVER }),
                group = emptyList(), reserve = null, reserveCovers = null,
            )
        val views = RemoteViews(context.packageName, if (shape.isStrip) R.layout.widget_light_strip else R.layout.widget_light_block)
        views.setImageViewResource(R.id.light_icon, icon(content.kind))
        views.setTextColor(R.id.light_primary, headlineColor(content.kind))
        val updated = WidgetRenderer.compactUpdatedText(context, input.snapshot, input)
        if (shape.isStrip) renderStrip(views, context, shape, content, updated, input, w, h) else renderBlock(views, context, shape, content, updated, input, w, h)

        views.setContentDescription(R.id.light_root, listOfNotNull(
            content.headlines.first(), content.durations.firstOrNull(), content.next.firstOrNull(), content.reserve,
            WidgetRenderer.fullUpdatedText(context, input.snapshot, input, WidgetVariant.MEDIUM),
        ).joinToString(". "))
        // Same routes as the battery widgets: card → app, schedule line → «Графік відключень», ↻ → shared worker.
        views.setOnClickPendingIntent(R.id.light_root, WidgetRenderer.openAppPendingIntent(context, widgetId))
        val refresh = WidgetRenderer.refreshPendingIntent(context, widgetId)
        views.setOnClickPendingIntent(R.id.light_refresh, refresh)
        views.setOnClickPendingIntent(R.id.light_updated, refresh)
        if (!shape.isStrip && input.snapshot?.powerOutage != null) {
            views.setOnClickPendingIntent(R.id.light_next, WidgetRenderer.schedulePendingIntent(context, widgetId))
        }
        return views
    }

    private fun fontScale(context: Context) = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f

    private fun renderStrip(views: RemoteViews, context: Context, shape: LightWidgetShape, content: LightWidgetContent, updated: String,
                            input: WidgetRenderInput, w: Float, h: Float) {
        val measure = WidgetRenderer.measurer(context)
        val twoLines = LightWidgetShapeSelector.stripHasTwoLines(h)
        val narrow = shape == LightWidgetShape.STRIP_NARROW
        val primarySp = if (h >= 64f) 15f else 14f
        val secondarySp = if (h >= 64f) 12f else 11f
        // padding 12 + 4, icon 22 + 8, refresh 30 (4×1 only)
        val fitPrimary = { text: String -> measure(text, primarySp, true) }
        val fitSecondary = { text: String -> measure(text, secondarySp, false) }
        val withIcon = w - 16f - 30f - (if (narrow) 0f else 30f)
        // Tiny 2×1: the headline beats the icon (the headline colour still carries the state).
        val showIcon = content.headlines.any { fitPrimary(it) <= withIcon }
        val room = if (showIcon) withIcon else withIcon + 30f
        views.setViewVisibility(R.id.light_icon, visibility(showIcon))
        val plain = LightWidgetTexts.strip(content, room, twoLines, fitPrimary, fitSecondary)
        // The freshness time joins a 4×1 only while it costs no text.
        val timeDp = measure(updated, 10f, false) + 6f
        val withTime = LightWidgetTexts.strip(content, room - timeDp, twoLines, fitPrimary, fitSecondary)
        val showTime = !narrow && wantsTime(input) && withTime == plain
        val strip = plain
        // Still too wide (very narrow cell, large font): shrink the type rather than cut the word.
        val primaryFit = LightWidgetFit.fit(listOf(strip.primary), room, primarySp, 9f) { t, sp -> measure(t, sp, true) }!!
        views.setTextViewTextSize(R.id.light_primary, SP, primaryFit.second)
        views.setTextViewText(R.id.light_primary, strip.primary)
        views.setTextViewTextSize(R.id.light_secondary, SP, secondarySp)
        views.setTextViewText(R.id.light_secondary, strip.secondary.orEmpty())
        views.setViewVisibility(R.id.light_secondary, visibility(!strip.secondary.isNullOrBlank()))
        views.setTextColor(R.id.light_secondary, NEUTRAL)
        views.setViewVisibility(R.id.light_updated, visibility(showTime))
        views.setTextViewText(R.id.light_updated, updated)
        views.setTextColor(R.id.light_updated, if (input.state == WidgetFetchState.OK) MUTED else Color.rgb(255, 196, 107))
        views.setViewVisibility(R.id.light_refresh, visibility(!narrow))
    }

    private fun renderBlock(views: RemoteViews, context: Context, shape: LightWidgetShape, content: LightWidgetContent, updated: String,
                            input: WidgetRenderInput, w: Float, h: Float) {
        val measure = WidgetRenderer.measurer(context)
        val plan = LightBlockPlanner.plan(w, h, shape, hasGroup = content.group.isNotEmpty(), hasReserve = content.reserve != null, fontScale = fontScale(context))
        val textW = w - LightBlockPlanner.PAD_H
        val iconDp = plan.iconDp
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutWidth(R.id.light_icon, iconDp, TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutHeight(R.id.light_icon, iconDp, TypedValue.COMPLEX_UNIT_DIP)
        }

        // Header: icon, headline, then the freshness time and ↻ while they fit beside the headline.
        val updatedSp = (plan.smallSp * 0.92f).coerceIn(9.5f, 13f)
        val headRoom = textW - iconDp - 6f
        val wantTime = wantsTime(input)
        val timeDp = measure(updated, updatedSp, false) + 6f
        val buttonDp = 24f
        val headline = { extra: Float -> content.headlines.firstOrNull { measure(it, plan.headerSp, true) <= headRoom - extra } }
        val wide = shape == LightWidgetShape.WIDE
        val (title, showTime, showButton) = when {
            wide && wantTime && headline(timeDp + buttonDp) != null -> Triple(headline(timeDp + buttonDp)!!, true, true)
            wantTime && headline(timeDp + measure(" ↻", updatedSp, false)) != null -> Triple(headline(timeDp + measure(" ↻", updatedSp, false))!!, true, false)
            else -> Triple(headline(0f) ?: content.headlines.last(), false, false)
        }
        // A headline that fits nowhere at full size shrinks instead of wrapping («Немає» / «зв'язку»).
        val titleSp = LightWidgetFit.fit(listOf(title), headRoom, plan.headerSp, plan.headerSp * 0.7f) { t, sp -> measure(t, sp, true) }!!.second
        views.setTextViewTextSize(R.id.light_primary, SP, titleSp)
        views.setTextViewText(R.id.light_primary, title)
        views.setTextViewTextSize(R.id.light_updated, SP, updatedSp)
        views.setTextViewTextSize(R.id.light_refresh, SP, updatedSp + 4f)
        views.setViewVisibility(R.id.light_refresh, visibility(showButton))
        // Without the button the time itself carries a cyan «↻» and refreshes on tap.
        val timeText: CharSequence = if (showButton) updated else android.text.SpannableString("$updated ↻").apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.rgb(54, 198, 213)), length - 1, length, 0)
        }
        views.setTextViewText(R.id.light_updated, timeText)
        views.setViewVisibility(R.id.light_updated, visibility(showTime))
        views.setTextColor(R.id.light_updated, if (input.state == WidgetFetchState.OK) MUTED else Color.rgb(255, 196, 107))
        if (!showButton) views.setContentDescription(R.id.light_updated, context.getString(R.string.widget_refresh_description))

        // The key number: «вже 2 год 15 хв», shrunk before «вже» is dropped.
        val duration = LightWidgetFit.fit(content.durations, textW, plan.durationSp, plan.durationSp * 0.75f) { t, sp -> measure(t, sp, true) }
        // No link: that slot says how old the shown data is instead («Останні дані о 20:01»), never a light duration.
        val lastData = if (duration == null && content.kind == LightKind.NO_LINK) WidgetRenderer.timestampText(input.snapshot, input)
            ?.let { context.getString(R.string.widget_light_last_data, it) } else null
        val lastDataFit = lastData?.let { LightWidgetFit.fit(listOf(it), textW, plan.bodySp * 1.25f, plan.bodySp * 0.85f) { t, sp -> measure(t, sp, true) } }
        val durationRow = duration ?: lastDataFit
        views.setViewVisibility(R.id.light_duration, visibility(durationRow != null))
        durationRow?.let { (text, sp) ->
            views.setTextViewText(R.id.light_duration, text)
            views.setTextViewTextSize(R.id.light_duration, SP, sp)
            views.setTextColor(R.id.light_duration, if (duration != null) Color.rgb(243, 247, 250) else MUTED)
        }

        // Schedule line; with no duration known it becomes the second most prominent row.
        val nextSp = if (duration == null) plan.bodySp * 1.15f else plan.bodySp
        val oneLine = if (plan.showNext) LightWidgetFit.fit(content.next, textW, nextSp, plan.bodySp * 0.8f) { t, sp -> measure(t, sp, false) } else null
        // Nothing fits one line (narrow 2×2): the full sentence on two lines; the queue row gives way if height is short.
        val wraps = oneLine != null && measure(oneLine.first, oneLine.second, false) > textW
        val next = if (wraps) content.next.first() to plan.bodySp * 0.9f else oneLine
        val twoLineExtra = if (wraps) LightBlockPlanner.lineDp(plan.bodySp * 0.9f, fontScale(context)) else 0f
        val groupFits = plan.contentDp + twoLineExtra <= h - LightBlockPlanner.PAD_V
        views.setInt(R.id.light_next, "setMaxLines", if (wraps) 2 else 1)
        views.setViewVisibility(R.id.light_next, visibility(next != null))
        next?.let { (text, sp) ->
            views.setTextViewText(R.id.light_next, text)
            views.setTextViewTextSize(R.id.light_next, SP, sp)
            views.setTextColor(R.id.light_next, NEUTRAL)
        }
        if (input.snapshot?.powerOutage != null) views.setContentDescription(R.id.light_next, (next?.first?.let { "$it. " } ?: "") + context.getString(R.string.widget_light_open_schedule))

        val reserve = content.reserve?.takeIf { plan.showReserve }?.let(WidgetRenderer::keepUnits)
            ?.let { r -> LightWidgetFit.fit(listOf(r), textW, plan.bodySp, plan.bodySp * 0.8f) { t, sp -> measure(t, sp, false) } }
        views.setViewVisibility(R.id.light_reserve, visibility(reserve != null))
        reserve?.let { (text, sp) ->
            views.setTextViewText(R.id.light_reserve, text)
            views.setTextViewTextSize(R.id.light_reserve, SP, sp)
            views.setTextColor(R.id.light_reserve, when (content.reserveCovers) {
                true -> Color.rgb(127, 227, 178)
                false -> Color.rgb(255, 140, 128)
                null -> NEUTRAL
            })
        }

        val group = if (plan.showGroup && groupFits) content.group.firstOrNull { measure(it, plan.smallSp, false) <= textW } else null
        views.setViewVisibility(R.id.light_group, visibility(group != null))
        group?.let {
            views.setTextViewText(R.id.light_group, it)
            views.setTextViewTextSize(R.id.light_group, SP, plan.smallSp)
        }
    }

    /** The freshness time is hidden while nothing was ever fetched («Дані…» says nothing new). */
    private fun wantsTime(input: WidgetRenderInput) =
        input.state != WidgetFetchState.NEVER && (input.options.showLastUpdated || input.state != WidgetFetchState.OK)

    private fun icon(kind: LightKind): Int = when (kind) {
        LightKind.ON -> R.drawable.ic_widget_light_on
        LightKind.OFF -> R.drawable.ic_widget_light_off
        else -> R.drawable.ic_widget_light_unknown
    }

    /** Amber for no light, mint for light, grey when unknown or the battery cannot be read. */
    fun headlineColor(kind: LightKind): Int = when (kind) {
        LightKind.ON -> Color.rgb(127, 227, 178)
        LightKind.OFF -> Color.rgb(255, 184, 107)
        LightKind.NO_LINK, LightKind.UNKNOWN -> Color.rgb(160, 174, 184)
    }

    private fun visibility(show: Boolean) = if (show) View.VISIBLE else View.GONE
}
