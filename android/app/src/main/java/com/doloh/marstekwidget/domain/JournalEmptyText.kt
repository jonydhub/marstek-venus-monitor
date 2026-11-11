package com.doloh.marstekwidget.domain

/** Informative empty-journal texts (instead of a bare «Немає подій»). */
object JournalEmptyText {
    const val EMPTY = "Подій ще немає. Вони з\u02BCявляться, коли зміниться стан (світло, заряд, зв\u02BCязок)."
    const val OLD_SERVER = "Сервер старої версії — оновіть сервер, щоб бачити журнал."

    fun serverEmpty(startedAtMillis: Long?, nowMillis: Long): String {
        val started = startedAtMillis?.takeIf { it in 1..nowMillis } ?: return EMPTY
        return "$EMPTY Журнал сервера ведеться ${since(nowMillis - started)}."
    }

    /** «5 хв», «3 год 10 хв», «2 дн 4 год». */
    fun since(millis: Long): String {
        val minutes = millis / 60_000
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        return when {
            days > 0 -> "$days дн $hours год"
            hours > 0 -> "$hours год $mins хв"
            else -> "${maxOf(1, mins)} хв"
        }
    }
}
