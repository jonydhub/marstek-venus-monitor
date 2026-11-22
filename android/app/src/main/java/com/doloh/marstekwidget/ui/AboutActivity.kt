package com.doloh.marstekwidget.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.doloh.marstekwidget.BuildConfig
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.update.AppUpdate
import com.doloh.marstekwidget.update.UpdateCheckOutcome
import com.doloh.marstekwidget.update.UpdateChecker
import com.doloh.marstekwidget.update.UpdatePreferences
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var updateLine: TextView
    private lateinit var updateButtons: LinearLayout
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val page = ui.page()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(10) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(ui.label(getString(R.string.app_name), 20f, Ui.TEXT, true))
        names.addView(ui.label("Версія ${BuildConfig.VERSION_NAME}", 11f, Ui.MUTED))
        head.addView(names)
        page.addView(head, ui.matchWrap(12))

        // «Остання версія» from the last GitHub check + «Перевірити зараз» (ignores the 12 h limit).
        val updates = ui.card()
        updateLine = ui.label("", 12f, Ui.TEXT, true)
        updates.addView(updateLine)
        updateButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updates.addView(updateButtons)
        page.addView(updates, ui.matchWrap(10))
        renderUpdate(null)

        val disclaimer = ui.card()
        disclaimer.addView(ui.label("Неофіційний застосунок, не пов'язаний з Marstek.", 13f, Ui.AMBER, true))
        disclaimer.addView(ui.hint("Лише читання: застосунок надсилає батареї тільки запити Get (Marstek Open API) і ніколи не змінює її налаштувань. Дані про відключення беруться з публічних джерел і можуть відрізнятися від фактичних — орієнтуйтеся на офіційні повідомлення обленерго."))
        page.addView(disclaimer, ui.matchWrap(10))

        val links = ui.card()
        links.addView(ui.title("Посилання"))
        links.addView(ui.button("Сирцевий код на GitHub") { open(REPO_URL) }, ui.matchWrap(4))
        links.addView(ui.hint(REPO_URL))
        page.addView(links, ui.matchWrap(10))

        val credits = ui.card()
        credits.addView(ui.title("Джерела даних і ліцензії"))
        CREDITS.forEach { (title, text, url) ->
            credits.addView(ui.label(title, 12f, Ui.TEXT, true).apply { setPadding(0, ui.dp(6), 0, 0) })
            credits.addView(ui.hint(text))
            if (url != null) credits.addView(ui.label(url, 10f, Ui.MINT).apply { setOnClickListener { open(url) } })
        }
        page.addView(credits, ui.matchWrap(10))

        val scroll = ui.scrollCentered(page)
        SystemBarsInsetHelper.apply(scroll, 0, 0, 0, 0)
        setContentView(scroll)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun renderUpdate(outcome: UpdateCheckOutcome?) {
        val ui = Ui(this)
        val prefs = UpdatePreferences(this)
        val latest = prefs.latest()
        val checkedAt = prefs.lastSuccessMs().takeIf { it > 0 }?.let { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(it)) }
        val newer = latest != null && AppUpdate.isNewer(latest.version, UpdateChecker.installedVersion)
        updateLine.text = when {
            checking -> "Перевіряю…"
            latest == null || checkedAt == null -> if (outcome == UpdateCheckOutcome.FAILED) "Не вдалося перевірити — немає зв'язку з GitHub" else "Нові версії ще не перевірялись"
            newer -> "Остання версія: ${latest?.version} (перевірено $checkedAt)"
            else -> "У вас остання версія (перевірено $checkedAt)"
        }
        updateLine.setTextColor(if (newer && !checking) Ui.MINT else Ui.TEXT)
        updateButtons.removeAllViews()
        if (outcome == UpdateCheckOutcome.FAILED && latest != null) updateButtons.addView(ui.hint("Не вдалося перевірити зараз — показано останній відомий результат"))
        if (!prefs.enabled()) updateButtons.addView(ui.hint("Автоматична перевірка вимкнена в налаштуваннях"))
        if (newer && !checking && latest != null) updateButtons.addView(ui.button("Оновити до ${latest.version}", filled = true) { open(latest.downloadUrl) }, ui.matchWrap(6))
        updateButtons.addView(ui.button(if (checking) "Перевіряю…" else "Перевірити зараз") {
            if (checking) return@button
            checking = true
            renderUpdate(null)
            scope.launch {
                val result = UpdateChecker.check(this@AboutActivity, force = true, foreground = true)
                checking = false
                renderUpdate(result)
            }
        }, ui.matchWrap(6))
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        const val REPO_URL = "https://github.com/jonydhub/marstek-venus-monitor"
        val CREDITS = listOf(
            Triple("Marstek Open API", "Локальний UDP JSON-RPC інтерфейс батареї (лише методи Get). Marstek — торгова марка її власника.", null),
            Triple("Львівобленерго", "Відкритий API графіків погодинних відключень (api.loe.lviv.ua).", "https://poweron.loe.lviv.ua/"),
            Triple("YASNO", "Публічний API планових відключень для Києва та Дніпра.", "https://yasno.ua/"),
            Triple("Харківобленерго, Полтаваобленерго, Запоріжжяобленерго, Чернівціобленерго", "Графіки читаються напряму з офіційних сайтів обленерго (не частіше ніж раз на 10 хв).", null),
            Triple("ua-outages — Pavlo Huk (MIT)", "Агрегат офіційних даних обленерго (hukpo/ua-outages), оновлюється кожні ~15 хв; з нього ж портовано розбір сайтів Харків-, Полтава-, Запоріжжя- та Чернівціобленерго.", "https://github.com/hukpo/ua-outages"),
            Triple("AndroidX WorkManager (Apache-2.0)", "Фонові оновлення віджетів.", null),
        )
    }
}
