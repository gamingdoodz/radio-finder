package de.gamingdoodz.radiofinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sender, die nicht angezeigt werden sollen (z. B. weil ein Sender das wünscht).
 * Die Liste liegt im Projekt (server/blocklist.txt) und wird einmal am Tag geholt.
 */
object Blocklist {
    private const val URL = "https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/server/blocklist.txt"
    @Volatile private var entries: List<String> = emptyList()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("blocklist", Context.MODE_PRIVATE)

    private fun parse(text: String) = text.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Gespeicherte Liste sofort laden, neue höchstens einmal am Tag holen. */
    suspend fun refresh(c: Context) = withContext(Dispatchers.IO) {
        val p = prefs(c)
        if (entries.isEmpty()) entries = parse(p.getString("text", "").orEmpty())
        if (System.currentTimeMillis() - p.getLong("at", 0L) < 24 * 3600_000L) return@withContext
        val text = Net.get(URL, 5000, 8000, 200_000) ?: return@withContext
        entries = parse(text)
        p.edit().putString("text", text).putLong("at", System.currentTimeMillis()).apply()
    }

    fun blocked(id: String, url: String): Boolean {
        val l = entries
        if (l.isEmpty()) return false
        val u = url.lowercase()
        return l.any { it == id.lowercase() || (it.contains('.') && u.contains(it)) }
    }
}
