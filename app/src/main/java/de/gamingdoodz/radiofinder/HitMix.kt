package de.gamingdoodz.radiofinder

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Bei Playlists: nur die Playlist-Lieder (haben Vorrang vor dem Ersatz-Genre). */
    private var primary: List<LiveIndex.Song> = emptyList()
    private var isPlaylist = false
    private var stations: List<Station> = emptyList()
    private var currentStationId: String? = null
    private var currentSongKey: String? = null
    private var songStartedAt = 0L
    /** Zuletzt gespielte Lieder (unveränderliche Liste: wird aus mehreren Threads gelesen). */
    @Volatile private var played: List<String> = emptyList()
    private fun addPlayed(key: String) { played = (played + key).takeLast(25) }

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
    /** Nur die eigene Senderliste verwenden (Lieblingssender-Mix oder ohne Server). */
    private var onlyListed = true

    /** Startet den Hit-Mix und gibt den ersten Sender zurück (oder null, wenn gerade nichts passt). */
    suspend fun start(genreKey: String?): MediaItem? {
        stop()
        // "artist:<id>:<Name>" = nur Lieder eines Künstlers
        val artist = genreKey?.takeIf { it.startsWith("artist:") }?.split(":", limit = 3)
        val plKey = genreKey?.takeIf { it.startsWith("pl:") || it.startsWith("my:") }
        // Playlists: passendes Genre als Ersatz, wenn gerade kein Playlist-Lied läuft
        val genre = Genres.all.firstOrNull { it.key == genreKey }
            ?: plKey?.let { k -> Playlists.genreFor(k.substring(3), Playlists.nameFor(k.substring(3))) }?.let { g -> Genres.all.firstOrNull { it.key == g } }
        val favMix = genreKey == "favs"
        val chartsMix = genreKey == "radiocharts"
        val similarTo = genreKey?.takeIf { it.startsWith("similar:") }?.removePrefix("similar:")
        val playlist = genreKey?.takeIf { it.startsWith("pl:") }?.removePrefix("pl:")
        val myList = genreKey?.takeIf { it.startsWith("my:") }?.removePrefix("my:")
        val name = when {
            genreKey == "mine" -> "Mein Mix"
            favMix -> "Lieblingssender"
            chartsMix -> "Meistgespielt im Radio"
            playlist == "community" -> "Beliebt bei Hitradar-Hörern"
            playlist != null -> Playlists.nameFor(playlist)
            myList != null -> myList
            similarTo != null -> "Mehr wie „${similarTo.substringAfter(" - ").substringAfter(" – ").take(24)}“"
            else -> artist?.getOrNull(2) ?: genre?.name ?: "Deutsche Charts"
        }
        Radar.fast = true
        scope.launch { Radar.charts() }
        // Nur feste Schlüssel zählen (keine eigenen Playlist-Namen)
        (genre?.key ?: playlist?.takeIf { p -> Playlists.curated.any { it.second == p } }?.let { "pl:$it" }
            ?: genreKey?.takeIf { it == "radiocharts" || it == "mine" } ?: if (genreKey == null) "charts" else null)
            ?.let { Radar.event("mix", it) }
        prefs(context).edit().putString("name", name).putString("now", "Suche Hits…").apply()

        val hits = when {
            genreKey == "mine" -> mySongs()
            playlist == "rising" || playlist == "radiocharts" ->
                (if (playlist == "rising") Radar.rising() else Radar.charts().map { it.first }).map { t ->
                    val a = t.substringBefore(" - ").substringBefore(" – ").trim()
                    val ti = t.substringAfter(" - ").substringAfter(" – ").trim()
                    Entry("cm:" + t.hashCode(), Kind.TRACK, ti, a, "", "", ti)
                }.ifEmpty { Deezer.topGermany(100) }
            playlist == "community" -> Radar.popular()?.songs.orEmpty().map { t ->
                val a = t.substringBefore(" - ").substringBefore(" – ").trim()
                val ti = t.substringAfter(" - ").substringAfter(" – ").trim()
                Entry("cm:" + t.hashCode(), Kind.TRACK, ti, a, "", "", ti)
            }.ifEmpty { Deezer.topGermany(100) }
            playlist != null -> Deezer.playlistByName(playlist) + genreHits(genre)
            myList != null -> Playlists.get(context, myList) + genreHits(genre)
            similarTo != null -> {
                // Ähnliche Lieder vom Server + weitere Lieder desselben Künstlers
                val artist = similarTo.substringBefore(" - ").substringBefore(" – ").trim()
                val sim = Radar.similar(similarTo).map { t ->
                    val a = t.substringBefore(" - ").substringBefore(" – ").trim()
                    val ti = t.substringAfter(" - ").substringAfter(" – ").trim()
                    Entry("sm:" + t.hashCode(), Kind.TRACK, ti, a, "", "", ti)
                }
                val own = Deezer.artist(artist)?.let { Deezer.artistTop(it.id).take(10) }.orEmpty()
                (sim + own).ifEmpty { Deezer.topGermany(100) }
            }
            chartsMix -> Radar.charts().take(60).map { (t, _) ->
                val artist = t.substringBefore(" - ").substringBefore(" – ").trim()
                val title = t.substringAfter(" - ").substringAfter(" – ").trim()
                Entry("rc:" + t.hashCode(), Kind.TRACK, title, artist, "", "", title)
            }.ifEmpty { Deezer.topGermany(100) }
            artist != null -> Deezer.artistTop(artist.getOrNull(1)?.toLongOrNull() ?: 0L)
            genre?.key == "kids" -> Deezer.kidsHits()
            genre?.playlist != null -> Deezer.playlistByName(genre.playlist)
            genre?.deezerGenre != null && genre.deezerGenre != 0 -> Deezer.genreChart(genre.deezerGenre, 50) + Deezer.topGermany(50)
            else -> Deezer.topGermany(100)
        }
        songs = hits.distinctBy { it.id }.map { LiveIndex.Song(it) }.filter { it.key.isNotBlank() }
        isPlaylist = playlist != null || myList != null
        primary = if (isPlaylist) {
            val own = (if (myList != null) Playlists.get(context, myList) else Deezer.playlistByName(playlist ?: "")).map { it.id }.toHashSet()
            songs.filter { it.entry.id in own }.ifEmpty { songs }
        } else songs

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
        themeWords = if (genre?.key == "kids")
            Regex("(?i)kinderlied|zuckowski|sommerland|deine freunde|volker rosin|jöcker|lichterkinder|bibi blocksberg|bibi und tina|benjamin blümchen|schlümpfe|biene maja|pippi|kikaninchen|sternschnuppe|frederik vahle|paw patrol|sandmännchen|die maus|kinderdisco")
        else if (genre?.key == "xmas")
            Regex("(?i)christmas|xmas|weihnacht|santa claus|jingle bell|noel|navidad|rudolph|sleigh|mistletoe|let it snow|snowman|winter wonderland|silent night|stille nacht|tannenbaum|advent|nikolaus|lebkuchen|schneeflöckchen|leise rieselt|kling glöckchen|feliz navidad|little drummer|frosty")
        else null

        // Mit Server: alle ~30.000 laufenden Sender kommen infrage (kein Laden langer Senderlisten nötig).
        // Ohne Server: eine Auswahl, die die App selbst abfragen kann.
        Radar.sync()
        onlyListed = favMix || !Radar.ok
        stations = when {
            favMix -> favStations
            Radar.ok -> genreStations
            else -> {
                val base = try { StationRepository.load(context, big = false) } catch (_: Exception) { emptyList() }
                val extra = genreStations + StationRepository.byTag("charts")
                val wifi = Net.isUnmetered(context)
                val big = if (wifi) (try { StationRepository.load(context, big = true) } catch (_: Exception) { emptyList() }) else emptyList()
                (extra + base + big).distinctBy { it.id }.take(if (wifi) 700 else 220)
            }
        }

        poll(force = true)
        val first = withContext(Dispatchers.Default) { pickNext() }
        if (first != null) { switchedAt = System.currentTimeMillis(); confirmed = false }
        if (first == null) prefs(context).edit().putString("now", "Gerade läuft kein passender Hit – ich warte auf den nächsten").apply()
        job = scope.launch { delay(3_000); refreshQueue(); loop() }
        return first?.let { prepareItem(it) }
    }

    /** Hits eines Genres (für Playlists als Ersatz hinten angehängt). */
    private suspend fun genreHits(g: Genre?): List<Entry> = when {
        g == null || g.key == "charts" -> Deezer.topGermany(100)
        g.key == "kids" -> Deezer.kidsHits()
        g.playlist != null -> Deezer.playlistByName(g.playlist)
        g.deezerGenre != null && g.deezerGenre != 0 -> Deezer.genreChart(g.deezerGenre, 50)
        else -> Deezer.topGermany(100)
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
        // Was Sender oft zusammen mit deinen meistgehörten Liedern spielen
        val similar = Heard.all(context).take(3).flatMap { h -> Radar.similar(h.title).take(10) }.map { t ->
            val a = t.substringBefore(" - ").substringBefore(" – ").trim()
            val ti = t.substringAfter(" - ").substringAfter(" – ").trim()
            Entry("sm:" + t.hashCode(), Kind.TRACK, ti, a, "", "", ti)
        }
        val all = heard + favTracks + artistSongs + similar
        return if (all.size < 5) Deezer.topGermany(100) + all else all
    }

    fun stop() {
        Radar.fast = false
        val wasActive = job?.isActive == true
        job?.cancel()
        job = null
        // Eingereihte "Als Nächstes"-Sender wieder entfernen
        if (wasActive) try {
            player()?.let { p -> if (p.mediaItemCount > p.currentMediaItemIndex + 1) p.removeMediaItems(p.currentMediaItemIndex + 1, p.mediaItemCount) }
        } catch (_: Exception) {}
        queued = emptyMap()
        currentStationId = null
        currentSongKey = null
        prefs(context).edit().remove("name").remove("now").apply()
    }

    private var waitingForNext = false

    /** Sofort zum nächsten Hit springen. Gibt false zurück, wenn wirklich nichts Passendes läuft. */
    suspend fun next(): Boolean {
        // Sofort: vorbereiteten Kandidaten nehmen bzw. aus dem vorhandenen Stand wählen (kein Warten aufs Netz)
        val item = withContext(Dispatchers.Default) {
            takeCandidate() ?: pickNext() ?: pickNext(allowRepeats = true) ?: pickNext(allowRepeats = true, anyMusic = true)
        } ?: run {
            // Gar nichts im Speicher: dann doch kurz nachsehen
            if (!Radar.sync(force = true)) {
                val priority = (stations.filter { it.id in genreIds } + stations).distinctBy { it.id }.take(40)
                withTimeoutOrNull(4_000) { LiveIndex.refresh(priority, maxAgeMs = 10_000L) }
            }
            withContext(Dispatchers.Default) { pickNext(allowRepeats = true) ?: pickNext(allowRepeats = true, anyMusic = true) }
        }
        // Im Hintergrund frische Daten für den nächsten Sprung
        scope.launch { Radar.sync(force = true) }
        if (item == null) {
            waitingForNext = true
            prefs(context).edit().putString("now", "Gerade läuft kein anderer Hit – ich springe, sobald einer anfängt").apply()
            return false
        }
        waitingForNext = false
        switchTo(item)
        return true
    }

    /** Zeitpunkt des letzten Umschaltens und ob der erwartete Titel im Stream bestätigt wurde. */
    private var switchedAt = 0L
    private var confirmed = false
    private var lastLocal = 0L
    private var candidate: Pair<Long, Choice>? = null

    /** Titel aus dem Stream des laufenden Senders (kommt genau dann, wenn er sich ändert). */
    suspend fun onStreamTitle(stationId: String, title: String, music: Boolean) {
        if (stationId != currentStationId || job?.isActive != true) return
        val now = System.currentTimeMillis()
        lastLocal = now
        val norm = Scanner.normalize(title)
        val sinceSwitch = now - switchedAt
        if (music && (switchedTitle == null || norm == switchedTitle)) {
            // Läuft wie erwartet
            switchedTitle = norm; confirmed = true; waitingForNext = false
            return
        }
        if (music) {
            val fresh = !confirmed && sinceSwitch < 25_000   // erster Titel nach dem Umschalten
            if (isPlaylist && !primary.any { it.matches(norm) }) {
                // Kein Playlist-Lied: fängt woanders gerade eins aus der Playlist an? Dann dorthin.
                val alt = withContext(Dispatchers.Default) { ranked(limit = 1, onlyPrimary = true).firstOrNull() }
                if (alt != null) { waitingForNext = false; switchTo(commit(alt)); return }
            }
            if (fitsTitle(norm, stationId) && norm !in played) {
                // Neues passendes Lied auf demselben Sender: einfach weiterhören
                switchedTitle = norm; confirmed = true; waitingForNext = false
                if (!fresh) songStartedAt = now
                currentSongKey = norm
                addPlayed(norm)
                prefs(context).edit().putString("now", Text.clean(title)).apply()
                return
            }
            switchNow()
            return
        }
        // Keine Musik mehr (Moderation, Jingle, Werbung): direkt weiter.
        // Kurz nach dem Umschalten nicht – da kommt oft erst der Sendername oder Vorab-Werbung.
        if (confirmed || sinceSwitch > 25_000) switchNow()
    }

    private suspend fun switchNow() {
        val next = withContext(Dispatchers.Default) { takeCandidate() ?: pickNext() }
        if (next != null) { waitingForNext = false; switchTo(next) }
        else waitingForNext = true
    }

    private suspend fun loop() {
        var tick = 0
        while (scope.isActive && job?.isActive != false) {
            delay(3_000)
            tick++
            val p = player() ?: continue
            if (!p.playWhenReady) continue
            val broken = p.playbackState == Player.STATE_IDLE || p.playerError != null
            // Länge bekannt und überschritten, Titel aber unverändert: Lied ist vorbei (Moderator redet o. Ä.)
            val overrun = NowSong.stationId == currentStationId && NowSong.overrun() &&
                NowSong.title?.let { Scanner.normalize(it) } == switchedTitle
            if ((broken || overrun) && (!waitingForNext || tick % 3 == 0)) { switchNow(); continue }
            if (waitingForNext && tick % 3 == 0) { switchNow(); continue }
            if (tick % 7 != 0) continue   // ca. alle 20 Sekunden: Server-Stand + nächsten Kandidaten vorbereiten
            poll(force = false)
            // Liedlängen der frisch angefangenen Lieder holen (damit fast vorbei nicht gewählt wird)
            LiveIndex.fresh().filter { LiveIndex.isMusic(it.title) && System.currentTimeMillis() - it.since < 150_000 && !Covers.known(it.title) }
                .sortedByDescending { it.since }.take(12).forEach { n -> scope.launch { Covers.load(n.title) } }
            candidate = withContext(Dispatchers.Default) { choose() }?.let { System.currentTimeMillis() to it }
            refreshQueue()
            // Ohne Titel im Stream (z. B. beim Casten oder Sender ohne Titel): Server-Stand entscheidet
            val local = !Cast.active && System.currentTimeMillis() - lastLocal < 10 * 60_000L
            if (local) continue
            val now = currentStationId?.let { LiveIndex.get(it) } ?: continue
            val stillHit = (switchedTitle == null || now.normalized == switchedTitle) && LiveIndex.isMusic(now.title)
            val tooLong = NowSong.durMs == 0L && System.currentTimeMillis() - songStartedAt > 9 * 60_000L
            if (!stillHit || tooLong) {
                if (LiveIndex.isMusic(now.title) && fitsTitle(now.normalized, now.station.id) && now.normalized !in played) {
                    switchedTitle = now.normalized; songStartedAt = now.since
                    addPlayed(now.normalized)
                } else switchNow()
            }
        }
    }

    /** Vorbereiteten Kandidaten nehmen, wenn er noch stimmt (läuft noch dasselbe Lied, nicht zu alt). */
    private fun takeCandidate(): MediaItem? {
        val (at, c) = candidate ?: return null
        candidate = null
        if (System.currentTimeMillis() - at > 60_000L) return null
        val cur = LiveIndex.get(c.now.station.id) ?: return null
        if (cur.normalized != c.now.normalized || c.now.station.id == currentStationId) return null
        if (c.key in played) return null
        return commit(c)
    }

    /** Passt dieses Lied zum Mix? (Liste, Genre-Sender, Thema oder bekannter Hit bei offenen Mixen) */
    private fun fitsTitle(norm: String, stationId: String): Boolean =
        songs.any { it.matches(norm) } || stationId in genreIds || themeWords?.containsMatchIn(norm) == true ||
            (!strict && genreIds.isEmpty() && (Radar.chartPlays[norm] ?: 0) >= 15)

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
    class Choice(val now: LiveIndex.Now, val key: String)

    private fun pickNext(allowRepeats: Boolean = false, anyMusic: Boolean = false): MediaItem? =
        choose(allowRepeats, anyMusic)?.let { commit(it) }

    private fun commit(c: Choice): MediaItem {
        val n = c.now
        currentStationId = n.station.id
        currentSongKey = c.key
        switchedTitle = n.normalized
        songStartedAt = n.since
        addPlayed(c.key)
        prefs(context).edit().putString("now", "${n.title} · ${n.station.name}").apply()
        return n.station.toEntry(n.title).toMediaItem()
    }

    private fun choose(allowRepeats: Boolean = false, anyMusic: Boolean = false): Choice? =
        ranked(allowRepeats, anyMusic, 1).firstOrNull()

    /** Die besten Kandidaten der Reihe nach (für "Als Nächstes" in der Wiedergabeliste). */
    private fun ranked(allowRepeats: Boolean = false, anyMusic: Boolean = false, limit: Int = 6, onlyPrimary: Boolean = false): List<Choice> {
        val t = System.currentTimeMillis()
        val ids = if (onlyListed) stations.map { it.id }.toHashSet() else null
        val currentTitle = currentStationId?.let { LiveIndex.get(it) }?.normalized
        val pool = LiveIndex.fresh().filter {
            (ids == null || it.station.id in ids) && it.station.id != currentStationId && it.normalized != currentTitle &&
                LiveIndex.isMusic(it.title) && !LiveIndex.endingSoon(it, t)
        }
        fun ok(key: String) = (allowRepeats || key !in played) && key != currentSongKey

        fun theme(n: LiveIndex.Now) = themeWords?.containsMatchIn(n.title) == true
        // Schnell über den Wort-Index statt jedes Lied mit jedem laufenden Titel zu vergleichen
        val poolIds = pool.mapTo(HashSet()) { it.station.id }
        fun liveOf(l: List<LiveIndex.Song>) = l.flatMap { sg -> LiveIndex.matching(sg).filter { it.station.id in poolIds }.map { it to sg.key } }
            .distinctBy { it.first.station.id }
            .filter { (_, k) -> ok(k) }
        // Playlist: zuerst ihre eigenen Lieder, dann das Ersatz-Genre
        val fromPrimary = if (isPlaylist) liveOf(primary) else emptyList()
        if (onlyPrimary) return fromPrimary.filter { (n, _) -> !Ads.likely(context, n.station.id, n.station.url) }
            .sortedBy { (n, _) -> t - n.since }.take(limit).map { Choice(it.first, it.second) }
        val fromList = liveOf(songs)
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
        val tiers = listOf(fromPrimary, fromList, fromGenre, fromArtist, fromAny)
        val list = (tiers.map { noAds(it) } + tiers).firstOrNull { it.isNotEmpty() } ?: return emptyList()
        return list.sortedBy { (n, key) ->
                // Bekannte Lieder deutlich bevorzugen: läuft auf vielen Sendern, oft gespielt heute, weit oben in der Liste
                val many = ((pop[n.normalized] ?: 1) - 1).coerceAtMost(6) * 60_000L
                val played = (Radar.chartPlays[n.normalized] ?: 0).coerceAtMost(200) * 1_500L
                val top = rank[key]?.let { (100 - it).coerceAtLeast(0) * 2_000L } ?: 0L
                val knownArtist = if (artistWords.any { w -> w.all { n.normalized.contains(it) } }) 90_000L else 0L
                // Frisch angefangen zählt; fast vorbei (über 3 Min.) und Sender mit Vorab-Werbung rutschen nach hinten
                val age = t - n.since
                // Beim Casten: Sender mit https direkt nehmen, spart die Weiterleitung über den Server
                val relay = if (Cast.active && !Cast.isHttps(n.station.url)) 45_000L else 0L
                age + relay + (if (age > 180_000L) 300_000L else 0L) +
                    (if (Ads.duration(context, n.station.id) > 0) 120_000L else 0L) - many - played - top - knownArtist
            }.distinctBy { it.second }.take(limit).map { Choice(it.first, it.second) }
    }

    // ---------- Wiedergabeliste: "Als Nächstes" zeigen, antippen springt hin ----------

    /** Nach dem aktuellen Sender die nächsten passenden Hits einreihen (ohne das laufende Lied zu stören). */
    private var refreshing = false

    private suspend fun refreshQueue() {
        if (refreshing || job?.isActive != true) return
        refreshing = true
        try { refreshQueueNow() } finally { refreshing = false }
    }

    private suspend fun refreshQueueNow() {
        val next = withContext(Dispatchers.Default) { ranked(limit = 5) }
        val p = player() ?: return
        if (p.mediaItemCount == 0) return
        val idx = p.currentMediaItemIndex
        if (p.mediaItemCount > idx + 1) p.removeMediaItems(idx + 1, p.mediaItemCount)
        if (idx > 0) p.removeMediaItems(0, idx)
        queued = next.associateBy { "st:" + it.now.station.id }
        // In der Liste mit Liedtitel und Sender anzeigen
        if (next.isNotEmpty()) p.addMediaItems(next.map { Covers.dress(it.now.station.toEntry(it.now.title)).toMediaItem() })
    }

    private var queued: Map<String, Choice> = emptyMap()

    /** Nutzer hat in der Wiedergabeliste getippt oder "Weiter" im Auto gedrückt: das ist jetzt der laufende Hit. */
    fun onJump(mediaId: String) {
        val c = queued[mediaId] ?: return
        if (c.now.station.id == currentStationId) return
        commit(c)
        switchedAt = System.currentTimeMillis()
        confirmed = false
        scope.launch { delay(1_500); refreshQueue() }
    }

    private fun switchTo(item: MediaItem) {
        switchedAt = System.currentTimeMillis()
        confirmed = false
        val p = player() ?: return
        p.setMediaItem(prepareItem(item))
        scope.launch { delay(1_500); refreshQueue() }
        p.prepare()
        p.play()
    }
}
