package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sucht ein Lied in vielen Radiosendern gleichzeitig.
 * Trick: Viele Sender laufen auf Icecast-Servern mit einer kleinen Statusseite,
 * auf der steht, was gerade auf ALLEN Sendern dieses Servers läuft.
 * Nur wo das nicht geht, wird der Sender kurz selbst angezapft.
 */
object Scanner {
    data class Hit(val station: Station, val title: String)

    /** Treffer der letzten Suche, z. B. für Android Auto. */
    @Volatile var lastHits: List<Hit> = emptyList()

    private class FoundException : Exception()

    private val noStatusPage: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(64)

    fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return n.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    fun words(query: String): List<String> = normalize(query).split(" ").filter { it.isNotBlank() }

    fun matches(text: String, words: List<String>): Boolean {
        val t = normalize(text)
        return words.isNotEmpty() && words.all { t.contains(it) }
    }

    /** Fragt bei allen Sendern ab, was gerade läuft, und meldet jeden Titel. */
    suspend fun titles(
        stations: List<Station>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onTitle: (Station, String) -> Unit,
    ) {
        val done = AtomicInteger()
        val total = stations.size
        val sem = Semaphore(80)
        val groups = stations.groupBy { serverKey(it) }
        coroutineScope {
            for ((key, group) in groups) {
                launch(io) {
                    val statusTitles: Map<String, String>? =
                        if (key == null || key in noStatusPage) null
                        else sem.withPermit { icecastTitles(key) }.also { if (it == null) noStatusPage.add(key) }

                    for (s in group) {
                        launch(io) {
                            val title = statusTitles?.let { titleFor(s, it) }
                                ?: if (s.hls) null else sem.withPermit { IcyReader.fetchTitle(s.url) }
                            if (title != null) {
                                LiveIndex.put(s, title)
                                onTitle(s, title)
                            }
                            onProgress(done.incrementAndGet(), total)
                        }
                    }
                }
            }
        }
    }

    suspend fun scan(
        stations: List<Station>,
        query: String,
        stopAfterFirst: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onHit: (Hit) -> Unit = {},
    ): List<Hit> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val hits = Collections.synchronizedList(mutableListOf<Hit>())
        try {
            titles(stations, onProgress) { s, title ->
                if (matches(title, words)) {
                    val hit = Hit(s, title)
                    hits.add(hit)
                    onHit(hit)
                    if (stopAfterFirst) throw FoundException()
                }
            }
        } catch (_: FoundException) {
            // gefunden, Rest abgebrochen
        }
        return synchronized(hits) { hits.toList() }
    }

    private fun serverKey(s: Station): String? = try {
        if (s.hls) null else {
            val u = URL(s.url)
            val port = if (u.port == -1) u.defaultPort else u.port
            "${u.protocol}://${u.host}:$port"
        }
    } catch (_: Exception) {
        null
    }

    private fun titleFor(s: Station, map: Map<String, String>): String? {
        val path = try { URL(s.url).path.ifEmpty { "/" } } catch (_: Exception) { return null }
        return map[path] ?: map[path.trimEnd('/')] ?: if (map.size == 1) map.values.first() else null
    }

    /** Liest die Icecast-Statusseite: Mount-Pfad -> laufender Titel. */
    private fun icecastTitles(key: String): Map<String, String>? {
        val text = Net.get("$key/status-json.xsl", 3000, 3000, 1_000_000) ?: return null
        return try {
            val stats = JSONObject(text).optJSONObject("icestats") ?: return null
            val sources = when (val src = stats.opt("source")) {
                is JSONArray -> (0 until src.length()).mapNotNull { src.optJSONObject(it) }
                is JSONObject -> listOf(src)
                else -> emptyList()
            }
            val map = HashMap<String, String>()
            for (o in sources) {
                val path = try { URL(o.optString("listenurl")).path } catch (_: Exception) { continue }
                val title = o.optString("title").trim()
                val artist = o.optString("artist").trim()
                val full = if (artist.isNotEmpty() && !title.contains(artist, ignoreCase = true)) {
                    if (title.isEmpty()) artist else "$artist - $title"
                } else title
                if (full.isNotEmpty() && full != "-") map[path] = full
            }
            map.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
