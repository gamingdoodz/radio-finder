package de.gamingdoodz.radiofinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Der Hitradar-Server beobachtet rund um die Uhr tausende Sender.
 * Ist er erreichbar, fragt die App nur noch ihn: schneller, spart Akku und Daten.
 * Ist er weg, scannt die App wie bisher selbst.
 */
object Radar {
    /** Hitradar-Server bei Oracle: verschlüsselt zuerst, sonst die direkte Adresse. */
    private val servers = listOf("https://130-61-78-232.sslip.io:8080", "http://130.61.78.232")
    @Volatile private var active = 0

    /** Fragt den aktiven Server; geht er nicht, den nächsten. */
    private fun fetch(path: String, readMs: Int, maxBytes: Int): String? {
        for (n in servers.indices) {
            val i = (active + n) % servers.size
            val text = Net.get(servers[i] + path, 4000, readMs, maxBytes)
            if (text != null) { active = i; return text }
        }
        return null
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var lastOk = 0L
    @Volatile private var lastTry = 0L
    @Volatile private var lastFail = 0L
    private val lock = Mutex()

    fun init(context: Context) { appContext = context.applicationContext }

    /** Hit-Mix läuft: öfter aktualisieren. Sonst seltener, das spart Akku und Daten. */
    @Volatile var fast = false

    /** Server hat in den letzten Minuten geantwortet. */
    val ok get() = System.currentTimeMillis() - lastOk < 5 * 60_000L

    private fun interval(): Long {
        val c = appContext ?: return 120_000L
        return when {
            fast && Net.isUnmetered(c) -> 60_000L
            fast -> if (Settings.saver(c)) 180_000L else 120_000L
            Net.isUnmetered(c) -> 150_000L
            else -> 300_000L
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

    /** Was der Server zuletzt gemeldet hat: Sender-ID -> (Sender, Titel, seit, gesehen). */
    private class Live(val station: Station, var title: String, var since: Long, var seen: Long)
    private val known = HashMap<String, Live>()
    @Volatile private var version = 0L

    /** Abgleich über /sync: beim ersten Mal alles, danach nur Änderungen. true = geklappt. */
    private fun applySync(text: String): Boolean {
        val o = JSONObject(text)
        val full = o.optInt("full", 0) == 1
        val t = o.optLong("t") * 1000L
        if (full) known.clear()
        val rows = o.optJSONArray("rows") ?: return false
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val id = r.optString(0)
            if (r.length() >= 7) {
                val st = Station(id, r.optString(1, "Radio"), r.optString(2), r.optString(3), false)
                known[id] = Live(st, r.optString(4), r.optLong(5) * 1000L, r.optLong(6) * 1000L)
            } else if (r.length() >= 4) {
                val k = known[id] ?: continue
                k.title = r.optString(1); k.since = r.optLong(2) * 1000L; k.seen = r.optLong(3) * 1000L
            }
        }
        o.optJSONArray("gone")?.let { g -> for (i in 0 until g.length()) known.remove(g.optString(i)) }
        // Unveränderte Einträge sind laut Server weiterhin aktuell
        if (!full) known.values.forEach { if (it.seen < t) it.seen = t }
        known.values.forEach { LiveIndex.putRemote(it.station, it.title, it.since, it.seen) }
        version = o.optLong("v")
        return known.isNotEmpty()
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
            // Neu: nur Änderungen holen (viel weniger Daten). Alter Server: wie bisher alles.
            val synced = fetch("/sync?from=$version", 15_000, 10_000_000)
                ?.let { runCatching { applySync(it) }.getOrDefault(false) } == true
            if (!synced) {
                version = 0L
                val text = fetch("/now?limit=8000&music=1", 15_000, 10_000_000)
                val list = text?.let { runCatching { parse(it) }.getOrNull() }
                if (list.isNullOrEmpty()) { lastFail = t; return@withContext false }
                list.forEach { (s, n) -> LiveIndex.putRemote(s, n.first, n.second, n.third) }
            }
            LiveIndex.invalidate()
            LiveIndex.warm() // Listen hier im Hintergrund vorberechnen, damit die Anzeige nicht hängt
            lastOk = System.currentTimeMillis()
            true
        } finally {
            lock.unlock()
        }
    }

    /** Meistgespielt im Radio (24 h): Liste aus (Titel, wie oft gespielt). */
    suspend fun charts(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val text = fetch("/charts", 8000, 2_000_000) ?: return@withContext emptyList()
        runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Text.clean(it.optString("title")) to it.optInt("plays") }
            }.filter { LiveIndex.isMusic(it.first) }
        }.getOrDefault(emptyList())
    }

    /** Lieder, die Sender oft direkt vor oder nach diesem Lied spielen ("Mehr wie dieses"). */
    suspend fun similar(radioTitle: String): List<String> = withContext(Dispatchers.IO) {
        val text = fetch("/similar?q=" + URLEncoder.encode(radioTitle, "UTF-8"), 8000, 1_000_000) ?: return@withContext emptyList()
        runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title")?.let { t -> Text.clean(t) } }
                .filter { LiveIndex.isMusic(it) }
        }.getOrDefault(emptyList())
    }

    /** Gemessene Werbelänge an den Server melden (nur Sender-ID und Dauer, nichts über dich). */
    fun reportAd(stationId: String, ms: Long) {
        Thread { runCatching { fetch("/ad?s=" + URLEncoder.encode(stationId, "UTF-8") + "&ms=$ms", 5000, 10_000) } }.start()
    }

    @Volatile private var adsAt = 0L

    /** Werbelängen aller Nutzer holen (höchstens alle 6 Stunden). */
    suspend fun loadAds() = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - adsAt < 6 * 3600_000L) return@withContext
        val text = fetch("/ads", 8000, 3_000_000) ?: return@withContext
        runCatching {
            val o = JSONObject(text)
            Ads.shared = o.keys().asSequence().associateWith { o.optLong(it) }
            adsAt = System.currentTimeMillis()
        }
    }

    data class Wish(val genre: String?, val artist: String?, val ai: Boolean)

    /** "Wünsch dir was": Satz in ein Genre oder einen Künstler übersetzen (KI auf dem Server). */
    suspend fun wish(text: String): Wish? = withContext(Dispatchers.IO) {
        val t = fetch("/wish?q=" + URLEncoder.encode(text, "UTF-8"), 30_000, 100_000) ?: return@withContext null
        runCatching {
            val o = JSONObject(t)
            Wish(o.optString("genre").takeIf { it.isNotBlank() && it != "null" },
                o.optString("artist").takeIf { it.isNotBlank() && it != "null" }, o.optBoolean("ai"))
        }.getOrNull()
    }

    /** Sucht ein Lied auf dem Server. null = Server nicht erreichbar. */
    suspend fun search(query: String): List<Scanner.Hit>? = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastFail < 5 * 60_000L) return@withContext null
        val text = fetch("/search?q=" + URLEncoder.encode(query, "UTF-8"), 8000, 4_000_000)
        if (text == null) { lastFail = System.currentTimeMillis(); return@withContext null }
        val list = runCatching { parse(text) }.getOrNull() ?: return@withContext null
        lastOk = System.currentTimeMillis()
        list.map { (s, n) ->
            LiveIndex.putRemote(s, n.first, n.second, n.third)
            Scanner.Hit(s, n.first)
        }
    }
}
