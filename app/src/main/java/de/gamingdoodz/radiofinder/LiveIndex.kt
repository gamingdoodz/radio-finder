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
        val titleWords = Scanner.words(
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

    fun put(s: Station, raw: String) {
        val title = Text.clean(raw)
        val t = System.currentTimeMillis()
        val old = map[s.id]
        val since = if (old != null && old.title == title && t - old.time < MAX_AGE) old.since else t
        map[s.id] = Now(s, title, t, since)
        freshCache = null
    }

    /** Vom Server: since = seit wann der Titel dort läuft. */
    fun putRemote(s: Station, raw: String, sinceMs: Long, seenMs: Long = 0L) {
        val title = Text.clean(raw)
        val now = System.currentTimeMillis()
        // Zeitpunkt, an dem der Server den Titel zuletzt gesehen hat (nicht wann wir ihn geholt haben)
        val t = if (seenMs in 1..now) seenMs else now
        val old = map[s.id]
        if (old != null && old.time > t) return
        val since = if (old != null && old.title == title && t - old.time < MAX_AGE) minOf(old.since, sinceMs)
        else if (sinceMs in 1..t) sinceMs else t
        map[s.id] = Now(old?.station ?: s, title, t, since)
    }

    private val talkWords = Regex("(?i)nachrichten|news|verkehr|wetter|werbung|podcast|interview|magazin|sendung|moderation|gewinnspiel")

    /** Ein Liedtitel sieht meist so aus: "Künstler - Titel". Sendungen, Nachrichten, Werbung nicht. */
    fun isMusic(title: String) =
        (title.contains(" - ") || title.contains(" – ")) && !talkWords.containsMatchIn(title)

    fun get(stationId: String): Now? = map[stationId]?.takeIf { System.currentTimeMillis() - it.time < MAX_AGE }

    fun isFresh(s: Station) = map[s.id]?.let { System.currentTimeMillis() - it.time < MAX_AGE } == true

    /** Wie viele Sender spielen dieses Lied gerade? Bekannte Hits laufen auf vielen gleichzeitig. */
    /** Nach einem Server-Abgleich: Zwischenspeicher leeren. */
    fun invalidate() { freshCache = null; popCache = null }

    @Volatile private var popCache: Pair<Long, Map<String, Int>>? = null
    fun popularity(): Map<String, Int> {
        val t = System.currentTimeMillis()
        popCache?.let { (at, m) -> if (t - at < 5_000) return m }
        return fresh().groupingBy { it.normalized }.eachCount().also { popCache = t to it }
    }

    @Volatile private var freshCache: Pair<Long, List<Now>>? = null

    /** Alles, was gerade läuft. Wird 2 Sekunden zwischengespeichert (beim Anzeigen sehr oft gebraucht). */
    fun fresh(): List<Now> {
        val t = System.currentTimeMillis()
        freshCache?.let { (at, l) -> if (t - at < 2_000) return l }
        return map.values.filter { t - it.time < MAX_AGE }.also { freshCache = t to it }
    }

    fun find(query: String): List<Now> {
        val words = Scanner.words(query)
        if (words.isEmpty()) return emptyList()
        return fresh().filter { n -> words.all { n.normalized.contains(it) } }
    }

    /** Läuft dieser Song (Titel + Künstler) gerade irgendwo? */
    fun findSong(title: String, artist: String): Now? = findSongs(title, artist).firstOrNull()

    /** Wort -> laufende Titel mit diesem Wort. Damit muss nicht jedes Mal über alle 8.000 Einträge gesucht werden. */
    @Volatile private var wordIndex: Pair<List<Now>, Map<String, List<Now>>>? = null

    private fun index(): Map<String, List<Now>> {
        val list = fresh()
        wordIndex?.let { (l, m) -> if (l === list) return m }
        val m = HashMap<String, MutableList<Now>>()
        for (n in list) for (w in n.normalized.split(' ')) if (w.length >= 2) m.getOrPut(w) { mutableListOf() }.add(n)
        wordIndex = list to m
        return m
    }

    /** Alle laufenden Titel, die zu diesem Song passen (schnell über den Wort-Index). */
    fun matching(song: Song): List<Now> {
        if (song.titleWords.isEmpty()) return emptyList()
        val idx = index()
        val candidates = song.titleWords.map { idx[it].orEmpty() }.minByOrNull { it.size } ?: return emptyList()
        return candidates.filter { song.matches(it.normalized) }
    }

    /** Alle Sender mit diesem Song, zuletzt angefangene zuerst. Länger als 5 Minuten = wohl schon vorbei. */
    fun findSongs(title: String, artist: String): List<Now> {
        val song = Song(Entry("", Kind.TRACK, title, artist, "", "", title))
        val words = Scanner.words(title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ").substringBefore(" - ")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        val idx = index()
        // Nur Titel, die das seltenste Wort enthalten, genauer prüfen
        val candidates = words.map { idx[it].orEmpty() }.minByOrNull { it.size } ?: return emptyList()
        val t = System.currentTimeMillis()
        return candidates.filter { song.matches(it.normalized) && t - it.since < 5 * 60_000L }.sortedByDescending { it.since }
    }

    /** Aktualisiert die Liste für die angegebenen Sender (nur einmal gleichzeitig). */
    suspend fun refresh(stations: List<Station>, maxAgeMs: Long = MAX_AGE, statusOnly: Boolean = false) {
        // Server zuerst. Läuft er, nur noch bei "sofort frisch" (z. B. Weiter-Taste) selbst nachfragen
        Radar.sync()
        if (Radar.ok && maxAgeMs !in 1L..14_999L) { lastRefresh = System.currentTimeMillis(); return }
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
