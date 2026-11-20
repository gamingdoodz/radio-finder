package de.gamingdoodz.radiofinder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    // Welche Sender durchsucht werden: deutsche, weltweit beliebte und Dance/Remix
    private val queries = listOf(
        "countrycode=DE&limit=250",
        "limit=150",
        "tag=dance&limit=50",
        "tag=remix&limit=30",
    )

    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val mutex = Mutex()
    @Volatile private var cached: List<Station>? = null

    suspend fun load(context: Context): List<Station> = mutex.withLock {
        cached?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "stations.json")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
            var list = if (fresh) readFile(file) else emptyList()
            if (list.isEmpty()) {
                list = fetchAll()
                if (list.isNotEmpty()) writeFile(file, list) else if (file.exists()) list = readFile(file)
            }
            if (list.isNotEmpty()) cached = list
            list
        }
    }

    private fun fetchAll(): List<Station> {
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
            try {
                val c = URL("https://$server/$path").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "RadioFinder/1.0")
                try {
                    if (c.responseCode == 200) {
                        return JSONArray(c.inputStream.bufferedReader().readText())
                    }
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                // nächsten Server probieren
            }
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
            name = o.optString("name").trim().ifBlank { "Unbekannter Sender" },
            url = url,
            favicon = o.optString("favicon").trim(),
            hls = o.optInt("hls", 0) == 1,
        )
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
