package de.gamingdoodz.radiofinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Der Hitradar-Server beobachtet rund um die Uhr tausende Sender.
 * Ist er erreichbar, fragt die App nur noch ihn: schneller, spart Akku und Daten.
 * Ist er weg, scannt die App wie bisher selbst.
 */
object Radar {
    private const val BASE = "http://34.170.242.185"

    @Volatile private var appContext: Context? = null
    @Volatile private var lastOk = 0L
    @Volatile private var lastTry = 0L
    @Volatile private var lastFail = 0L
    private val lock = Mutex()

    fun init(context: Context) { appContext = context.applicationContext }

    /** Server hat in den letzten Minuten geantwortet. */
    val ok get() = System.currentTimeMillis() - lastOk < 5 * 60_000L

    private fun interval(): Long {
        val c = appContext ?: return 120_000L
        return when {
            Net.isUnmetered(c) -> 60_000L
            Settings.saver(c) -> 300_000L
            else -> 150_000L
        }
    }

    private fun parse(text: String): List<Pair<Station, Triple<String, Long, Long>>> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val url = o.optString("url").ifBlank { return@mapNotNull null }
            val title = o.optString("title").ifBlank { return@mapNotNull null }
            Station(id, o.optString("name", "Radio"), url, o.optString("favicon"), false) to
                Triple(title, o.optLong("since") * 1000L, o.optLong("seen") * 1000L)
        }
    }

    /** Holt alles, was gerade läuft, in den LiveIndex (höchstens einmal pro Intervall). */
    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis()
        if (!force && t - lastTry < interval()) return@withContext ok
        // Nach einem Fehler 5 Minuten Pause, damit die App nicht dauernd wartet
        if (t - lastFail < 5 * 60_000L) return@withContext false
        if (!lock.tryLock()) return@withContext ok
        try {
            lastTry = t
            val text = Net.get("$BASE/now?limit=6000", 4000, 15_000, 8_000_000)
            val list = text?.let { runCatching { parse(it) }.getOrNull() }
            if (list.isNullOrEmpty()) { lastFail = t; return@withContext false }
            list.forEach { (s, n) -> LiveIndex.putRemote(s, n.first, n.second, n.third) }
            lastOk = System.currentTimeMillis()
            true
        } finally {
            lock.unlock()
        }
    }

    /** Sucht ein Lied auf dem Server. null = Server nicht erreichbar. */
    suspend fun search(query: String): List<Scanner.Hit>? = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastFail < 5 * 60_000L) return@withContext null
        val text = Net.get("$BASE/search?q=" + URLEncoder.encode(query, "UTF-8"), 4000, 8000)
        if (text == null) { lastFail = System.currentTimeMillis(); return@withContext null }
        val list = runCatching { parse(text) }.getOrNull() ?: return@withContext null
        lastOk = System.currentTimeMillis()
        list.map { (s, n) ->
            LiveIndex.putRemote(s, n.first, n.second, n.third)
            Scanner.Hit(s, n.first)
        }
    }
}
