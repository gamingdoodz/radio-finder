package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Freie Musik von Audius: Künstler laden ihre Lieder dort selbst hoch,
 * kostenlos und legal komplett abspielbar (viele Remixe dabei).
 */
object Audius {
    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String,
        val streamUrl: String,
    )

    private const val APP = "Hitradar"
    @Volatile private var host: String? = null

    suspend fun search(query: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        load("tracks/search?query=$q&limit=$limit").take(limit)
    }

    suspend fun trending(limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        load("tracks/trending?time=week").take(limit)
    }

    private fun load(path: String): List<Track> = try {
        host?.let { h -> fetch(h, path)?.let { return parse(it, h) } }
        var result: List<Track> = emptyList()
        for (h in candidates()) {
            val data = fetch(h, path) ?: continue
            host = h
            result = parse(data, h)
            break
        }
        result
    } catch (_: Exception) {
        emptyList()
    }

    private fun candidates(): List<String> {
        val list = mutableListOf<String>()
        try {
            val arr = JSONObject(get("https://api.audius.co") ?: "{}").optJSONArray("data")
            if (arr != null) for (i in 0 until arr.length()) {
                val h = arr.optString(i).trimEnd('/')
                if (h.startsWith("https://")) list.add(h)
            }
        } catch (_: Exception) {}
        list.shuffle()
        return (list.take(3) + listOf("https://api.audius.co", "https://discoveryprovider.audius.co")).distinct()
    }

    private fun fetch(h: String, path: String): JSONArray? {
        val sep = if (path.contains('?')) "&" else "?"
        val text = get("$h/v1/$path${sep}app_name=$APP") ?: return null
        return try { JSONObject(text).optJSONArray("data") } catch (_: Exception) { null }
    }

    private fun parse(arr: JSONArray, h: String): List<Track> {
        val out = mutableListOf<Track>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("is_streamable") && !o.optBoolean("is_streamable", true)) continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val art = o.optJSONObject("artwork")?.optString("480x480").orEmpty()
            out.add(
                Track(
                    id = id,
                    title = o.optString("title").ifBlank { "Unbekannt" },
                    artist = o.optJSONObject("user")?.optString("name").orEmpty(),
                    artwork = if (art.startsWith("http")) art else "",
                    streamUrl = "$h/v1/tracks/$id/stream?app_name=$APP",
                )
            )
        }
        return out
    }

    private fun get(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Hitradar/1.0")
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
