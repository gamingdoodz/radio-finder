package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Charts und Song-Suche von Deezer (öffentlich, ohne Anmeldung).
 * Bekannte Lieder gibt es dort nur als 30-Sekunden-Vorschau,
 * deshalb schaut die App zusätzlich, ob der Song gerade im Radio läuft.
 */
object Deezer {
    private const val TOP_GERMANY_PLAYLIST = 1111143121L
    private val cache = HashMap<String, Pair<Long, List<Entry>>>()

    suspend fun topGermany(limit: Int = 100): List<Entry> =
        cached("top") {
            load("playlist/$TOP_GERMANY_PLAYLIST/tracks?limit=$limit").ifEmpty { load("chart/0/tracks?limit=$limit") }
        }

    suspend fun genreChart(genreId: Int, limit: Int = 50): List<Entry> =
        cached("g$genreId") { load("chart/$genreId/tracks?limit=$limit") }

    /** Bekannte Kinderlieder (über gezielte Suchen, damit keine Erwachsenen-Hits drin sind). */
    suspend fun kidsHits(): List<Entry> = cached("kids") {
        listOf("kinderlieder", "Rolf Zuckowski", "Simone Sommerland", "Bibi Blocksberg", "Volker Rosin")
            .flatMap { load("search?q=${URLEncoder.encode(it, "UTF-8")}&limit=8") }
            .distinctBy { it.id }
    }

    data class Artist(val id: Long, val name: String, val picture: String)

    /** Passender Künstler zur Suche (nur wenn der Name wirklich passt). */
    suspend fun artist(q: String): Artist? = withContext(Dispatchers.IO) {
        val text = Net.get("https://api.deezer.com/search/artist?limit=1&q=" + URLEncoder.encode(q, "UTF-8")) ?: return@withContext null
        try {
            val o = JSONObject(text).optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
            val name = o.optString("name")
            val nq = Scanner.normalize(q)
            val nn = Scanner.normalize(name)
            if (nn.isEmpty() || !(nq.contains(nn) || nn.contains(nq))) return@withContext null
            Artist(o.optLong("id"), name, o.optString("picture_big"))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun artistTop(id: Long): List<Entry> = cached("a$id") { load("artist/$id/top?limit=50") }

    suspend fun search(q: String, limit: Int = 25): List<Entry> =
        load("search?q=${URLEncoder.encode(q, "UTF-8")}&limit=$limit")

    private suspend fun cached(key: String, block: suspend () -> List<Entry>): List<Entry> {
        synchronized(cache) {
            cache[key]?.let { (t, list) -> if (System.currentTimeMillis() - t < 60 * 60_000L) return list }
        }
        val list = block()
        if (list.isNotEmpty()) synchronized(cache) { cache[key] = System.currentTimeMillis() to list }
        return list
    }

    private suspend fun load(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val text = Net.get("https://api.deezer.com/$path") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(text).optJSONArray("data") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val preview = o.optString("preview")
                if (!preview.startsWith("http")) return@mapNotNull null
                val title = o.optString("title_short").ifBlank { o.optString("title") }
                val album = o.optJSONObject("album")
                Entry(
                    id = "dz:" + o.optLong("id"),
                    kind = Kind.TRACK,
                    title = title,
                    subtitle = o.optJSONObject("artist")?.optString("name").orEmpty(),
                    url = preview,
                    artwork = album?.optString("cover_big")?.ifBlank { album.optString("cover_medium") }.orEmpty(),
                    name = title,
                    preview = true,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
