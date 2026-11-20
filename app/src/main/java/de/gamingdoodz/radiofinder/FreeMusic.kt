package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Jamendo: über 600.000 freie Lieder von Künstlern, legal und komplett hörbar. */
object Jamendo {
    private const val CLIENT_ID = "2ecf94df"

    /** Hohe Qualität (VBR ~192 kbit/s) oder sparsam (96 kbit/s). */
    @Volatile var highQuality = false

    suspend fun search(q: String, limit: Int = 20) = tracks("search=${enc(q)}&order=relevance&limit=$limit")
    suspend fun popular(limit: Int = 25) = tracks("order=popularity_week&limit=$limit")
    suspend fun kids(limit: Int = 30) = tracks("fuzzytags=kids&order=popularity_total&limit=$limit")
    suspend fun byTag(tag: String, limit: Int = 25) = tracks("tags=${enc(tag)}&order=popularity_week&limit=$limit")

    /** Sehr viele Lieder eines Genres (bis 200), gut gemischt. */
    suspend fun many(tag: String) = tracks("tags=${enc(tag)}&order=popularity_total&limit=200").shuffled()

    private suspend fun tracks(params: String): List<Entry> = withContext(Dispatchers.IO) {
        val text = Net.get(
            "https://api.jamendo.com/v3.0/tracks/?client_id=$CLIENT_ID&format=json&audioformat=${if (highQuality) "mp32" else "mp31"}&imagesize=300&$params"
        ) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(text).optJSONArray("results") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val audio = o.optString("audio")
                if (!audio.startsWith("http")) return@mapNotNull null
                val name = o.optString("name").ifBlank { "Unbekannt" }
                Entry(
                    id = "jm:" + o.optString("id"),
                    kind = Kind.TRACK,
                    title = name,
                    subtitle = o.optString("artist_name").ifBlank { "Jamendo" } + " · Jamendo",
                    url = audio,
                    artwork = o.optString("album_image").ifBlank { o.optString("image") },
                    name = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Internet Archive: riesiges Archiv mit freier Musik, alten Liedern, Kinderliedern. */
object Archive {
    private data class Doc(val id: String, val title: String, val creator: String)

    suspend fun search(q: String, items: Int = 4, perItem: Int = 4, popular: Boolean = false): List<Entry> =
        withContext(Dispatchers.IO) {
            val docs = searchDocs(q, items, popular)
            coroutineScope { docs.map { async { files(it, perItem) } }.awaitAll().flatten() }
        }

    private fun searchDocs(q: String, rows: Int, popular: Boolean): List<Doc> {
        val query = enc("($q) AND mediatype:(audio) AND NOT collection:(podcasts)")
        val sort = if (popular) "&sort%5B%5D=downloads+desc" else ""
        val text = Net.get(
            "https://archive.org/advancedsearch.php?q=$query&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator$sort&rows=$rows&page=1&output=json"
        ) ?: return emptyList()
        return try {
            val docs = JSONObject(text).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
            (0 until docs.length()).mapNotNull { i ->
                val o = docs.optJSONObject(i) ?: return@mapNotNull null
                Doc(o.optString("identifier"), o.optString("title"), firstString(o.opt("creator")))
            }.filter { it.id.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun files(doc: Doc, perItem: Int): List<Entry> {
        val text = Net.get("https://archive.org/metadata/${doc.id}", maxBytes = 3_000_000) ?: return emptyList()
        return try {
            val files = JSONObject(text).optJSONArray("files") ?: return emptyList()
            (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".mp3", ignoreCase = true) }
                .sortedBy { it.optString("name") }
                .take(perItem)
                .map { f ->
                    val name = f.optString("name")
                    val title = f.optString("title").ifBlank { name.substringAfterLast('/').removeSuffix(".mp3") }
                    val who = f.optString("creator").ifBlank { doc.creator }.ifBlank { doc.title }
                    val path = name.split("/").joinToString("/") { enc(it).replace("+", "%20") }
                    Entry(
                        id = "ia:${doc.id}/$name",
                        kind = Kind.TRACK,
                        title = title,
                        subtitle = "$who · Archiv",
                        url = "https://archive.org/download/${doc.id}/$path",
                        artwork = "https://archive.org/services/img/${doc.id}",
                        name = title,
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Freie Hörbücher von LibriVox (deutsch), als "Shows" mit Kapiteln. */
    suspend fun librivox(extra: String, rows: Int = 30): List<Entry> = withContext(Dispatchers.IO) {
        val q = "collection:(librivoxaudio) AND language:(German OR Deutsch OR ger)" + if (extra.isNotBlank()) " AND ($extra)" else ""
        val text = Net.get(
            "https://archive.org/advancedsearch.php?q=${enc(q)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&sort%5B%5D=downloads+desc&rows=$rows&page=1&output=json"
        ) ?: return@withContext emptyList()
        try {
            val docs = JSONObject(text).optJSONObject("response")?.optJSONArray("docs") ?: return@withContext emptyList()
            (0 until docs.length()).mapNotNull { i ->
                val o = docs.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("identifier")
                if (id.isBlank()) return@mapNotNull null
                val title = o.optString("title").substringBefore(" (LibriVox").trim()
                Entry("lv:$id", Kind.SHOW, title, firstString(o.opt("creator")).ifBlank { "LibriVox" } + " · Hörbuch", "ia:$id",
                    "https://archive.org/services/img/$id", title)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Kapitel eines Archiv-Hörbuchs. */
    suspend fun chapters(show: Entry): List<Entry> = withContext(Dispatchers.IO) {
        val id = show.url.removePrefix("ia:")
        val text = Net.get("https://archive.org/metadata/$id", maxBytes = 5_000_000) ?: return@withContext emptyList()
        try {
            val files = JSONObject(text).optJSONArray("files") ?: return@withContext emptyList()
            val mp3 = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".mp3", ignoreCase = true) }
            val small = mp3.filter { it.optString("name").contains("_64kb") }
            (small.ifEmpty { mp3 }).sortedBy { it.optString("name") }.map { f ->
                val name = f.optString("name")
                val title = f.optString("title").ifBlank { name.substringAfterLast('/').removeSuffix(".mp3").replace('_', ' ') }
                val path = name.split("/").joinToString("/") { enc(it).replace("+", "%20") }
                Entry("ia:$id/$name", Kind.TRACK, title, show.name, "https://archive.org/download/$id/$path", show.artwork, title)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun firstString(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> v.optString(0)
        else -> ""
    }
}

/** Alle freien Musikquellen zusammen. */
object FreeMusic {
    suspend fun search(q: String): List<Entry> = coroutineScope {
        val j = async { safe { Jamendo.search(q, 20) } }
        val a = async { safe { Audius.search(q, 15).map { it.toEntry() } } }
        val ia = async { safe { Archive.search(q, 3, 3) } }
        (j.await() + a.await() + ia.await()).distinctBy { it.id }
    }

    suspend fun charts(): List<Entry> = coroutineScope {
        val j = async { safe { Jamendo.popular(20) } }
        val a = async { safe { Audius.trending(15).map { it.toEntry() } } }
        (j.await() + a.await()).distinctBy { it.id }
    }

    /** Kinderlieder nur aus Jamendo und dem Archiv (dort ist der Inhalt verlässlicher). */
    suspend fun kids(): List<Entry> = coroutineScope {
        val j1 = async { safe { Jamendo.search("kinderlieder", 20) } }
        val j2 = async { safe { Jamendo.kids(25) } }
        val ia = async { safe { Archive.search("kinderlieder", 4, 5, popular = true) } }
        (j1.await() + j2.await() + ia.await()).distinctBy { it.id }
    }

    private suspend fun safe(block: suspend () -> List<Entry>): List<Entry> =
        try { block() } catch (_: Exception) { emptyList() }
}
