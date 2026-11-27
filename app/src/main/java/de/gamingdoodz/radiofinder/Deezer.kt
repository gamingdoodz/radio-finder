package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
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
        listOf(
            "Rolf Zuckowski", "Simone Sommerland", "Deine Freunde", "Volker Rosin", "Detlev Jöcker",
            "Bibi Blocksberg", "Bibi und Tina", "Benjamin Blümchen", "Lichterkinder", "Kinderlieder Superstar",
            "Die Schlümpfe", "Frederik Vahle", "Sternschnuppe Kinderlieder", "Kikaninchen", "Paw Patrol Lied",
            "Die Eiskönigin Lied", "Pippi Langstrumpf Lied", "Biene Maja Lied", "Kinderlieder Klassiker",
            "Kinderliederbande", "Bummi", "Kinderlieder zum Mitsingen", "Disney Songs deutsch", "Der König der Löwen deutsch",
            "Vaiana deutsch", "Encanto deutsch", "Dschungelbuch Probiers mal mit Gemütlichkeit", "Feuerwehrmann Sam Titellied",
            "Peppa Wutz Lied", "Bob der Baumeister Lied", "Pumuckl Lied", "Wickie Lied", "Heidi Titellied", "Löwenzahn Lied",
            "Sendung mit der Maus Lied", "Baby Shark Deutsch", "Alle meine Entchen", "Hänschen klein", "Die Vogelhochzeit",
            "Aramsamsam", "Der Gorilla mit der Sonnenbrille", "Meine Oma fährt im Hühnerstall Motorrad", "Tschu Tschu wa",
            "Schlaflieder Kinder", "Kinderdisco", "Toggo Music", "Sing Kinderlieder", "Conni Lied"
        ).let { qs ->
            // Mehrere Suchen gleichzeitig (höchstens 6), vorher dauerte der erste Kinder-Mix bis zu einer Minute
            val gate = kotlinx.coroutines.sync.Semaphore(6)
            kotlinx.coroutines.coroutineScope {
                qs.map { q -> async { gate.withPermit { load("search?q=${URLEncoder.encode(q, "UTF-8")}&limit=8") } } }.awaitAll()
            }.flatten()
        }
            .distinctBy { it.id }
            .sortedByDescending { rank[it.id] ?: 0 }
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
            // Unbekannte Bands, die zufällig wie ein Lied heißen, nicht als Künstler werten
            if (o.optLong("nb_fan", 0L) < 20_000) return@withContext null
            Artist(o.optLong("id"), name, o.optString("picture_big"))
        } catch (_: Exception) {
            null
        }
    }

    /** Lieder einer Deezer-Playlist, gefunden über ihren Namen (z. B. "80er Hits"). */
    private val pictures = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Bild einer Playlist (eine kleine Anfrage statt aller Lieder). */
    suspend fun playlistPicture(name: String): String? {
        pictures[name]?.let { return it.ifBlank { null } }
        val text = withContext(Dispatchers.IO) {
            Net.get("https://api.deezer.com/search/playlist?limit=1&q=" + URLEncoder.encode(name, "UTF-8"))
        } ?: return null
        val pic = try { JSONObject(text).optJSONArray("data")?.optJSONObject(0)?.optString("picture_medium").orEmpty() } catch (_: Exception) { "" }
        pictures[name] = pic
        return pic.ifBlank { null }
    }

    suspend fun playlistByName(name: String): List<Entry> = cached("p:$name") {
        val text = withContext(Dispatchers.IO) {
            Net.get("https://api.deezer.com/search/playlist?limit=1&q=" + URLEncoder.encode(name, "UTF-8"))
        }
        val id = try { JSONObject(text ?: "{}").optJSONArray("data")?.optJSONObject(0)?.optLong("id") } catch (_: Exception) { null }
        if (id == null || id == 0L) emptyList() else load("playlist/$id/tracks?limit=100")
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

    /** Beliebtheit laut Deezer (höher = bekannter). */
    val rank = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Liedlänge in ms (für Laufleiste und "Lied ist vorbei"). */
    val durations = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
                rank["dz:" + o.optLong("id")] = o.optInt("rank")
                durations["dz:" + o.optLong("id")] = o.optInt("duration") * 1000L
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
