package com.doloh.marstekwidget.data

import android.content.Context
import com.doloh.marstekwidget.domain.DetectorMemory
import com.doloh.marstekwidget.domain.JournalEvent
import org.json.JSONObject
import java.io.File

/** Direct-mode event journal: one JSON line per event in app-private storage, kept 30 days. */
class JournalStore(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "journal.jsonl")
    private val prefs = app.getSharedPreferences("marstek_journal", Context.MODE_PRIVATE)

    @Synchronized
    fun append(events: List<JournalEvent>, nowMillis: Long = System.currentTimeMillis()) {
        if (events.isEmpty()) return
        file.appendText(events.joinToString("") { it.toJson().toString() + "\n" })
        val all = read()
        val kept = all.filter { it.tsMillis >= nowMillis - RETENTION_MILLIS }
        if (kept.size < all.size) file.writeText(kept.sortedBy { it.tsMillis }.joinToString("") { it.toJson().toString() + "\n" })
    }

    /** Newest first. */
    @Synchronized
    fun events(limit: Int = 200): List<JournalEvent> = read().sortedByDescending { it.tsMillis }.take(limit)

    @Synchronized
    fun memory(): DetectorMemory? = DetectorMemory.fromJson(prefs.getString(KEY_MEMORY, null))

    @Synchronized
    fun saveMemory(memory: DetectorMemory) { prefs.edit().putString(KEY_MEMORY, memory.toJson().toString()).apply() }

    private fun read(): List<JournalEvent> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
        runCatching { JournalEvent.fromJson(JSONObject(line)) }.getOrNull()
    }

    companion object {
        const val RETENTION_MILLIS = 30L * 24 * 3600_000L
        private const val KEY_MEMORY = "detector.memory"
    }
}
