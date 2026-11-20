package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Fragt viele Sender gleichzeitig ab und sucht das gewünschte Lied. */
object Scanner {
    data class Hit(val station: Station, val title: String)

    /** Treffer der letzten Suche, z. B. für die Liste in Android Auto. */
    @Volatile var lastHits: List<Hit> = emptyList()

    private class FoundException : Exception()

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
        val done = AtomicInteger()
        val sem = Semaphore(40)
        val total = stations.size
        try {
            coroutineScope {
                for (s in stations) {
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            val title = if (s.hls) null else IcyReader.fetchTitle(s.url)
                            if (title != null && matches(title, words)) {
                                val hit = Hit(s, title)
                                hits.add(hit)
                                onHit(hit)
                                if (stopAfterFirst) throw FoundException()
                            }
                        }
                        onProgress(done.incrementAndGet(), total)
                    }
                }
            }
        } catch (_: FoundException) {
            // gefunden, Rest abgebrochen
        }
        return synchronized(hits) { hits.toList() }
    }
}
