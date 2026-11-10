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

class AboutActivity : Activity() {
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
