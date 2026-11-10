package com.doloh.marstekwidget.debug

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.doloh.marstekwidget.domain.AlertEvaluator
import com.doloh.marstekwidget.domain.AlertMemory
import com.doloh.marstekwidget.domain.AlertSettings
import com.doloh.marstekwidget.domain.BatteryDirection
import com.doloh.marstekwidget.domain.BatterySnapshotParser
import com.doloh.marstekwidget.domain.GridState
import com.doloh.marstekwidget.notify.AlertNotifier
import com.doloh.marstekwidget.ui.MainActivity
import com.doloh.marstekwidget.widget.WidgetDisplayOptions
import com.doloh.marstekwidget.widget.WidgetFetchState
import com.doloh.marstekwidget.widget.WidgetPreferences
import com.doloh.marstekwidget.widget.WidgetRenderInput
import com.doloh.marstekwidget.widget.WidgetRenderer
import com.doloh.marstekwidget.widget.WidgetShapeSelector
import com.doloh.marstekwidget.widget.WidgetVariant
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * adb shell am start -n com.doloh.marstekwidget/.debug.WidgetPreviewActivity --es state outage|charging|idle|soon [--es page 1|2|tablet] [--ez main true] [--ez notify true]
 * `notify`: saves the fixture and runs the real alert engine + notifier once (for outage it first seeds «grid was on»).
 * `--es showcase outage|charging [--ez main true]`: clean marketing render (no debug labels), fixed clock 18:30 Kyiv.
 * `--es showcase live`: the same clean 4×1 / 2×2 / 4×2 stack fed by the last real stored snapshot.
 * FIXTURE data, except `--es state live` which renders the app's last stored real snapshot.
 */
class WidgetPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("onboarding", false)) {
            // README/manual screenshot of the first-run welcome screen without wiping the saved setup.
            startActivity(Intent(this, com.doloh.marstekwidget.ui.SettingsActivity::class.java)
                .putExtra(com.doloh.marstekwidget.ui.SettingsActivity.EXTRA_ONBOARDING, true))
            finish()
            return
        }
        intent.getStringExtra("showcase")?.let { kind ->
            showcase(kind, intent.getBooleanExtra("main", false))
            return
        }
        val state = intent.getStringExtra("state") ?: "outage"
        // `live`: the last snapshot actually fetched by the app (direct or server mode), not a fixture.
        val snapshot = if (state == "live") WidgetPreferences(this).snapshot() ?: run { finish(); return }
            else BatterySnapshotParser.parse(fixture(state))
        if (state != "live" && intent.getBooleanExtra("notify", false)) {
            val preferences = WidgetPreferences(this)
            if (state == "outage") {
                preferences.saveAlertMemory(AlertMemory(gridState = GridState.ON, direction = BatteryDirection.IDLE, soc = snapshot.socPercent, hadFreshData = true))
            }
            preferences.saveSnapshot(snapshot)
            val result = AlertNotifier.process(this, snapshot)
            android.util.Log.i("MarstekAlerts", "FIXTURE notify: ${result.notifications.map { it.id }} active=${result.active.map { it.id }}")
            WidgetRenderer.updateAll(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (state != "live" && intent.getBooleanExtra("main", false)) {
            WidgetPreferences(this).saveSnapshot(snapshot)
            WidgetRenderer.updateAll(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val alert = AlertEvaluator.top(AlertEvaluator.evaluate(null, snapshot, System.currentTimeMillis(), AlertSettings()).active)
        val input = WidgetRenderInput(snapshot, WidgetFetchState.OK, WidgetDisplayOptions(), System.currentTimeMillis(), alert = alert)
        val page = intent.getStringExtra("page") ?: "1"
        val sizes = if (page == "1") {
            listOf("4x1 min" to (340 to 44), "4x1" to (340 to 72), "2x1" to (160 to 72), "2x2" to (160 to 160), "4x2" to (340 to 160))
        } else if (page == "tablet") {
            // Tablet launcher cells (Pixel Tablet-like): 6×1, 5×2, 3×3, 6×3 and 6×4.
            listOf("6x1" to (640 to 90), "5x2" to (560 to 210), "3x3" to (330 to 330), "6x3" to (680 to 330), "6x4" to (700 to 470))
        } else {
            listOf("4x3" to (340 to 250), "4x4" to (340 to 340))
        }
        val density = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(40, 56, 70))
            setPadding((12 * density).toInt(), (34 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }
        column.addView(TextView(this).apply {
            text = if (state == "live") "LIVE snapshot · page $page" else "FIXTURE · $state · page $page"
            setTextColor(Color.WHITE)
            textSize = 11f
        })
        sizes.forEach { (name, size) ->
            val (w, h) = size
            val shape = WidgetShapeSelector.select(w.toFloat(), h.toFloat())
            column.addView(TextView(this).apply {
                text = "$name · ${w}x${h}dp → $shape"
                setTextColor(Color.rgb(200, 210, 220))
                textSize = 10f
                setPadding(0, (8 * density).toInt(), 0, (3 * density).toInt())
            })
            val frame = FrameLayout(this)
            val views = WidgetRenderer.build(this, shape, WidgetVariant.MEDIUM, input, 0)
            frame.addView(views.apply(this, frame), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            column.addView(frame, LinearLayout.LayoutParams((w * density).toInt(), (h * density).toInt()))
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun fixture(state: String): JSONObject {
        val kyiv = ZoneId.of("Europe/Kyiv")
        val now = ZonedDateTime.now(kyiv)
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun at(hours: Long) = now.plusHours(hours).withMinute(0).withSecond(0).withNano(0).format(iso)
        val soc = when (state) { "charging" -> 46; "idle" -> 100; "soon" -> 18; else -> 64 }
        val temp = if (state == "soon") 46.5 else 23.5
        val history = JSONArray().apply {
            for (i in 0..95) {
                val t = now.minusMinutes(15L * (95 - i))
                val v = when (state) {
                    "charging" -> (100 - i).coerceAtLeast(46).let { if (i > 54) 46 + (i - 54) / 8 else it }
                    "idle" -> if (i < 40) 60 + i else 100
                    else -> if (i < 80) 95 else 95 - (i - 80) * 2
                }
                put(JSONObject().put("t", t.format(iso)).put("soc", v.coerceIn(0, 100)))
            }
        }
        val outage = when (state) {
            "outage" -> JSONObject("""{"available":true,"group":"1.1","grid_state":"off",
                "current_outage":{"kind":"schedule","start":"${at(-1)}","end":"${at(3)}","duration_seconds":14400,"remaining_seconds":10800},
                "next_outage":null,"schedule_published":true,
                "autonomy":{"load_w":430.0,"load_basis":"measured_discharge","stored_wh":3277,"runtime_seconds":27400,"outage_seconds":10800,"covers_next_outage":true}}""")
            "soon" -> JSONObject("""{"available":true,"group":"1.1","grid_state":"on","current_outage":null,
                "next_outage":{"kind":"schedule","start":"${now.plusMinutes(45).withSecond(0).withNano(0).format(iso)}","end":"${now.plusMinutes(285).withSecond(0).withNano(0).format(iso)}","duration_seconds":14400},
                "schedule_published":true,
                "autonomy":{"load_w":310.0,"load_basis":"measured_average_24h","stored_wh":${soc * 51.2},"runtime_seconds":${(soc * 51.2 / 310.0 * 3600).toLong()},"outage_seconds":14400,"covers_next_outage":false}}""")
            else -> JSONObject("""{"available":true,"group":"1.1","grid_state":"on","current_outage":null,
                "next_outage":{"kind":"schedule","start":"${at(2)}","end":"${at(6)}","duration_seconds":14400},
                "schedule_published":true,
                "autonomy":{"load_w":310.0,"load_basis":"measured_average_24h","stored_wh":${soc * 51.2},"runtime_seconds":${(soc * 51.2 / 310.0 * 3600).toLong()},"outage_seconds":14400,"covers_next_outage":true}}""")
        }
        val flow = when (state) {
            "charging" -> """{"available":true,"power_w":820.0,"direction":"charging","charge_eta_seconds":11700,"discharge_eta_seconds":null}"""
            "idle" -> """{"available":true,"power_w":0.0,"direction":"idle","charge_eta_seconds":null,"discharge_eta_seconds":null}"""
            else -> """{"available":true,"power_w":-445.0,"direction":"discharging","charge_eta_seconds":null,"discharge_eta_seconds":26500}"""
        }
        val offgrid = if (state == "outage") 438.0 else 0.0
        return JSONObject("""{
            "collected_at":"${now.toInstant()}",
            "methods":{
              "Bat.GetStatus":{"data":{"soc":$soc,"bat_capacity":${soc * 51.2},"rated_capacity":5120,"bat_temp":$temp}},
              "ES.GetStatus":{"data":{"ongrid_power":${if (state == "charging") -840 else 0},"offgrid_power":$offgrid,"total_grid_input_energy":412340,"total_grid_output_energy":380120,"total_load_energy":96210}},
              "ES.GetMode":{"data":{"mode":"Manual","ct_state":0}}
            },
            "battery_flow":$flow
        }""").put("power_outage", outage).put("insights", JSONObject()
            .put("backup_output_w", offgrid)
            .put("backup_avg_w_24h", 312.4)
            .put("backup_avg_coverage_hours", 23.6)
            .put("backup_energy_today_wh", 2140.5)
            .put("temperature_c", temp)
            .put("soc_history", history))
    }

    private fun showcase(kind: String, main: Boolean) {
        // Render every clock (incl. "оновлено HH:mm") in Kyiv time inside this debug process only.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Kyiv"))
        val kyiv = ZoneId.of("Europe/Kyiv")
        val realNow = ZonedDateTime.now(kyiv)
        val live = kind == "live"
        val now = if (main || live) realNow else realNow.withHour(18).withMinute(30).withSecond(0).withNano(0)
        // `live`: clean render (no labels) of the last snapshot the app actually fetched — README screenshots.
        val snapshot = if (live) WidgetPreferences(this).snapshot() ?: run { finish(); return }
            else BatterySnapshotParser.parse(showcaseFixture(kind, now))
        if (live && main) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (main) {
            WidgetPreferences(this).saveSnapshot(snapshot)
            startActivity(Intent(this, MainActivity::class.java).putExtra("showcase", true))
            finish()
            return
        }
        val nowMillis = now.toInstant().toEpochMilli()
        val alert = AlertEvaluator.top(AlertEvaluator.evaluate(null, snapshot, nowMillis, AlertSettings()).active)
        val input = WidgetRenderInput(snapshot, WidgetFetchState.OK, WidgetDisplayOptions(), nowMillis, now = now, alert = alert)
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(24, 44, 62), Color.rgb(12, 24, 36)),
            )
            setPadding(px(24), px(72), px(24), px(40))
        }
        listOf(340 to 72, 160 to 160, 340 to 160).forEachIndexed { index, (w, h) ->
            val shape = WidgetShapeSelector.select(w.toFloat(), h.toFloat())
            val frame = FrameLayout(this)
            val views = WidgetRenderer.build(this, shape, WidgetVariant.MEDIUM, input, 0)
            frame.addView(views.apply(this, frame), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            column.addView(frame, LinearLayout.LayoutParams(px(w), px(h)).apply { if (index > 0) topMargin = px(36) })
        }
        setContentView(column)
    }

    /** Realistic showcase numbers (requested for a public post); still a fixture, not live telemetry. */
    private fun showcaseFixture(kind: String, now: ZonedDateTime): JSONObject {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        fun today(hour: Int) = now.withHour(hour).withMinute(0).withSecond(0).withNano(0).format(iso)
        val outage = kind == "outage"
        val soc = if (outage) 64 else 72
        val stored = if (outage) 3280.0 else 72 * 51.2
        val temp = if (outage) 24.0 else 27.0
        val history = JSONArray().apply {
            for (i in 0..95) {
                val v = if (outage) (if (i < 70) 92 else 92 - (i - 70)) else (if (i < 60) 40 else 40 + (i - 60))
                put(JSONObject().put("t", now.minusMinutes(15L * (95 - i)).format(iso)).put("soc", v.coerceIn(0, 100)))
            }
        }
        val runtime = (stored / 48.0 * 3600).toLong()
        val powerOutage = if (outage) JSONObject("""{"available":true,"group":"1.1","grid_state":"off",
            "current_outage":{"kind":"schedule","start":"${today(17)}","end":"${today(21)}","duration_seconds":14400},
            "next_outage":null,"schedule_published":true,
            "autonomy":{"load_w":48.0,"load_basis":"measured_discharge","stored_wh":$stored,"runtime_seconds":$runtime,"outage_seconds":9000,"covers_next_outage":true}}""")
        else JSONObject("""{"available":true,"group":"1.1","grid_state":"on","current_outage":null,
            "next_outage":{"kind":"schedule","start":"${today(20)}","end":"${now.withHour(23).withMinute(59).withSecond(0).withNano(0).plusMinutes(1).format(iso)}","duration_seconds":14400},
            "schedule_published":true,
            "autonomy":{"load_w":52.0,"load_basis":"measured_average_24h","stored_wh":$stored,"runtime_seconds":${(stored / 52.0 * 3600).toLong()},"outage_seconds":14400,"covers_next_outage":true}}""")
        val chargeEta = ((5120.0 - stored) / 820.0 * 3600).toLong()
        val flow = if (outage) """{"available":true,"power_w":-48.0,"direction":"discharging","charge_eta_seconds":null,"discharge_eta_seconds":$runtime}"""
        else """{"available":true,"power_w":820.0,"direction":"charging","charge_eta_seconds":$chargeEta,"discharge_eta_seconds":null}"""
        val offgrid = if (outage) 48.0 else 0.0
        return JSONObject("""{
            "collected_at":"${now.toInstant()}",
            "methods":{
              "Bat.GetStatus":{"data":{"soc":$soc,"bat_capacity":$stored,"rated_capacity":5120,"bat_temp":$temp}},
              "ES.GetStatus":{"data":{"ongrid_power":${if (outage) 0 else -840},"offgrid_power":$offgrid,"total_grid_input_energy":412340,"total_grid_output_energy":380120,"total_load_energy":96210}},
              "ES.GetMode":{"data":{"mode":"Manual","ct_state":0}}
            },
            "battery_flow":$flow
        }""").put("power_outage", powerOutage).put("insights", JSONObject()
            .put("backup_output_w", offgrid)
            .put("backup_avg_w_24h", 52.0)
            .put("backup_energy_today_wh", 410.0)
            .put("temperature_c", temp)
            .put("soc_history", history))
    }
}
