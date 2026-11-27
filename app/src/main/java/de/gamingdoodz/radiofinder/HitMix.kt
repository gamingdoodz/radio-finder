package de.gamingdoodz.radiofinder

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
    /** Ist der Player gerade wegen Werbung/Ansage stumm? Dann blendet der Mix nicht selbst ein. */
    private val muted: () -> Boolean = { false },
    /** Ziel-Lautstärke beim Einblenden (beim Wecker anfangs leise). */
    private val targetVolume: () -> Float = { 1f },
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
    /** Wörter der Künstlernamen (einmal beim Start berechnet statt bei jeder Suche). */
    private var artistWords: List<List<String>> = emptyList()
    /** Themen-Mix (Weihnachten, Kinder): nur passende Lieder, keine "ähnlichen" Künstler oder Zufallsmusik. */
    private var strict = false
    /** Mix auf eine Zeit ausgelegt (80er, 90er, Oldies …): aktuelle Chart-Hits nicht bevorzugen. */
    private var eraMix = false
    /** Wörter, an denen man ein Themen-Lied im Titel erkennt (z. B. "Christmas"). */
    private var themeWords: Regex? = null
    /** Titel, der beim Umschalten lief – ändert er sich, ist das Lied vorbei. */
    private var switchedTitle: String? = null
    /** Mix mit fester Richtung (Rock, Schlager, Künstler, Playlist …): nie "irgendeine Musik" als Ersatz. */
    private var focused = false
    /** Nur die eigene Senderliste verwenden (Lieblingssender-Mix oder ohne Server). */
    private var onlyListed = true

    /**
     * Zählt jeden Start und jeden Stopp hoch. Alles, was nach einer Wartezeit (Netz, Hintergrund-Suche)
     * weitermacht, prüft damit, ob es noch zum laufenden Mix gehört – sonst würde ein gestoppter
     * oder ersetzter Mix von selbst wieder umschalten (Stopp bleibt Stopp).
     */
    private var gen = 0

    /** Startet den Hit-Mix und gibt den ersten Sender zurück (oder null, wenn gerade nichts passt oder inzwischen gestoppt wurde). */
    suspend fun start(genreKey: String?): MediaItem? {
        stop()
        val myGen = gen
        fun stale() = gen != myGen
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

        // Playlist-Lieder nur einmal holen (vorher zweimal: kam beim zweiten Mal nichts, galt das ganze Genre als Playlist)
        val special = playlist == "rising" || playlist == "radiocharts" || playlist == "community"
        // Lieblingssender-Mix: nur die eigenen Favoriten-Sender
        val favStations = if (favMix) Favorites.all(context).filter { it.kind == Kind.STATION }
            .map { Station(it.id.removePrefix("st:"), it.name, it.url, it.artwork, false) } else emptyList()
        // Alles gleichzeitig laden statt nacheinander (Lieder, Genre-Sender, Server-Abgleich): Start dauert so
        // nur so lange wie die langsamste Abfrage, nicht so lange wie alle zusammen
        val (listSongs, hits, genreStations) = coroutineScope {
        val syncJob = async { Radar.sync() }
        val stationsJob = async { if (favMix) favStations else genreStationsFor(genre) }
        val listSongs: List<Entry> = when {
            special -> emptyList()
            playlist != null -> Deezer.playlistByName(playlist)
            myList != null -> Playlists.get(context, myList)
            else -> emptyList()
        }

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
            playlist != null -> listSongs + genreHits(genre)
            myList != null -> listSongs + genreHits(genre)
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
            // Nur die Charts der Richtung (vorher kamen die deutschen Pop-Charts dazu: Pop im Rock-Mix)
            genre?.deezerGenre != null && genre.deezerGenre != 0 ->
                Deezer.genreChart(genre.deezerGenre, 100).let { if (MixLogic.chartsBelongTo(genre.key)) it + Deezer.topGermany(50) else it }
                    .ifEmpty { if (MixLogic.chartsBelongTo(genre.key)) Deezer.topGermany(100) else emptyList() }
            else -> Deezer.topGermany(100)
        }
        syncJob.await()
        Triple(listSongs, hits, stationsJob.await())
        }
        if (stale()) return null
        val newSongs = hits.distinctBy { it.id }.map { LiveIndex.Song(it) }.filter { it.key.isNotBlank() }
        val newIsPlaylist = playlist != null || myList != null
        val newPrimary = if (newIsPlaylist) {
            val own = listSongs.mapTo(HashSet()) { it.id }
            newSongs.filter { it.entry.id in own }.ifEmpty { newSongs }
        } else newSongs

        // Mit Server: alle laufenden Sender kommen infrage (kein Laden langer Senderlisten nötig).
        // Ohne Server: eine Auswahl, die die App selbst abfragen kann.
        val newStations = when {
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
        // Inzwischen gestoppt oder ein anderer Mix gestartet: nichts übernehmen
        if (stale()) return null

        // Erst jetzt alles auf einmal übernehmen (ein überholter Start überschreibt so nichts mehr)
        songs = newSongs
        isPlaylist = newIsPlaylist
        primary = newPrimary
        // Künstler aus der Hitliste: für "ähnliche" Treffer, wenn gerade kein Listen-Song läuft
        artistNames = hits.map { it.subtitle.split(Regex("[,&]| feat| x ")).first().trim() }
            .filter { it.length >= 3 }.distinct()
        artistWords = artistNames.map { Scanner.words(it) }.filter { it.isNotEmpty() }
        genreIds = genreStations.map { it.id }.toSet()
        strict = genre?.key == "xmas" || genre?.key == "kids"
        focused = strict || MixLogic.focused(genreKey)
        eraMix = genre?.key in setOf("80s", "90s", "2000s", "oldies", "rockclassics", "classic", "jazz") ||
            (newIsPlaylist && Regex("(?i)\\b(19)?[5-9]0(er|s)\\b|oldie|klassiker|classic|retro|kult").containsMatchIn(name))
        themeWords = if (genre?.key == "kids")
            Regex("(?i)kinderlied|kinder lied|zuckowski|sommerland|deine freunde|volker rosin|jöcker|lichterkinder|bibi blocksberg|bibi und tina|benjamin blümchen|schlümpfe|biene maja|pippi|kikaninchen|sternschnuppe|frederik vahle|paw patrol|sandmännchen|die maus|kinderdisco|" +
                "kinderlieder superstar|kinderlieder-?bande|bummi|sing ?kinderlieder|toggolino|" +
                "eiskönigin|lass jetzt los|vaiana|encanto|könig der löwen|dschungelbuch|probier'?s mal mit gemütlichkeit|arielle|" +
                "feuerwehrmann sam|peppa|bob der baumeister|conni|janosch|tigerente|sendung mit der maus|löwenzahn|pumuckl|" +
                "räuber hotzenplotz|wickie|alfred j|grüffelo|regenbogenfisch|bobo siebenschläfer|leo lausemaus|" +
                "baby shark|old macdonald|wheels on the bus|twinkle twinkle|head shoulders|itsy bitsy|five little|" +
                "alle meine entchen|hänschen klein|kuckuck|fuchs du hast|backe backe kuchen|häschen in der grube|ein vogel wollte|" +
                "der mond ist aufgegangen|guten abend gut' nacht|weißt du wieviel|schlaf kindlein|bruder jakob|hoch auf dem gelben|" +
                "die vogelhochzeit|ich bin ein musikante|meine oma fährt|das lied über mich|tschu tschu wa|der gorilla mit der|" +
                "hey hey wickie|aramsamsam|laurentia|jetzt fahrn wir|in der weihnachtsbäckerei|lasst uns froh|kindergarten")
        else if (genre?.key == "xmas")
            Regex("(?i)christmas|xmas|weihnacht|santa claus|jingle bell|noel|navidad|rudolph|sleigh|mistletoe|let it snow|snowman|winter wonderland|silent night|stille nacht|tannenbaum|advent|nikolaus|lebkuchen|schneeflöckchen|leise rieselt|kling glöckchen|feliz navidad|little drummer|frosty")
        else null
        onlyListed = favMix || !Radar.ok
        stations = newStations

        poll(force = true)
        val found = withContext(Dispatchers.Default) {
            // Auch bei Themen-Mixen (Weihnachten, Kinder) Lieder mit unbekanntem Anfang nehmen, statt gar nichts zu spielen.
            // Zufallsmusik gibt es bei ihnen trotzdem nicht (fromAny ist bei strict aus).
            choose() ?: choose(allowRepeats = true) ?: choose(allowRepeats = true, anyMusic = true)
        }
        if (stale()) return null
        // Nichts Passendes gefunden: bei einem Mix mit fester Richtung einen Sender dieser Richtung spielen
        // (vorher kam irgendein Musiksender, z. B. Pop im Rock-Mix), bis woanders ein passendes Lied anfängt
        val first = found?.let { commit(it) } ?: if (focused) genreFallback(genreStations) else null
        switchedAt = System.currentTimeMillis(); confirmed = false
        // Nichts gefunden: solange läuft ein Ersatz-Sender, der Mix springt um, sobald ein passender Hit anfängt
        waitingForNext = found == null
        if (first == null) prefs(context).edit().putString("now", "Gerade läuft kein passender Hit – ich warte auf den nächsten").apply()
        job = scope.launch { delay(3_000); if (gen == myGen) refreshQueue(); loop(myGen) }
        return first?.let { prepareItem(it) }
    }

    /** Sender der Richtung als Ersatz: am liebsten einer, auf dem gerade Musik läuft. */
    private fun genreFallback(list: List<Station>): MediaItem? {
        if (list.isEmpty()) return null
        val withMusic = list.firstOrNull { st -> LiveIndex.get(st.id)?.let { LiveIndex.isMusic(it.title) && !Ads.likely(context, st.id, st.url) } == true }
        val st = (withMusic ?: list.firstOrNull { !Ads.likely(context, it.id, it.url) } ?: list.first())
            .takeIf { !Cast.active || Cast.isHttps(it.url) } ?: list.firstOrNull { Cast.isHttps(it.url) } ?: return null
        val now = LiveIndex.get(st.id)
        currentStation = st
        currentStationId = st.id
        switchedTitle = now?.normalized
        currentSongKey = now?.normalized
        songStartedAt = now?.since ?: System.currentTimeMillis()
        val song = now?.title?.takeIf { LiveIndex.isMusic(it) }
        prefs(context).edit().putString("now", "${st.name} · ich springe, sobald woanders ein passendes Lied anfängt").apply()
        return st.toEntry(song).toMediaItem()
    }

    /** Passende Sender zum Genre (Tags geprüft: kein Hit-Sender im Rock-Mix; Weihnachten/Kinder mit eigenen Listen). */
    private suspend fun genreStationsFor(genre: Genre?): List<Station> = StationRepository.forGenre(context, genre)

    /** Hits eines Genres (für Playlists als Ersatz hinten angehängt). */
    private suspend fun genreHits(g: Genre?): List<Entry> = when {
        g == null || g.key == "charts" -> Deezer.topGermany(100)
        g.key == "kids" -> Deezer.kidsHits()
        g.playlist != null -> Deezer.playlistByName(g.playlist)
        g.deezerGenre != null && g.deezerGenre != 0 -> Deezer.genreChart(g.deezerGenre, 50)
        // Ersatz-Genre ohne eigene Liste (z. B. Schlager-Playlist): keine fremden Pop-Charts untermischen
        MixLogic.focused(g.key) -> emptyList()
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
        gen++
        Radar.fast = false
        val wasActive = job?.isActive == true
        job?.cancel()
        job = null
        // Eingereihte "Als Nächstes"-Sender wieder entfernen
        if (wasActive) try {
            player()?.let { p -> if (p.mediaItemCount > p.currentMediaItemIndex + 1) p.removeMediaItems(p.currentMediaItemIndex + 1, p.mediaItemCount) }
        } catch (_: Exception) {}
        queued = emptyMap()
        candidate = null
        currentStationId = null
        currentSongKey = null
        switchedAt = 0L
        switchedTitle = null
        confirmed = false
        lastLocal = 0L
        songStartedAt = 0L
        waitingForNext = false
        autoSwitches.clear()
        endTimer?.cancel(); endTimer = null
        history.clear(); currentStation = null
        fadeJob?.cancel(); fadeJob = null
        prefs(context).edit().remove("name").remove("now").apply()
    }

    private var waitingForNext = false

    /** Sofort zum nächsten Hit springen. Gibt false zurück, wenn wirklich nichts Passendes läuft. */
    suspend fun next(): Boolean {
        val g = gen
        // Sofort: vorbereiteten Kandidaten nehmen bzw. aus dem vorhandenen Stand wählen (kein Warten aufs Netz)
        val item = withContext(Dispatchers.Default) {
            takeCandidate() ?: choose() ?: choose(allowRepeats = true) ?: choose(allowRepeats = true, anyMusic = true)
        }?.takeIf { g == gen }?.let { commit(it) } ?: run {
            if (g != gen) return false
            // Gar nichts im Speicher: dann doch kurz nachsehen
            if (!Radar.sync(force = true)) {
                val priority = (stations.filter { it.id in genreIds } + stations).distinctBy { it.id }.take(40)
                withTimeoutOrNull(4_000) { LiveIndex.refresh(priority, maxAgeMs = 10_000L) }
            }
            withContext(Dispatchers.Default) { choose(allowRepeats = true) ?: choose(allowRepeats = true, anyMusic = true) }
                ?.takeIf { g == gen }?.let { commit(it) }
        }
        // Während der Suche gestoppt oder ein anderer Mix gestartet: nichts mehr umschalten
        if (g != gen) return false
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
    @Volatile private var candidate: Pair<Long, Choice>? = null

    /** Titel aus dem Stream des laufenden Senders (kommt genau dann, wenn er sich ändert). */
    suspend fun onStreamTitle(stationId: String, title: String, music: Boolean) {
        if (stationId != currentStationId || job?.isActive != true) return
        val g = gen
        // Pausiert/gestoppt: nie von selbst umschalten (Stopp bleibt Stopp)
        if (player()?.playWhenReady != true) return
        val now = System.currentTimeMillis()
        lastLocal = now
        val norm = Scanner.normalize(title)
        val sinceSwitch = now - switchedAt
        if (music && (switchedTitle == null || sameSong(norm, switchedTitle))) {
            // Läuft wie erwartet
            switchedTitle = norm; confirmed = true; waitingForNext = false
            return
        }
        if (music && !confirmed && sinceSwitch < 25_000 && !strict && (!focused || fitsTitle(norm, stationId))) {
            // Erster Titel nach dem Umschalten ist ein anderes Lied als erwartet: nicht gleich wieder springen.
            // Bei Mixen mit fester Richtung nur, wenn es passt (sonst nach der Sprungbremse weiter, statt falscher Musik)
            switchedTitle = norm; confirmed = true; waitingForNext = false
            currentSongKey = norm; addPlayed(norm)
            prefs(context).edit().putString("now", Text.clean(title)).apply()
            return
        }
        if (music) {
            val fresh = !confirmed && sinceSwitch < 25_000   // erster Titel nach dem Umschalten
            if (isPlaylist && !primary.any { it.matches(norm) }) {
                // Kein Playlist-Lied: fängt woanders gerade eins aus der Playlist an? Dann dorthin.
                if (autoSwitch { ranked(limit = 1, onlyPrimary = true).firstOrNull() }) return
                if (g != gen || stationId != currentStationId) return
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
        // Keine Musik mehr laut Titel (Moderation, Jingle, Werbung): weiter.
        // Kurz nach dem Umschalten nicht – da kommt oft erst der Sendername oder Vorab-Werbung.
        // Viele Sender schicken mitten im Lied ihren Slogan oder "Jetzt auf Radio X" als Titel: hört die
        // Klang-Erkennung noch Musik, ist das Lied nicht vorbei (die Ansage-Erkennung übernimmt, wenn Sprache kommt).
        if (stillMusic()) return
        if (confirmed || sinceSwitch > 25_000) switchNow()
    }

    private val autoSwitches = ArrayDeque<Long>()

    /** Höchstens 3 automatische Sprünge pro Minute, und nie in den ersten 20 s nach einem Sprung (außer der Sender ist kaputt). */
    private fun mayAutoSwitch(force: Boolean = false): Boolean {
        val t = System.currentTimeMillis()
        while (autoSwitches.isNotEmpty() && t - autoSwitches.first() > 60_000L) autoSwitches.removeFirst()
        if (force) return autoSwitches.size < 6
        return autoSwitches.size < 3 && t - switchedAt > 20_000L
    }

    /** Läuft gerade eine automatische Suche? Dann keine zweite gleichzeitig (sonst zwei Sprünge auf einmal). */
    private var switching = false

    /**
     * Automatisch umschalten, mit Sprungbremse. Gezählt wird nur ein echter Sprung
     * (vorher zählte jeder vergebliche Versuch und blockierte dann den nächsten echten Hit).
     * Gibt true zurück, wenn umgeschaltet wurde.
     */
    private suspend fun autoSwitch(force: Boolean = false, pick: () -> Choice?): Boolean {
        if (switching || !mayAutoSwitch(force)) return false
        val g = gen
        val at = switchedAt
        switching = true
        val next = try { withContext(Dispatchers.Default) { pick() } } finally { switching = false }
        // Inzwischen gestoppt, pausiert, ein anderer Mix oder von Hand umgeschaltet: nichts tun (Stopp bleibt Stopp)
        if (next == null || g != gen || at != switchedAt || job?.isActive != true || player()?.playWhenReady != true) return false
        autoSwitches.addLast(System.currentTimeMillis())
        waitingForNext = false
        switchTo(commit(next))
        return true
    }

    private suspend fun switchNow(force: Boolean = false) {
        if (switching) return
        val g = gen
        // Lieber andere Musik als Moderation: notfalls auch ein schon gespieltes oder irgendein Lied (nicht bei Themen-Mixen)
        val done = autoSwitch(force) {
            takeCandidate() ?: choose() ?: choose(allowRepeats = true) ?: choose(allowRepeats = true, anyMusic = true)
        }
        if (!done && g == gen) waitingForNext = true
    }

    private suspend fun loop(myGen: Int) {
        var tick = 0
        while (currentCoroutineContext().isActive && gen == myGen) {
            delay(3_000)
            if (gen != myGen) return
            tick++
            val p = player() ?: continue
            // Gestoppt (Stopp gedrückt, Liste leer): Mix beenden – nicht von selbst wieder anfangen
            if (switchedAt > 0 && (p.mediaItemCount == 0 || (p.playbackState == Player.STATE_IDLE && p.playerError == null))) { stop(); return }
            if (!p.playWhenReady) continue
            // Kaputter Sender: weiter. Ohne Netz (Funkloch) nicht – der Player verbindet von selbst neu, sobald Netz da ist
            val broken = p.playerError != null && Net.online(context)
            // Länge bekannt und überschritten, Titel aber unverändert: Lied ist vorbei (Moderator redet o. Ä.)
            // Klang-Erkennung hört noch Musik: Lied ist länger als gedacht (andere Fassung), nicht abwürgen (höchstens 1 Min.)
            val overrun = NowSong.stationId == currentStationId && NowSong.overrun() &&
                NowSong.title?.let { sameSong(Scanner.normalize(it), switchedTitle) } == true &&
                (!stillMusic() || NowSong.elapsed() > NowSong.durMs + 60_000L)
            if ((broken || overrun) && (!waitingForNext || tick % 3 == 0)) { switchNow(force = broken); continue }
            // Klang-Erkennung hört Moderation/Werbung zwischen den Liedern (ist schon stumm): gleich zum nächsten Hit
            if (muteForTalk() && SoundSense.talkFor(4_000)) { switchNow(); continue }
            // Liedende sekundengenau bekannt: kurz vor Schluss umschalten, bevor die Moderation anfängt
            planEndSwitch(myGen)
            if (waitingForNext && tick % 3 == 0) { switchNow(); continue }
            if (tick % 7 != 0) continue   // ca. alle 20 Sekunden: Server-Stand + nächsten Kandidaten vorbereiten
            poll(force = false)
            if (gen != myGen) return
            // Liedlängen der frisch angefangenen Lieder holen (damit fast vorbei nicht gewählt wird)
            withContext(Dispatchers.Default) {
                LiveIndex.fresh().filter { LiveIndex.isMusic(it.title) && System.currentTimeMillis() - it.since < 150_000 && !Covers.known(it.title) }
                    .sortedByDescending { it.since }.take(12)
            }.forEach { n -> scope.launch { Covers.load(n.title) } }
            val prepared = withContext(Dispatchers.Default) { choose() }
            if (gen != myGen) return
            candidate = prepared?.let { System.currentTimeMillis() to it }
            refreshQueue()
            // Ohne Titel im Stream (z. B. beim Casten oder Sender ohne Titel): Server-Stand entscheidet
            val local = !Cast.active && System.currentTimeMillis() - lastLocal < 10 * 60_000L
            if (local) continue
            val now = currentStationId?.let { LiveIndex.get(it) } ?: continue
            val stillHit = (switchedTitle == null || sameSong(now.normalized, switchedTitle)) && LiveIndex.isMusic(now.title)
            val tooLong = NowSong.durMs == 0L && System.currentTimeMillis() - songStartedAt > 9 * 60_000L
            // Server-Stand ist bis zu ~1,5 Min. alt: hört die Klang-Erkennung noch Musik, nicht umschalten
            if ((!stillHit || tooLong) && !stillMusic()) {
                if (LiveIndex.isMusic(now.title) && fitsTitle(now.normalized, now.station.id) && now.normalized !in played) {
                    switchedTitle = now.normalized; songStartedAt = now.since
                    addPlayed(now.normalized)
                } else switchNow()
            }
        }
    }

    private var endTimer: Job? = null

    /** Läuft ein Lied mit sekundengenau bekanntem Anfang und bekannter Länge, kurz vor dem Ende umschalten. */
    private fun planEndSwitch(myGen: Int) {
        if (endTimer?.isActive == true) return
        if (NowSong.stationId != currentStationId || !NowSong.known() || !NowSong.startLocal) return
        val left = NowSong.durMs - NowSong.elapsed()
        if (left !in 0L..9_000L) return
        val title = NowSong.title
        endTimer = scope.launch {
            // Vorladen, nur kurz vor dem Wechsel: nächsten Sender jetzt aussuchen und die Verbindung aufbauen
            // (Name auflösen, Verschlüsselung, ein paar KB). Beim Umschalten ist der Sender dann schneller da.
            val next = withContext(Dispatchers.Default) { choose() }
            if (gen != myGen) return@launch
            if (next != null) { candidate = System.currentTimeMillis() to next; warmUp(next.now.station.url) }
            delay((left - 1_200L).coerceAtLeast(0L))
            // Liedlänge stimmt nicht immer (Radio-Fassung, Titel kam früher): solange noch Musik zu hören ist, warten
            // (höchstens 20 s), dann umschalten, sobald die Musik aufhört
            var waited = 0L
            while (stillMusic() && waited < 20_000L && gen == myGen && NowSong.title == title) { delay(500); waited += 500 }
            if (gen == myGen && NowSong.title == title && NowSong.stationId == currentStationId && player()?.playWhenReady == true) switchNow()
        }
    }

    /** Verbindung zum nächsten Sender schon mal aufbauen und gleich wieder schließen (höchstens 8 KB). */
    private fun warmUp(url: String) {
        if (Cast.active || url.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 4_000; c.readTimeout = 4_000
                c.setRequestProperty("User-Agent", "Hitradar/1.0")
                try { c.inputStream.use { it.read(ByteArray(8 * 1024)) } } finally { c.disconnect() }
            } catch (_: Exception) {}
        }
    }

    // ---------- Wie schnell ein Sender nach dem Umschalten spielt (auf dem Handy gemerkt) ----------
    private val speedPrefs by lazy { context.applicationContext.getSharedPreferences("hitmix_speed", Context.MODE_PRIVATE) }

    private fun startDelay(stationId: String): Long = speedPrefs.getLong(stationId, 0L)

    private fun rememberStart(stationId: String, ms: Long) {
        val old = speedPrefs.getLong(stationId, 0L)
        val v = if (old == 0L) ms else (old * 2 + ms) / 3
        speedPrefs.edit().putLong(stationId, v.coerceIn(1L, 30_000L)).apply()
    }

    /** Hört die Klang-Erkennung gerade sicher Musik? (nur wenn sie läuft) */
    private fun stillMusic() = SoundSense.active && SoundSense.musicFor(3_000)

    /** Dasselbe Lied, nur anders geschrieben ("(Radio Edit)", feat., Reihenfolge)? Dann kein neues Lied. */
    private fun sameSong(a: String, b: String?): Boolean = MixLogic.sameSong(a, b)

    /** Lied ist fast vorbei (weniger als 1,5 Min. übrig): gar nicht erst hinschalten. */
    private fun almostOver(n: LiveIndex.Now, t: Long): Boolean {
        if (!LiveIndex.sinceKnown(n, t)) return false
        val age = t - n.since
        val dur = Covers.byKey(n.normalized)?.durMs ?: 0L
        return if (dur > 0) dur - age < 90_000L else age > 210_000L
    }

    /**
     * Soll wegen Sprache stumm sein? Klang-Erkennung hört seit 1,5 s nur Sprache, und es läuft gerade kein Lied
     * (oder das Lied ist laut Länge vorbei). Mitten in einem Lied (Rap, gesprochene Stelle, Intro) nie.
     */
    fun muteForTalk(): Boolean {
        if (job?.isActive != true || !SoundSense.talkFor(2_000)) return false
        if (NowSong.stationId != currentStationId) return false
        // Kein Lied gemeldet: nur, wenn der Sender überhaupt Titel schickt und zuletzt "kein Lied" gemeldet hat
        // (Moderation/Jingle). Sender ohne Titel: Gesang und Sprache sind dort nicht sicher zu trennen, lieber nichts tun.
        if (NowSong.title == null) return NowSong.localAt > 0
        return NowSong.known() && NowSong.elapsed() > NowSong.durMs - 15_000L
    }

    /** Vorbereiteten Kandidaten nehmen, wenn er noch stimmt (läuft noch dasselbe Lied, nicht zu alt). */
    private fun takeCandidate(): Choice? {
        val (at, c) = candidate ?: return null
        candidate = null
        if (System.currentTimeMillis() - at > 60_000L) return null
        val cur = LiveIndex.get(c.now.station.id) ?: return null
        if (cur.normalized != c.now.normalized || c.now.station.id == currentStationId) return null
        if (c.key in played) return null
        if (almostOver(cur, System.currentTimeMillis())) return null
        return c
    }

    /** Passt dieses Lied zum Mix? (Liste, Genre-Sender, Thema oder bekannter Hit bei offenen Mixen) */
    private fun fitsTitle(norm: String, stationId: String): Boolean =
        songs.any { it.matches(norm) } || stationId in genreIds || themeWords?.containsMatchIn(norm) == true ||
            (!strict && genreIds.isEmpty() && (Radar.chartPlays[norm] ?: 0) >= 15) ||
            // Sender mit Herz (Favorit): dort bleiben, solange Musik ohne Ansage weiterläuft – nur bei offenen Mixen
            // (im Rock-Mix bleibt er nicht auf dem Lieblings-Popsender, außer der ist selbst ein Rocksender, s. genreIds)
            (!focused && Favorites.isFav(context, "st:$stationId"))

    // ---------- Zurück: der vorherige Sender (live, also mit dem Lied, das dort jetzt läuft) ----------
    private var currentStation: Station? = null
    private val history = ArrayDeque<Station>()

    /** Zum vorherigen Sender zurück. Es ist Live-Radio: man hört, was dort jetzt läuft, nicht den alten Liedanfang. */
    fun previous(): Boolean {
        if (job?.isActive != true) return false
        val st = history.removeLastOrNull() ?: return false
        val now = LiveIndex.get(st.id)
        currentStation = st
        currentStationId = st.id
        currentSongKey = now?.normalized
        switchedTitle = now?.normalized
        songStartedAt = now?.since ?: System.currentTimeMillis()
        waitingForNext = false
        candidate = null
        val song = now?.title?.takeIf { LiveIndex.isMusic(it) }
        prefs(context).edit().putString("now", "Zurück zu ${st.name}" + (song?.let { " · läuft jetzt: $it" } ?: " · live")).apply()
        switchTo(st.toEntry(song).toMediaItem())
        return true
    }

    fun hasPrevious() = job?.isActive == true && history.isNotEmpty()

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

    private fun commit(c: Choice): MediaItem {
        val n = c.now
        // Für "Zurück": vorherigen Sender merken
        currentStation?.let { prev -> if (prev.id != n.station.id) { history.addLast(prev); while (history.size > 20) history.removeFirst() } }
        currentStation = n.station
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
                LiveIndex.isMusic(it.title) && !almostOver(it, t) &&
                (anyMusic || LiveIndex.sinceKnown(it, t))   // nur Lieder, deren Anfang bekannt ist (sonst evtl. mittendrin)
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
        val fromArtist = if (strict) emptyList() else pool.filter { n -> artistWords.any { w -> w.all { MixLogic.hasWord(n.normalized, it) } } }
            .map { it to it.normalized }.filter { (_, k) -> ok(k) }
        // Irgendeine Musik nur bei offenen Mixen (Charts, Mein Mix): sonst liefe im Rock-Mix plötzlich Schlager
        val fromAny = if (anyMusic && !focused) pool.map { it to it.normalized }.filter { (_, k) -> ok(k) } else emptyList()

        // Bekanntheit: läuft auf vielen Sendern, steht weit oben in der Liste oder ist von einem bekannten Künstler
        val pop = LiveIndex.popularity()
        val rank = HashMap<String, Int>().also { m -> songs.forEachIndexed { i, sg -> m.putIfAbsent(sg.key, i) } }
        // Sender, bei denen beim Umschalten Werbung kommt, nur nehmen, wenn es gar nicht anders geht
        fun noAds(l: List<Pair<LiveIndex.Now, String>>) = l.filter { (n, _) -> !Ads.likely(context, n.station.id, n.station.url) }
        val tiers = listOf(fromPrimary, fromList, fromGenre, fromArtist, fromAny)
        // Beim Casten nur Sender mit https (andere lassen sich nicht casten, Weiterleiten über den Server gibt es nicht)
        fun castable(l: List<Pair<LiveIndex.Now, String>>) = if (Cast.active) l.filter { (n, _) -> Cast.isHttps(n.station.url) } else l
        val list = (tiers.map { castable(noAds(it)) } + tiers.map { castable(it) }).firstOrNull { it.isNotEmpty() } ?: return emptyList()
        return list.sortedBy { (n, key) ->
                // Bekannte Lieder deutlich bevorzugen: läuft auf vielen Sendern, oft gespielt heute, weit oben in der Liste
                val many = ((pop[n.normalized] ?: 1) - 1).coerceAtMost(6) * 60_000L
                // Gerade oft im Radio = aktuell und bekannt. Bei Ära-Mixen (80er, Oldies …) zählt das nicht,
                // dort entscheidet die Liste des Mixes, sonst kämen nur die paar Dauerbrenner, die heute noch laufen.
                val played = if (eraMix) 0L else (Radar.chartPlays[n.normalized] ?: 0).coerceAtMost(300) * 2_000L
                val top = rank[key]?.let { (100 - it).coerceAtLeast(0) * 2_000L } ?: 0L
                val knownArtist = if (artistWords.any { w -> w.all { MixLogic.hasWord(n.normalized, it) } }) 90_000L else 0L
                // Frisch angefangen zählt; fast vorbei (über 3 Min.) und Sender mit Vorab-Werbung rutschen nach hinten
                val age = t - n.since
                // Sender, die beim Umschalten lange brauchen (Lücke), nach hinten
                val slow = startDelay(n.station.id).let { d -> if (d > 6_000L) 240_000L else if (d > 3_000L) d * 15 else 0L }
                age + slow + (if (age > 180_000L) 300_000L else 0L) +
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
        val g = gen
        val next = withContext(Dispatchers.Default) { ranked(limit = 5) }
        // Inzwischen gestoppt oder anderer Mix: nichts mehr einreihen
        if (g != gen || job?.isActive != true) return
        val p = player() ?: return
        if (p.mediaItemCount == 0) return
        val idx = p.currentMediaItemIndex
        val items = next.map { c ->
            Covers.dress(c.now.station.toEntry(c.now.title)).toMediaItem().let { m ->
                val extras = Bundle(m.mediaMetadata.extras ?: Bundle.EMPTY).apply { putString("hm_title", c.now.title) }
                m.buildUpon().setMediaMetadata(m.mediaMetadata.buildUpon().setExtras(extras).build()).build()
            }
        }
        queued = next.associateBy { "st:" + it.now.station.id }
        // Gleiche Vorschau wie schon in der Liste (gleiche Sender, Lieder, Bilder): nichts anfassen.
        // Sonst bauen Android Auto, Benachrichtigung und Widget alle 20 s alles neu auf.
        fun sig(m: MediaItem) = listOf(m.mediaId, m.mediaMetadata.extras?.getString("hm_title"), m.mediaMetadata.artworkUri?.toString(), m.mediaMetadata.title?.toString())
        if (idx == 0 && items.isNotEmpty() && p.mediaItemCount == items.size + 1 &&
            items.indices.all { sig(items[it]) == sig(p.getMediaItemAt(it + 1)) }) return
        if (p.mediaItemCount > idx + 1) p.removeMediaItems(idx + 1, p.mediaItemCount)
        if (idx > 0) p.removeMediaItems(0, idx)
        // In der Liste mit Liedtitel und Sender anzeigen
        if (items.isNotEmpty()) p.addMediaItems(items)
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
        fadeIn(p)
    }

    private var fadeJob: Job? = null

    /** Weicher Wechsel: leise starten und in ~0,6 s hochblenden, sobald der neue Sender spielt (kein Knacken, kein Anriss). */
    private fun fadeIn(p: Player) {
        fadeJob?.cancel()
        if (Cast.active) return   // beim Casten ist die Lautstärke die des Lautsprechers: nicht anfassen
        try { p.volume = 0f } catch (_: Exception) { return }
        val sid = currentStationId
        fadeJob = scope.launch {
            try {
                val start = System.currentTimeMillis()
                while (!p.isPlaying && System.currentTimeMillis() - start < 10_000L) delay(100)
                // Merken, wie lange der Sender gebraucht hat (langsame kommen künftig seltener dran)
                if (sid != null) rememberStart(sid, if (p.isPlaying) System.currentTimeMillis() - start else 10_000L)
                delay(250)
                for (i in 1..10) {
                    if (muted()) return@launch   // Werbung/Ansage: die Stummschaltung übernimmt
                    p.volume = targetVolume() * i / 10f
                    delay(60)
                }
            } finally {
                val target = targetVolume()
                if (!muted() && p.volume < target) try { p.volume = target } catch (_: Exception) {}
            }
        }
    }
}
