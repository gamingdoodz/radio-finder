package de.gamingdoodz.radiofinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Der Hitradar-Server beobachtet rund um die Uhr tausende Sender.
 * Ist er erreichbar, fragt die App nur noch ihn: schneller, spart Akku und Daten.
 * Ist er weg, scannt die App wie bisher selbst.
 */
object Radar {
    /** Eingebaute Adressen (Rückfall): verschlüsselt zuerst, sonst die direkte Adresse. */
    private val builtIn = listOf("https://130-61-78-232.sslip.io:8080", "http://130.61.78.232")

    /**
     * Aktuelle Server-Adressen stehen in config/servers.json im Repo (kostenlos über GitHub ausgeliefert).
     * Zieht der Server um, reicht eine Änderung dort: die App holt die Liste einmal am Tag.
     * Die eingebauten Adressen bleiben immer als letzter Rückfall dabei.
     */
    private val configUrls = listOf(
        "https://raw.githubusercontent.com/gamingdoodz/radio-finder/main/config/servers.json",
        "https://cdn.jsdelivr.net/gh/gamingdoodz/radio-finder@main/config/servers.json",
    )
    @Volatile private var servers: List<String> = builtIn
    @Volatile private var active = 0
    @Volatile private var configStarted = false

    /** Fragt den aktiven Server; geht er nicht, den nächsten. */
    private fun fetch(path: String, readMs: Int, maxBytes: Int): String? {
        val list = servers
        for (n in list.indices) {
            val i = (active + n) % list.size
            val text = Net.get(list[i] + path, 4000, readMs, maxBytes)
            if (text != null) { active = i; return text }
        }
        return null
    }

    /** Liest {"servers":[...]} und übernimmt die Adressen (eingebaute immer hinten dran). */
    private fun applyConfig(text: String): Boolean {
        val arr = runCatching { JSONObject(text).optJSONArray("servers") }.getOrNull() ?: return false
        val list = (0 until arr.length()).map { arr.optString(it).trim().trimEnd('/') }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .distinct().take(5)
        if (list.isEmpty()) return false
        val all = (list + builtIn).distinct()
        if (all != servers) { servers = all; active = 0 }
        return true
    }

    /** Gespeicherte Liste sofort nutzen, höchstens einmal am Tag neu holen (im Hintergrund). */
    private fun loadConfig(context: Context) {
        if (configStarted) return
        configStarted = true
        Thread {
            runCatching {
                val p = context.getSharedPreferences("radar_config", Context.MODE_PRIVATE)
                p.getString("json", null)?.let { applyConfig(it) }
                if (System.currentTimeMillis() - p.getLong("at", 0L) < 24 * 3600_000L) return@runCatching
                for (u in configUrls) {
                    val t = Net.get(u, 5000, 8000, 50_000) ?: continue
                    if (applyConfig(t)) {
                        p.edit().putString("json", t).putLong("at", System.currentTimeMillis()).apply()
                        break
                    }
                }
            }
            cleanCache(context)
        }.start()
    }

