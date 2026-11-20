package de.gamingdoodz.radiofinder

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Merkt sich, was gerade auf welchem Sender läuft (ein paar Minuten lang).
 * Damit kommen Suchergebnisse sofort, und Chart-Songs bekommen "LIVE".
 */
object LiveIndex {
    /** time = zuletzt gesehen, since = seit wann dieser Titel auf dem Sender läuft. */
    class Now(val station: Station, val title: String, val time: Long, val since: Long) {
        val normalized: String = Scanner.normalize(title)
    }

    /** Ein Song zum Wiedererkennen im Radio (Titel- und Künstlerwörter). */
    class Song(val entry: Entry) {
        private val titleWords = Scanner.words(
            entry.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ").substringBefore(" - ")
        ).filter { it.length >= 2 }
        private val artistWords = Scanner.words(entry.subtitle).filter { it.length >= 3 }
        val key: String = titleWords.joinToString(" ")

        fun matches(normalizedRadioTitle: String): Boolean =
            titleWords.isNotEmpty() && titleWords.all { normalizedRadioTitle.contains(it) } &&
                (artistWords.isEmpty() || artistWords.any { normalizedRadioTitle.contains(it) })
    }

    private const val MAX_AGE = 4 * 60_000L
    private val map = ConcurrentHashMap<String, Now>()
    private val refreshLock = Mutex()
    @Volatile var lastRefresh = 0L
        private set

    fun put(s: Station, title: String) {
        val t = System.currentTimeMillis()
        val old = map[s.id]
        val since = if (old != null && old.title == title && t - old.time < MAX_AGE) old.since else t
        map[s.id] = Now(s, title, t, since)
    }

    private val talkWords = Regex("(?i)nachrichten|news|verkehr|wetter|werbung|podcast|interview|magazin|sendung|moderation|gewinnspiel")

    /** Ein Liedtitel sieht meist so aus: "Künstler - Titel". Sendungen, Nachrichten, Werbung nicht. */
    fun isMusic(title: String) =
        (title.contains(" - ") || title.contains(" – ")) && !talkWords.containsMatchIn(title)

    fun get(stationId: String): Now? = map[stationId]?.takeIf { System.currentTimeMillis() - it.time < MAX_AGE }

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
        val song = Song(Entry("", Kind.TRACK, title, artist, "", "", title))
        return fresh().firstOrNull { song.matches(it.normalized) }
    }

    /** Aktualisiert die Liste für die angegebenen Sender (nur einmal gleichzeitig). */
    suspend fun refresh(stations: List<Station>, maxAgeMs: Long = MAX_AGE, statusOnly: Boolean = false) {
        if (!refreshLock.tryLock()) return
        try {
            val t = System.currentTimeMillis()
            val due = stations.filter { s -> map[s.id]?.let { t - it.time >= maxAgeMs } ?: true }
            Scanner.titles(due, statusOnly = statusOnly) { _, _ -> }
            lastRefresh = System.currentTimeMillis()
        } finally {
            refreshLock.unlock()
        }
    }
}
