package de.gamingdoodz.radiofinder

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hit-Mix: spielt nur aktuelle Hits aus dem Radio.
 * Beobachtet viele Sender. Ist der Hit auf dem aktuellen Sender vorbei,
 * springt er zu einem Sender, auf dem gerade ein Hit angefangen hat.
 */
class HitMix(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> Player?,
    private val prepareItem: (MediaItem) -> MediaItem,
) {
    companion object {
        fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hitmix", Context.MODE_PRIVATE)
        fun activeName(context: Context): String? = prefs(context).getString("name", null)
        fun nowPlaying(context: Context): String? = prefs(context).getString("now", null)
    }

    private var job: Job? = null
    private var songs: List<LiveIndex.Song> = emptyList()
    private var stations: List<Station> = emptyList()
    private var currentStationId: String? = null
    private var currentSongKey: String? = null
    private var songStartedAt = 0L
    private val played = ArrayDeque<String>()

    val isActive get() = job?.isActive == true

    /** Genre-Mix (z. B. Weihnachten): jede Musik auf passenden Sendern zählt, nicht nur eine feste Liste. */
    private var genreIds: Set<String> = emptySet()
    private var artistNames: List<String> = emptyList()
    /** Themen-Mix (Weihnachten, Kinder): nur passende Lieder, keine "ähnlichen" Künstler oder Zufallsmusik. */
    private var strict = false
    /** Wörter, an denen man ein Themen-Lied im Titel erkennt (z. B. "Christmas"). */
    private var themeWords: Regex? = null
    /** Titel, der beim Umschalten lief – ändert er sich, ist das Lied vorbei. */
    private var switchedTitle: String? = null

    /** Startet den Hit-Mix und gibt den ersten Sender zurück (oder null, wenn gerade nichts passt). */
    suspend fun start(genreKey: String?): MediaItem? {
        stop()
        // "artist:<id>:<Name>" = nur Lieder eines Künstlers
        val artist = genreKey?.takeIf { it.startsWith("artist:") }?.split(":", limit = 3)
        val genre = Genres.all.firstOrNull { it.key == genreKey }
        val favMix = genreKey == "favs"
        val name = when {
            genreKey == "mine" -> "Mein Mix"
            favMix -> "Lieblingssender"
            else -> artist?.getOrNull(2) ?: genre?.name ?: "Deutsche Charts"
        }
        Radar.fast = true
        prefs(context).edit().putString("name", name).putString("now", "Suche Hits…").apply()

        val hits = when {
            genreKey == "mine" -> mySongs()
            artist != null -> Deezer.artistTop(artist.getOrNull(1)?.toLongOrNull() ?: 0L)
            genre?.key == "kids" -> Deezer.kidsHits()
            genre?.playlist != null -> Deezer.playlistByName(genre.playlist)
            genre?.deezerGenre != null && genre.deezerGenre != 0 -> Deezer.genreChart(genre.deezerGenre, 50) + Deezer.topGermany(50)
            else -> Deezer.topGermany(100)
        }
        songs = hits.distinctBy { it.id }.map { LiveIndex.Song(it) }.filter { it.key.isNotBlank() }

        // Künstler aus der Hitliste: für "ähnliche" Treffer, wenn gerade kein Listen-Song läuft
        artistNames = hits.map { it.subtitle.split(Regex("[,&]| feat| x ")).first().trim() }
            .filter { it.length >= 3 }.distinct()

        // Passende Sender zum Genre (bei Weihnachten auch deutsche Weihnachtssender)
        // Lieblingssender-Mix: nur die eigenen Favoriten-Sender
        val favStations = if (favMix) Favorites.all(context).filter { it.kind == Kind.STATION }
            .map { Station(it.id.removePrefix("st:"), it.name, it.url, it.artwork, false) } else emptyList()
        val genreStations = if (favMix) favStations else when (genre?.key) {
            null, "charts" -> emptyList()
            "kids" -> StationRepository.kids(context)
            "xmas" -> StationRepository.byTag("christmas") + StationRepository.byTag("weihnachten") + StationRepository.byTag("weihnachtsmusik")
            else -> StationRepository.byTag(genre.radioTag)
        }
        genreIds = genreStations.map { it.id }.toSet()
        strict = genre?.key == "xmas" || genre?.key == "kids"
        themeWords = if (genre?.key == "xmas")
            Regex("(?i)christmas|xmas|weihnacht|santa|jingle|snow|winter|noel|navidad|feliz|rudolph|sleigh|mistletoe|let it snow|silent night|stille nacht|o tannenbaum|advent|nikolaus|lebkuchen|schnee")
        else null

        val base = try { StationRepository.load(context, big = false) } catch (_: Exception) { emptyList() }
        val extra = genreStations + StationRepository.byTag("charts")
        val wifi = Net.isUnmetered(context)
        val big = if (wifi) (try { StationRepository.load(context, big = true) } catch (_: Exception) { emptyList() }) else emptyList()
        stations = if (favMix) favStations else (extra + base + big).distinctBy { it.id }.take(if (wifi) 700 else 220)

        poll(force = true)
        val first = pickNext()
        if (first == null) prefs(context).edit().putString("now", "Gerade läuft kein passender Hit – ich warte auf den nächsten").apply()
        job = scope.launch { loop() }
        return first?.let { prepareItem(it) }
    }

    /**
     * "Mein Mix": Lieder, die du im Radio gehört oder als Favorit hast,
     * dazu die Top-Lieder deiner meistgehörten Künstler.
     */
    private suspend fun mySongs(): List<Entry> {
        val heard = Heard.all(context).map { h ->
            val artist = h.title.substringBefore(" - ").substringBefore(" – ").trim()
            val title = h.title.substringAfter(" - ").substringAfter(" – ").trim()
            Entry("hd:" + h.title.hashCode(), Kind.TRACK, title, artist, "", "", title)
        }
        val favTracks = (Favorites.all(context) + Recent.all(context)).filter { it.kind == Kind.TRACK }
        val topArtists = heard.groupingBy { it.subtitle.lowercase() }.eachCount()
            .entries.sortedByDescending { it.value }.take(4).map { it.key }
        val artistSongs = topArtists.flatMap { a -> Deezer.artist(a)?.let { Deezer.artistTop(it.id).take(15) }.orEmpty() }
        val all = heard + favTracks + artistSongs
        return if (all.size < 5) Deezer.topGermany(100) + all else all
    }

    fun stop() {
        Radar.fast = false
        job?.cancel()
        job = null
        currentStationId = null
        currentSongKey = null
        prefs(context).edit().remove("name").remove("now").apply()
    }

    private var waitingForNext = false

    /** Sofort zum nächsten Hit springen. Gibt false zurück, wenn wirklich nichts Passendes läuft. */
    suspend fun next(): Boolean {
        // Beim Drücken auf "Weiter" die wichtigsten Sender sofort frisch abfragen
        val priority = (stations.filter { it.id in genreIds } + stations).distinctBy { it.id }.take(80)
        LiveIndex.refresh(priority, maxAgeMs = 10_000L)
        val item = pickNext() ?: pickNext(allowRepeats = true) ?: pickNext(allowRepeats = true, anyMusic = true)
        if (item == null) {
            waitingForNext = true
            prefs(context).edit().putString("now", "Gerade läuft kein anderer Hit – ich springe, sobald einer anfängt").apply()
            return false
        }
        waitingForNext = false
        switchTo(item)
        return true
    }

    private suspend fun loop() {
        while (scope.isActive && job?.isActive != false) {
            delay(if (waitingForNext) 10_000 else 20_000)
            val p = player() ?: continue
            // Pausiert: nichts tun
            if (!p.playWhenReady) continue
            poll(force = false)
            val now = currentStationId?.let { LiveIndex.get(it) }
            // Läuft noch dasselbe Lied wie beim Umschalten? Dann bleiben.
            val stillHit = now != null && (switchedTitle == null || now.normalized == switchedTitle) &&
                LiveIndex.isMusic(now.title)
            val broken = p.playbackState == Player.STATE_IDLE || p.playerError != null
            // Hit vorbei (anderes Lied, Werbung, Moderation) oder Sender kaputt -> weiter
            if (waitingForNext || !stillHit || broken || System.currentTimeMillis() - songStartedAt > 7 * 60_000L) {
                val next = pickNext() ?: if (broken || waitingForNext) pickNext(allowRepeats = true) else null
                if (next != null) {
                    waitingForNext = false
                    switchTo(next)
                } else if (now != null && LiveIndex.isMusic(now.title) && fits(now)) {
                    // Nichts Neues gefunden, aber hier läuft wieder ein passendes Lied: bleiben
                    switchedTitle = now.normalized
                    songStartedAt = now.since
                    waitingForNext = false
                } else if (!broken) {
                    // Hier läuft gerade nichts Passendes: sobald woanders eins anfängt, springen
                    waitingForNext = true
                }
            }
        }
    }

    private var rotation = 0

    private suspend fun poll(force: Boolean) {
        val wifi = Net.isUnmetered(context)
        if (force || wifi) {
            LiveIndex.refresh(stations, maxAgeMs = if (force) 0L else 15_000L)
            return
        }
        // Mobil: Statusseiten (fast keine Daten) + jedes Mal ein paar Sender direkt, reihum
        LiveIndex.refresh(stations, maxAgeMs = 15_000L, statusOnly = true)
        // Genre-Sender (z. B. Weihnachtssender) häufiger anschauen
        val genre = stations.filter { it.id in genreIds }.take(25)
        if (genre.isNotEmpty()) LiveIndex.refresh(genre, maxAgeMs = 40_000L)
        val batch = if (Settings.saver(context)) 15 else 30
        if (stations.isNotEmpty()) {
            val from = rotation % stations.size
            val part = (stations.drop(from) + stations.take(from)).take(batch)
            rotation += batch
            LiveIndex.refresh(part, maxAgeMs = 15_000L)
        }
    }

    /** Sucht einen Sender, auf dem gerade ein Hit läuft – am liebsten einen, der eben erst angefangen hat. */
    /**
     * Sucht den nächsten Sender, am liebsten einen, auf dem der Song eben erst angefangen hat:
     * 1. ein Song aus der Hitliste, 2. Musik auf einem Genre-Sender,
     * 3. ein Song von einem Künstler aus der Liste, 4. (nur bei "Weiter") irgendeine Musik.
     */
    private fun pickNext(allowRepeats: Boolean = false, anyMusic: Boolean = false): MediaItem? {
        val t = System.currentTimeMillis()
        val ids = stations.map { it.id }.toHashSet()
        val currentTitle = currentStationId?.let { LiveIndex.get(it) }?.normalized
        val pool = LiveIndex.fresh().filter {
            it.station.id in ids && it.station.id != currentStationId && it.normalized != currentTitle && LiveIndex.isMusic(it.title)
        }
        fun ok(key: String) = (allowRepeats || key !in played) && key != currentSongKey

        fun theme(n: LiveIndex.Now) = themeWords?.containsMatchIn(n.title) == true
        val fromList = pool.mapNotNull { n -> songs.firstOrNull { it.matches(n.normalized) }?.let { n to it.key } }
            .filter { (_, k) -> ok(k) }
        val fromGenre = pool.filter { it.station.id in genreIds || theme(it) }.map { it to it.normalized }.filter { (_, k) -> ok(k) }
        val artistWords = artistNames.map { Scanner.words(it) }.filter { it.isNotEmpty() }
        val fromArtist = if (strict) emptyList() else pool.filter { n -> artistWords.any { w -> w.all { n.normalized.contains(it) } } }
            .map { it to it.normalized }.filter { (_, k) -> ok(k) }
        val fromAny = if (anyMusic && !strict) pool.map { it to it.normalized }.filter { (_, k) -> ok(k) } else emptyList()

        // Bekanntheit: läuft auf vielen Sendern, steht weit oben in der Liste oder ist von einem bekannten Künstler
        val pop = LiveIndex.popularity()
        val rank = HashMap<String, Int>().also { m -> songs.forEachIndexed { i, sg -> m.putIfAbsent(sg.key, i) } }
        // Sender, bei denen beim Umschalten Werbung kommt, nur nehmen, wenn es gar nicht anders geht
        fun noAds(l: List<Pair<LiveIndex.Now, String>>) = l.filter { (n, _) -> !Ads.likely(context, n.station.id, n.station.url) }
        val tiers = listOf(fromList, fromGenre, fromArtist, fromAny)
        val best = (tiers.map { noAds(it) } + tiers).firstOrNull { it.isNotEmpty() }
            ?.minByOrNull { (n, key) ->
                val many = ((pop[n.normalized] ?: 1) - 1).coerceAtMost(6) * 45_000L
                val top = rank[key]?.let { (100 - it).coerceAtLeast(0) * 1_500L } ?: 0L
                val knownArtist = if (artistWords.any { w -> w.all { n.normalized.contains(it) } }) 60_000L else 0L
                // Frisch angefangen zählt, Sender mit Vorab-Werbung rutschen nach hinten
                (t - n.since) + (if (Ads.duration(context, n.station.id) > 0) 120_000L else 0L) - many - top - knownArtist
            } ?: return null
        val (n, key) = best
        currentStationId = n.station.id
        currentSongKey = key
        switchedTitle = n.normalized
        songStartedAt = n.since
        played.addLast(key)
        while (played.size > 25) played.removeFirst()
        prefs(context).edit().putString("now", "${n.title} · ${n.station.name}").apply()
        return n.station.toEntry(n.title).toMediaItem()
    }

    /** Passt dieses Lied zum Mix? */
    private fun fits(n: LiveIndex.Now): Boolean =
        songs.any { it.matches(n.normalized) } || n.station.id in genreIds ||
            themeWords?.containsMatchIn(n.title) == true

    private fun switchTo(item: MediaItem) {
        val p = player() ?: return
        p.setMediaItem(prepareItem(item))
        p.prepare()
        p.play()
    }
}