    // ---- Zwischenspeicher auf dem Handy: Antworten, die sich selten ändern, übersteht auch einen Neustart der App ----
    private fun cacheFile(path: String): File? {
        val c = appContext ?: return null
        val dir = File(c.cacheDir, "radar")
        if (!dir.exists()) dir.mkdirs()
        val h = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, h)
    }

    /**
     * Wie fetch, aber höchstens alle maxAgeMs wirklich beim Server. Ist der Server weg, gilt die letzte Antwort.
     * keep: nur solche Antworten speichern (z. B. Cover nur, wenn wirklich eins gefunden wurde).
     */
    private fun fetchCached(path: String, maxAgeMs: Long, readMs: Int, maxBytes: Int, keep: (String) -> Boolean = { true }): String? {
        val f = runCatching { cacheFile(path) }.getOrNull()
        if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
            runCatching { f.readText() }.getOrNull()?.let { return it }
        }
        val text = fetch(path, readMs, maxBytes)
        if (text != null) {
            if (f != null && keep(text)) runCatching { f.writeText(text) }
            return text
        }
        return if (f != null && f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    /** Alte Einträge (über 8 Tage) und zu viele Dateien wegräumen. */
    private fun cleanCache(context: Context) = runCatching {
        val files = File(context.cacheDir, "radar").listFiles() ?: return@runCatching
        val old = System.currentTimeMillis() - 8 * 24 * 3600_000L
        files.filter { it.lastModified() < old }.forEach { it.delete() }
        val rest = files.filter { it.exists() }.sortedByDescending { it.lastModified() }
        rest.drop(1500).forEach { it.delete() }
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var lastOk = 0L
    @Volatile private var lastTry = 0L
    @Volatile private var lastFail = 0L
    private val lock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
        Region.load(context)
        loadConfig(context.applicationContext)
    }

    /** Länder geändert: alles neu vom Server holen. */
    fun resetSync() { synchronized(known) { resetGen++; known.clear(); version = 0L }; lastTry = 0L; LiveIndex.clear() }

    /** Zählt jedes Zurücksetzen: Antworten, die noch für die alte Region angefragt wurden, werden verworfen. */
    @Volatile private var resetGen = 0


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
            if (!Region.allows(if (o.has("cc")) o.optString("cc") else null)) return@mapNotNull null
            Station(id, o.optString("name", "Radio"), url, o.optString("favicon"), false) to
                Triple(title, if (o.optBoolean("exact", true)) o.optLong("since") * 1000L else -1L, o.optLong("seen") * 1000L)
        }
    }

    /** Was der Server zuletzt gemeldet hat: Sender-ID -> (Sender, Titel, seit, gesehen). */
    private class Live(val station: Station, var title: String, var since: Long, var seen: Long)
    /**
     * Liedanfang, wie ihn der Server gesehen hat (auf Handy-Uhr umgerechnet), nur wenn er ihn wirklich beobachtet hat
     * und es derselbe Titel ist. 0 = unbekannt. Für den Ausgleich "Server sieht den Titel früher, als man ihn hört".
     */
    fun serverStart(stationId: String, normalizedTitle: String): Long = synchronized(known) {
        val k = known[stationId] ?: return 0L
        if (k.since <= 0L) return 0L
        if (!MixLogic.sameSong(Scanner.normalize(k.title), normalizedTitle)) return 0L
        k.since + clockOffset
    }

    /** Handy-Uhr minus Server-Uhr (ms), beim letzten Abgleich gemessen. */
    @Volatile var clockOffset = 0L
        private set
    private val known = HashMap<String, Live>()
    @Volatile private var version = 0L

    /** Abgleich über /sync: beim ersten Mal alles, danach nur Änderungen. true = geklappt. */
    private fun applySync(text: String, gen: Int): Boolean = synchronized(known) {
        if (gen != resetGen) return false
        val o = JSONObject(text)
        val full = o.optInt("full", 0) == 1
        val t = o.optLong("t") * 1000L
        if (full) known.clear()
        val rows = o.optJSONArray("rows") ?: return false
        // Nur geänderte Sender komplett übernehmen, bei den anderen nur die Zeit erneuern
        val changed = HashSet<String>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val id = r.optString(0)
            changed.add(id)
            if (r.length() >= 7) {
                if (!Region.allows(if (r.length() >= 8) r.optString(7) else null)) continue
                val st = Station(id, r.optString(1, "Radio"), r.optString(2), r.optString(3), false)
                // since = -1: Liedanfang unbekannt (Server hat das Lied erst mittendrin gesehen)
                val exact = r.length() < 9 || r.optInt(8, 1) == 1
                // Anfang unbekannt: als negative Zahl, wann der Server das Lied zuerst gesehen hat ("läuft mindestens seit")
                known[id] = Live(st, r.optString(4), if (exact) r.optLong(5) * 1000L else -(r.optLong(5) * 1000L).coerceAtLeast(2L), r.optLong(6) * 1000L)
            } else if (r.length() >= 4) {
                val k = known[id] ?: continue
                val exact = r.length() < 5 || r.optInt(4, 1) == 1
                k.title = r.optString(1); k.since = if (exact) r.optLong(2) * 1000L else -(r.optLong(2) * 1000L).coerceAtLeast(2L); k.seen = r.optLong(3) * 1000L
            }
        }
        o.optJSONArray("gone")?.let { g -> for (i in 0 until g.length()) known.remove(g.optString(i)) }
        // Unveränderte Einträge sind laut Server weiterhin aktuell
        if (!full) known.values.forEach { if (it.seen < t) it.seen = t }
        // Handy-Uhr geht anders als die Server-Uhr: Zeiten des Servers auf die Handy-Uhr umrechnen
        // (sonst wären bei 3 Min. Abweichung alle Lieder "fast vorbei" oder "gerade angefangen")
        if (t > 0) clockOffset = (System.currentTimeMillis() - t).let { if (kotlin.math.abs(it) < 45_000L) 0L else it }   // kleine Werte = Alter des Server-Stands + Netz, keine falsche Uhr
        val off = clockOffset
        fun since(v: Long) = when { off == 0L -> v; v > 0 -> v + off; v < -1L -> -((-v) + off).coerceAtLeast(2L); else -> v }
        fun seen(v: Long) = if (off != 0L && v > 0) v + off else v
        known.forEach { (id, it) ->
            if (full || id in changed) LiveIndex.putRemote(it.station, it.title, since(it.since), seen(it.seen))
            else LiveIndex.touchRemote(it.station, it.title, since(it.since), seen(it.seen))
        }
        version = o.optLong("v")
        return known.isNotEmpty()
    }

    /** App-Start: die 1.500 neuesten Lieder sofort (≈ 150 KB statt 2,7 MB), der Rest kommt danach. */
    suspend fun quickStart(): Boolean = withContext(Dispatchers.IO) {
        if (version != 0L) return@withContext false
        val text = fetch("/now?limit=1500&music=1", 8000, 3_000_000) ?: return@withContext false
        val list = runCatching { parse(text) }.getOrNull().orEmpty()
        list.forEach { (s, n) -> LiveIndex.putRemote(s, n.first, n.second, n.third) }
        if (list.isNotEmpty()) LiveIndex.rebuild()
        list.isNotEmpty()
    }

    /** Holt alles, was gerade läuft, in den LiveIndex (höchstens einmal pro Intervall). */
    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis()
        if (!force && t - lastTry < interval()) return@withContext ok
        // Nach einem Fehler 1 Minute Pause, damit die App nicht dauernd wartet
        if (t - lastFail < 60_000L) return@withContext false
        if (!lock.tryLock()) return@withContext ok
        try {
            lastTry = t
            val gen = resetGen
            // Neu: nur Änderungen holen (viel weniger Daten). Alter Server: wie bisher alles.
            val synced = fetch("/sync?from=$version", 15_000, 10_000_000)
                ?.let { runCatching { applySync(it, gen) }.getOrDefault(false) } == true
            if (gen != resetGen) return@withContext false
            if (!synced) {
                version = 0L
                val text = fetch("/now?limit=8000&music=1", 15_000, 10_000_000)
                val list = text?.let { runCatching { parse(it) }.getOrNull() }
                if (list.isNullOrEmpty()) { lastFail = t; return@withContext false }
                if (gen != resetGen) return@withContext false
                list.forEach { (s, n) -> LiveIndex.putRemote(s, n.first, n.second, n.third) }
            }
            LiveIndex.rebuild() // Listen hier im Hintergrund neu berechnen, damit die Anzeige nicht hängt
            lastOk = System.currentTimeMillis()
            true
        } finally {
            lock.unlock()
        }
    }

    /** Meistgespielt im Radio (24 h): Liste aus (Titel, wie oft gespielt). */
    @Volatile private var chartsCache: Pair<Long, List<Pair<String, Int>>>? = null

    /** Wie oft lief dieses Lied heute im Radio? (normalisierter Titel -> Anzahl, für "bekannte Lieder zuerst") */
    @Volatile var chartPlays: Map<String, Int> = emptyMap()
        private set

    /** Neu & angesagt: Lieder, die gerade deutlich öfter im Radio laufen als vorher. */
    suspend fun rising(): List<String> = withContext(Dispatchers.IO) {
        val text = fetchCached("/rising", 10 * 60_000L, 8000, 500_000) ?: return@withContext emptyList()
        runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("title")?.let { t -> Text.clean(t) } }
                .filter { LiveIndex.isMusic(it) }
        }.getOrDefault(emptyList())
    }

    suspend fun charts(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        chartsCache?.let { (at, l) -> if (System.currentTimeMillis() - at < 10 * 60_000L && l.isNotEmpty()) return@withContext l }
        val text = fetchCached("/charts", 10 * 60_000L, 8000, 2_000_000) ?: return@withContext chartsCache?.second.orEmpty()
        val list = runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Text.clean(it.optString("title")) to it.optInt("plays") }
            }.filter { LiveIndex.isMusic(it.first) }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) {
            chartsCache = System.currentTimeMillis() to list
            chartPlays = list.associate { Scanner.normalize(it.first) to it.second }
        }
        list
    }

    /** Lieder, die Sender oft direkt vor oder nach diesem Lied spielen ("Mehr wie dieses"). */
    suspend fun similar(radioTitle: String): List<String> = withContext(Dispatchers.IO) {
        val text = fetchCached("/similar?q=" + URLEncoder.encode(radioTitle, "UTF-8"), 3600_000L, 8000, 1_000_000) ?: return@withContext emptyList()
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
        val text = fetchCached("/ads", 6 * 3600_000L, 8000, 3_000_000) ?: return@withContext
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

    /** Radiosender nach Namen suchen (alle ~50.000 auf dem Server). null = Server nicht erreichbar. */
    suspend fun stations(query: String): List<Entry>? = withContext(Dispatchers.IO) {
        val text = fetch("/stations?q=" + URLEncoder.encode(query, "UTF-8"), 8000, 2_000_000) ?: return@withContext null
        runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val st = Station(o.optString("id"), StationRepository.cleanName(o.optString("name")), o.optString("url"), o.optString("favicon"), false,
                    o.optString("tags").lowercase().take(300))
                val t = Text.clean(o.optString("title"))
                if (t.isNotBlank()) LiveIndex.put(st, t)
                // Als Sender anzeigen (Titel = Sendername), das laufende Lied in der Unterzeile
                Entry("st:" + st.id, Kind.STATION, st.name, if (LiveIndex.isMusic(t)) "▶ $t" else "Internetradio", st.url, st.favicon, st.name)
            }
        }.getOrNull()
    }

    /** Cover vom Server (einmal für alle Nutzer geholt). null = Server nicht erreichbar, leer = kein Cover. */
    class CoverInfo(val art: String, val title: String, val artist: String, val durMs: Long)

    fun coverBlocking(radioTitle: String): CoverInfo? {
        val text = fetchCached("/cover?q=" + URLEncoder.encode(radioTitle, "UTF-8"), 7 * 24 * 3600_000L, 10_000, 100_000,
            keep = { runCatching { JSONObject(it).optString("art").startsWith("http") }.getOrDefault(false) }) ?: return null
        return runCatching {
            val o = JSONObject(text)
            CoverInfo(o.optString("art"), o.optString("title"), o.optString("artist"), o.optLong("dur") * 1000L)
        }.getOrNull()
    }

    /** Sender direkt vom Server prüfen lassen: Sender-ID -> (Titel, seit). null = Server nicht erreichbar. */
    suspend fun verify(ids: List<String>): Map<String, Pair<String, Long>>? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val text = fetch("/verify?ids=" + ids.joinToString(","), 12_000, 200_000) ?: return@withContext null
        runCatching {
            val o = JSONObject(text)
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                Text.clean(v.optString("title")) to v.optLong("since").let { if (it > 0) it * 1000L + clockOffset else 0L }
            }
        }.getOrNull()
    }

    /** Senderliste vom Server ("popular", "kids", "tag:rock"). null = nicht erreichbar. */
    fun listBlocking(type: String): List<Station>? {
        val text = fetchCached("/list?t=" + URLEncoder.encode(type, "UTF-8"), 3600_000L, 10_000, 2_000_000) ?: return null
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Station(o.optString("id"), StationRepository.cleanName(o.optString("name")), o.optString("url"), o.optString("favicon"), false,
                    o.optString("tags").lowercase().take(300))
            }.filter { it.id.isNotBlank() && it.url.isNotBlank() && !Blocklist.blocked(it.id, it.url) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Anonym melden, was gehört/gemerkt wurde (für "Beliebt bei Hitradar-Hörern"). Ohne Nutzerdaten. */
    fun event(type: String, value: String) {
        val c = appContext ?: return
        if (!Settings.share(c)) return
        Thread { runCatching { fetch("/event?type=$type&v=" + URLEncoder.encode(value, "UTF-8"), 5000, 10_000) } }.start()
    }

    data class Popular(val songs: List<String>, val mixes: List<String>)
    @Volatile private var popularCache: Pair<Long, Popular>? = null

    /** Was Hitradar-Hörer gerade mögen (Lieder und Mixe), höchstens alle 10 Minuten neu. */
    suspend fun popular(): Popular? = withContext(Dispatchers.IO) {
        popularCache?.let { (at, p) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return@withContext p }
        val text = fetchCached("/popular", 10 * 60_000L, 8000, 1_000_000) ?: return@withContext popularCache?.second
        runCatching {
            val o = JSONObject(text)
            val s = o.optJSONArray("songs") ?: JSONArray()
            val m = o.optJSONArray("mixes") ?: JSONArray()
            Popular((0 until s.length()).map { Text.clean(s.getJSONObject(it).optString("title")) }.filter { LiveIndex.isMusic(it) },
                (0 until m.length()).map { m.getJSONObject(it).optString("key") })
                .also { popularCache = System.currentTimeMillis() to it }
        }.getOrNull()
    }

    /** Sucht ein Lied auf dem Server. null = Server nicht erreichbar. */
    suspend fun search(query: String): List<Scanner.Hit>? = withContext(Dispatchers.IO) {
        // Suche ist vom Nutzer gewollt: immer fragen, bei einem Aussetzer einmal kurz nachfassen
        val text = fetch("/search?q=" + URLEncoder.encode(query, "UTF-8"), 8000, 4_000_000)
        val answer = text ?: run { kotlinx.coroutines.delay(1500); fetch("/search?q=" + URLEncoder.encode(query, "UTF-8"), 8000, 4_000_000) }
        if (answer == null) { lastFail = System.currentTimeMillis(); return@withContext null }
        val list = runCatching { parse(answer) }.getOrNull() ?: return@withContext null
        lastOk = System.currentTimeMillis()
        list.map { (s, n) ->
            LiveIndex.putRemote(s, n.first, n.second, n.third)
            Scanner.Hit(s, n.first)
        }
    }
}
