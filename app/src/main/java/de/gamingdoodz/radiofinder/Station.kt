package de.gamingdoodz.radiofinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Station(
    val id: String,
    val name: String,
    val url: String,
    val favicon: String,
    val hls: Boolean,
)

/** Holt die Senderliste von radio-browser.info und merkt sie sich einen Tag lang. */
object StationRepository {
    private val servers = listOf(
        "de1.api.radio-browser.info",
        "fi1.api.radio-browser.info",
        "de2.api.radio-browser.info",
    )

    // Unterwegs: rund 500 Sender (spart Daten)
    private val smallQueries = listOf(
        "countrycode=DE&limit=250",
        "limit=150",
        "tag=dance&limit=50",
        "tag=remix&limit=30",
        "tag=kinder&limit=20",
        "tag=hits&limit=60",
        "tag=christmas&countrycode=DE&limit=20",
    )

    // Im WLAN: mehrere tausend Sender
    private val bigQueries = listOf(
        "limit=3500",
        "countrycode=DE&limit=900",
        "countrycode=AT&limit=200",
        "countrycode=CH&limit=200",
        "tag=dance&limit=250",
        "tag=remix&limit=120",
        "tag=charts&limit=150",
        "tag=kinder&limit=50",
        "tag=hits&limit=250",
        "tag=top 40&limit=150",
        "tag=christmas&limit=150",
        "tag=80s&limit=120",
        "tag=90s&limit=120",
        "tag=schlager&limit=120",
        "tag=party&limit=80",
        "countrycode=GB&limit=150",
        "countrycode=US&limit=150",
    )

    private val kidsQueries = listOf(
        "name=teddy&limit=10",
        "name=kiraka&limit=5",
        "name=toggo&limit=5",
        "name=kakadu&limit=5",
        "name=kinderradio&limit=10",
        "tag=kinder&limit=40",
        "tag=kinderlieder&limit=20",
        "tag=kids&limit=30",
        "tag=children&limit=20",
    )

    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val mutex = Mutex()
    private val cache = HashMap<String, List<Station>>()

    suspend fun load(context: Context, big: Boolean = Net.isUnmetered(context)): List<Station> =
        if (big) loadList(context, "stations_big.json", bigQueries)
        else loadList(context, "stations.json", smallQueries)

    suspend fun kids(context: Context): List<Station> = loadList(context, "kids.json", kidsQueries)

    /** Welche Sender bei einer Suche durchsucht werden (WLAN: viele, mobil mit Datensparen: wenige). */
    suspend fun scanList(context: Context): List<Station> {
        if (Net.isUnmetered(context)) return load(context, big = true)
        val small = load(context, big = false)
        return if (Settings.saver(context)) small.take(150) else small
    }

    private val tagCache = HashMap<String, List<Station>>()

    /** Sender zu einem Genre (erst deutsche, dann weltweit). */
    suspend fun byTag(tag: String): List<Station> {
        synchronized(tagCache) { tagCache[tag]?.let { return it } }
        val t = java.net.URLEncoder.encode(tag, "UTF-8")
        val list = withContext(Dispatchers.IO) {
            fetchAll(listOf("tag=$t&countrycode=DE&limit=30", "tag=$t&limit=30"))
        }
        if (list.isNotEmpty()) synchronized(tagCache) { tagCache[tag] = list }
        return list
    }

    private suspend fun loadList(context: Context, fileName: String, queries: List<String>): List<Station> =
        mutex.withLock {
            cache[fileName]?.let { return@withLock it }
            val list = withContext(Dispatchers.IO) {
                val file = File(context.filesDir, fileName)
                val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
                var l = if (fresh) readFile(file) else emptyList()
                if (l.isEmpty()) {
                    l = fetchAll(queries)
                    if (l.isNotEmpty()) writeFile(file, l) else if (file.exists()) l = readFile(file)
                }
                l
            }
            if (list.isNotEmpty()) cache[fileName] = list
            list
        }

    private fun fetchAll(queries: List<String>): List<Station> {
        val map = LinkedHashMap<String, Station>()
        for (q in queries) {
            val arr = fetchJson("json/stations/search?$q&order=clickcount&reverse=true&hidebroken=true") ?: continue
            for (i in 0 until arr.length()) {
                parse(arr.optJSONObject(i))?.let { map.putIfAbsent(it.id, it) }
            }
        }
        return map.values.toList()
    }

    private fun fetchJson(path: String): JSONArray? {
        for (server in servers) {
            val text = Net.get("https://$server/$path", 8000, 20000, 12_000_000) ?: continue
            try { return JSONArray(text) } catch (_: Exception) {}
        }
        return null
    }

    private fun parse(o: JSONObject?): Station? {
        if (o == null) return null
        val id = o.optString("stationuuid")
        val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
        if (id.isBlank() || url.isBlank()) return null
        return Station(
            id = id,
            name = cleanName(o.optString("name")),
            url = url,
            favicon = o.optString("favicon").trim(),
            hls = o.optInt("hls", 0) == 1,
        )
    }

    /** Macht Sendernamen lesbar: "_DEEJAY REMIXE_ Your Radio" -> "DEEJAY REMIXE Your Radio". */
    fun cleanName(raw: String): String {
        val n = raw.replace(Regex("[_#*|~=]+"), " ")
            .replace(Regex("-{2,}>?"), " ")
            .replace(Regex("^[^\\p{L}\\p{N}]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return n.ifBlank { "Unbekannter Sender" }
    }

    private fun readFile(file: File): List<Station> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeFile(file: File, list: List<Station>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("stationuuid", s.id)
                    .put("name", s.name)
                    .put("url_resolved", s.url)
                    .put("favicon", s.favicon)
                    .put("hls", if (s.hls) 1 else 0)
            )
        }
        try { file.writeText(arr.toString()) } catch (_: Exception) {}
    }
}
