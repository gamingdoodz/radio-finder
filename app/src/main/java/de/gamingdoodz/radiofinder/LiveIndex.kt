package de.gamingdoodz.radiofinder

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Merkt sich, was gerade auf welchem Sender läuft (ein paar Minuten lang).
 * Damit kommen Suchergebnisse sofort, und Chart-Songs bekommen "LIVE".
 */
object LiveIndex {
    class Now(val station: Station, val title: String, val time: Long) {
        val normalized: String = Scanner.normalize(title)
    }

    private const val MAX_AGE = 4 * 60_000L
    private val map = ConcurrentHashMap<String, Now>()
    private val refreshLock = Mutex()
    @Volatile var lastRefresh = 0L
        private set

    fun put(s: Station, title: String) {
        map[s.id] = Now(s, title, System.currentTimeMillis())
    }

    fun isFresh(s: Station) = map[s.id]?.let { System.currentTimeMillis() - it.time < MAX_AGE } == true

    fun fresh(): List<Now> {
        val t = System.currentTimeMillis()
        return map.values.filter { t - it.time < MAX_AGE }
    }

    fun find(query: String): List<Now> {
        val words = Scanner.words(query)
        if (words.isEmpty()) return emptyList()
        return fresh().filter { n -> words.all { n.normalized.contains(it) } }
    }

    /** Läuft dieser Song (Titel + Künstler) gerade irgendwo? */
    fun findSong(title: String, artist: String): Now? {
        val clean = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ").substringBefore(" - ")
        val tw = Scanner.words(clean).filter { it.length >= 2 }
        if (tw.isEmpty()) return null
        val aw = Scanner.words(artist).filter { it.length >= 3 }
        return fresh().firstOrNull { n ->
            tw.all { n.normalized.contains(it) } && (aw.isEmpty() || aw.any { n.normalized.contains(it) })
        }
    }

    /** Aktualisiert die Liste für die angegebenen Sender (nur einmal gleichzeitig). */
    suspend fun refresh(stations: List<Station>) {
        if (!refreshLock.tryLock()) return
        try {
            Scanner.titles(stations.filter { !isFresh(it) }) { _, _ -> }
            lastRefresh = System.currentTimeMillis()
        } finally {
            refreshLock.unlock()
        }
    }
}
