package com.doloh.marstekwidget.domain

/** Official schedule pages of the sources the app reads (for «Відкрити сайт джерела»); null when not an official site. */
object OutageSourceLinks {
    fun url(sourceId: String?, sourceName: String? = null): String? {
        val key = (sourceId ?: sourceName)?.lowercase() ?: return null
        return when {
            key == "loe" || key.contains("львів") -> "https://poweron.loe.lviv.ua/"
            key == "yasno" -> "https://yasno.ua/"
            key == "kharkiv" || key.contains("харків") -> "https://oblenergo.kharkiv.ua/uk"
            key == "poltava" || key.contains("полтав") -> "https://www.poe.pl.ua/disconnection/power-outages/"
            key == "zaporizhzhia" || key.contains("запоріж") -> "https://www.zoe.com.ua/outage/"
            key == "chernivtsi" || key.contains("чернів") -> "https://oblenergo.cv.ua/shutdowns/"
            else -> null
        }
    }
}
