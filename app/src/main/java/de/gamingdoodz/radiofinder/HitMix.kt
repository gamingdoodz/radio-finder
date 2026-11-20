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

    /** Startet den Hit-Mix und gibt den ersten Sender zurück (oder null, wenn gerade nichts passt). */
    suspend fun start(genreKey: String?): MediaItem? {
        stop()
        // "artist:<id>:<Name>" = nur Lieder eines Künstlers
        val artist = genreKey?.takeIf { it.startsWith("artist:") }?.split(":", limit = 3)
        val genre = Genres.all.firstOrNull { it.key == genreKey }
        val name = if (genreKey == "mine") "Mein Mix" else artist?.getOrNull(2) ?: genre?.name ?: "Deutsche Charts"
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

        val base = try { StationRepository.load(context, big = false) } catch (_: Exception) { emptyList() }
        val extra = genre?.let { StationRepository.byTag(it.radioTag) }.orEmpty() + StationRepository.byTag("charts")
        val wifi = Net.isUnmetered(context)
        val big = if (wifi) (try { StationRepository.load(context, big = true) } catch (_: Exception) { emptyList() }) else emptyList()
        stations = (extra + base + big).distinctBy { it.id }.take(if (wifi) 700 else 220)

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
        job?.cancel()
        job = null
        currentStationId = null
        currentSongKey = null
        prefs(context).edit().remove("name").remove("now").apply()
    }

    private var waitingForNext = false

    /** Sofort zum nächsten Hit springen. Gibt false zurück, wenn gerade kein anderer Hit läuft. */
    suspend fun next(): Boolean {
        poll(force = false)
        val item = pickNext() ?: pickNext(allowRepeats = true)
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
            val stillHit = now != null && songs.any { it.key == currentSongKey && it.matches(now.normalized) }
            val broken = p.playbackState == Player.STATE_IDLE || p.playerError != null
            // Hit vorbei (anderes Lied, Werbung, Moderation) oder Sender kaputt -> weiter
            if (waitingForNext || !stillHit || broken || System.currentTimeMillis() - songStartedAt > 7 * 60_000L) {
                (pickNext() ?: if (broken || waitingForNext) pickNext(allowRepeats = true) else null)?.let {
                    waitingForNext = false
                    switchTo(it)
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
        val batch = if (Settings.saver(context)) 15 else 30
        if (stations.isNotEmpty()) {
            val from = rotation % stations.size
            val part = (stations.drop(from) + stations.take(from)).take(batch)
            rotation += batch
            LiveIndex.refresh(part, maxAgeMs = 15_000L)
        }
    }

    /** Sucht einen Sender, auf dem gerade ein Hit läuft – am liebsten einen, der eben erst angefangen hat. */
    private fun pickNext(allowRepeats: Boolean = false): MediaItem? {
        val t = System.currentTimeMillis()
        val ids = stations.map { it.id }.toHashSet()
        val candidates = LiveIndex.fresh()
            .filter { it.station.id in ids && it.station.id != currentStationId }
            .mapNotNull { n -> songs.firstOrNull { it.matches(n.normalized) }?.let { n to it } }
            .filter { (_, song) -> allowRepeats || song.key !in played }
            .filter { (_, song) -> song.key != currentSongKey }
        val best = candidates.minByOrNull { (n, _) -> t - n.since } ?: return null
        val (n, song) = best
        currentStationId = n.station.id
        currentSongKey = song.key
        songStartedAt = n.since
        played.addLast(song.key)
        while (played.size > 25) played.removeFirst()
        prefs(context).edit().putString("now", "${n.title} · ${n.station.name}").apply()
        return n.station.toEntry(n.title).toMediaItem()
    }

    private fun switchTo(item: MediaItem) {
        val p = player() ?: return
        p.setMediaItem(prepareItem(item))
        p.prepare()
        p.play()
    }
}
