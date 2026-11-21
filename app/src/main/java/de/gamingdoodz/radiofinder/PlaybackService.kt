package de.gamingdoodz.radiofinder

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.DynamicsProcessing
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Spielt im Hintergrund ab (auch bei gesperrtem Bildschirm)
 * und liefert Listen, Suche und Favoriten-Knopf für Android Auto.
 */
class PlaybackService : MediaLibraryService() {

    companion object {
        const val CMD_SLEEP = "SLEEP_TIMER"
        const val CMD_HITMIX = "HITMIX"        // args: "genre" (leer = Deutsche Charts), "stop"
        const val CMD_NEXT = "NEXT_HIT"
        const val CMD_FADEIN = "FADE_IN"          // Wecker: leise anfangen, lauter werden
        const val CMD_BEDTIME = "BEDTIME"         // Einschlaf-Modus, args: "min"
        const val CMD_ENDLESS = "ENDLESS"         // Endlos freie Musik, args: "tag"
    }

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val itemCache = ConcurrentHashMap<String, MediaItem>()
    private val searchResults = ConcurrentHashMap<String, List<MediaItem>>()
    // Für freie Lieder: die ganze Liste merken, damit man in Android Auto weiterspringen kann
    private val listContext = ConcurrentHashMap<String, List<MediaItem>>()
    private val favCommand = SessionCommand("FAV_TOGGLE", Bundle.EMPTY)
    private val sleepCommand = SessionCommand(CMD_SLEEP, Bundle.EMPTY)
    private val hitMixCommand = SessionCommand(CMD_HITMIX, Bundle.EMPTY)
    private val nextCommand = SessionCommand(CMD_NEXT, Bundle.EMPTY)
    private val shows = ConcurrentHashMap<String, Entry>()
    private val posRunnable = object : Runnable {
        override fun run() {
            savePosition()
            sleepHandler.postDelayed(this, 15_000)
        }
    }

    /** Wo war man bei der Geschichte? Wird regelmäßig gespeichert. */
    private fun savePosition() {
        val p = session?.player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        Positions.save(this, id, p.currentPosition, p.duration.coerceAtLeast(0))
    }

    private val hitMix by lazy { HitMix(this, scope, { session?.player }, { liveTitle(it) ?: it }) }
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bedtime = false
    private var endlessTag: String? = null
    private var endlessOffset = 0
    private var endlessLoading = false

    /** Fast am Ende der Liste: die nächsten 200 freien Lieder anhängen. */
    private fun checkEndless() {
        val tag = endlessTag ?: return
        val p = session?.player ?: return
        if (endlessLoading || p.mediaItemCount - p.currentMediaItemIndex > 3) return
        endlessLoading = true
        scope.launch {
            Jamendo.highQuality = Settings.highQuality(this@PlaybackService)
            val more = Jamendo.page(tag, endlessOffset)
            endlessOffset += 200
            if (more.isNotEmpty()) p.addMediaItems(more.map { it.toMediaItem() })
            endlessLoading = false
        }
    }
    // Werbung am Senderanfang messen
    private var stationStart = 0L
    private var musicSeen = false
    private var nonMusicSeen = false
    private var exo: ExoPlayer? = null
    private var dynamics: DynamicsProcessing? = null

    /** Am Ende des Schlaf-Timers: 60 Sekunden langsam leiser, dann aus. */
    private val sleepRunnable = object : Runnable {
        private var step = 0
        override fun run() {
            val p = session?.player ?: return
            if (step < 12) {
                step++
                p.volume = (1f - step / 12f).coerceAtLeast(0f)
                sleepHandler.postDelayed(this, 5_000)
            } else {
                step = 0
                p.pause()
                // Hörspiel: ein paar Minuten zurück, damit man beim Weiterhören nichts Verschlafenes verpasst
                if (isStory(p.currentMediaItem?.mediaId)) {
                    p.seekTo((p.currentPosition - 3 * 60_000L).coerceAtLeast(0L))
                    savePosition()
                }
                p.volume = 1f
                bedtime = false
                Settings.setSleepEnd(this@PlaybackService, 0L)
            }
        }
    }

    /** Wecker: in einer Minute von leise auf normal. */
    private val fadeInRunnable = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            if (p.volume < 1f) {
                p.volume = (p.volume + 0.06f).coerceAtMost(1f)
                sleepHandler.postDelayed(this, 4_000)
            }
        }
    }

    private fun startSleepTimer(min: Int) {
        sleepHandler.removeCallbacks(sleepRunnable)
        session?.player?.volume = 1f
        if (min > 0) {
            // Ausblenden beginnt eine Minute vor Schluss
            sleepHandler.postDelayed(sleepRunnable, (min * 60_000L - 60_000L).coerceAtLeast(0L))
            Settings.setSleepEnd(this, System.currentTimeMillis() + min * 60_000L)
        } else {
            Settings.setSleepEnd(this, 0L)
        }
    }

    /** Lautstärke zwischen Sendern angleichen (Kompressor + Begrenzer). */
    private fun setupNormalizer(sessionId: Int) {
        try {
            dynamics?.release()
            dynamics = null
            if (!Settings.normalize(this) || android.os.Build.VERSION.SDK_INT < 28 || sessionId == C.AUDIO_SESSION_ID_UNSET) return
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, true, 1, false, 0, true
            ).build()
            val d = DynamicsProcessing(0, sessionId, cfg)
            d.setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(true, 20000f, 5f, 120f, 3f, -26f, 6f, -90f, 1f, 0f, 5f))
            d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1.5f, 0f))
            d.enabled = true
            dynamics = d
        } catch (_: Exception) {
            dynamics = null
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "normalize") exo?.let { setupNormalizer(it.audioSessionId) }
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateFavButton() }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Radar.init(this)
        scope.launch { Blocklist.refresh(applicationContext) }
        scope.launch { Radar.loadAds() }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Hitradar/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo = player
        Jamendo.highQuality = Settings.highQuality(this)
        setupNormalizer(player.audioSessionId)
        player.addListener(object : Player.Listener {
            private var retries = 0

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                // Einschlaf-Modus: nach der Geschichte leise Schlaflieder
                if (state == Player.STATE_ENDED && bedtime) {
                    scope.launch {
                        val songs = (Jamendo.byTag("lullaby", 30) + Jamendo.search("schlaflied", 15)).distinctBy { it.id }
                        val p = session?.player ?: return@launch
                        if (songs.isNotEmpty()) {
                            p.setMediaItems(songs.map { it.toMediaItem() })
                            p.volume = 0.7f
                            p.prepare(); p.play()
                        }
                    }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) = setupNormalizer(audioSessionId)

            override fun onEvents(player: Player, events: Player.Events) {
                // Widget auf dem Startbildschirm aktualisieren
                val item = player.currentMediaItem
                val m = item?.mediaMetadata
                val title = player.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: m?.title?.toString()?.takeIf { it.isNotBlank() } ?: m?.artist?.toString() ?: "Hitradar"
                val sub = HitMix.activeName(this@PlaybackService)?.let { "⚡ Hit-Mix · $it" } ?: m?.artist?.toString().orEmpty()
                RadioWidget.update(this@PlaybackService, title, sub, player.isPlaying, item?.let { Favorites.isFav(this@PlaybackService, it.mediaId) } == true)
                updateWidgetArt(m?.artworkUri?.toString())
            }

            /** Fehler abfangen: Lied überspringen bzw. Sender kurz neu verbinden. */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val p = session?.player ?: return
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem(); p.prepare(); p.play()
                } else if (retries < 2 && p.currentMediaItem?.mediaId?.startsWith("st:") == true) {
                    retries++
                    sleepHandler.postDelayed({ p.prepare(); p.play() }, 3000)
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                val p = session?.player ?: return
                val item = p.currentMediaItem ?: return
                if (!item.mediaId.startsWith("st:")) return
                val t = mediaMetadata.title?.toString()?.trim().orEmpty()
                val id = item.mediaId.removePrefix("st:")
                if (LiveIndex.isMusic(t)) Ads.markIcy(this@PlaybackService, id)
                if (!musicSeen && stationStart > 0) {
                    if (LiveIndex.isMusic(t) && !Ads.isAd(t)) {
                        musicSeen = true
                        val took = System.currentTimeMillis() - stationStart
                        // Erst nach >10 s Musik und vorher Werbung/Ansage: das war Vorab-Werbung
                        // Vorab-Werbung hat meist gar keinen Titel: erstes Lied erst nach >12 s = Werbung davor
                        if (took in 10_001..170_000 && (nonMusicSeen || Ads.isAd(t) || took > 12_000)) Ads.record(this@PlaybackService, id, took)
                        else if (took < 6_000) Ads.clear(this@PlaybackService, id)
                    } else if (t.isNotEmpty()) {
                        nonMusicSeen = true
                    }
                }
                // Erst als "gehört" merken, wenn das Lied mindestens 45 Sekunden lief
                // (nicht beim schnellen Durchschalten im Hit-Mix)
                sleepHandler.removeCallbacks(heardRunnable)
                if (t.contains(" - ") || t.contains(" – ")) {
                    heardTitle = t
                    heardStation = item.mediaMetadata.artist?.toString().orEmpty()
                    sleepHandler.postDelayed(heardRunnable, 45_000)
                }
                updateArtwork(item, t)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition()
                if (isPlaying && stationStart == 0L && session?.player?.currentMediaItem?.mediaId?.startsWith("st:") == true) {
                    stationStart = System.currentTimeMillis()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (sameItemReplace && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    sameItemReplace = false
                    return
                }
                sameItemReplace = false
                stationStart = 0L; musicSeen = false; nonMusicSeen = false
                // Tempo gilt nur für Hörspiele und Hörbücher
                if (!isStory(mediaItem?.mediaId)) session?.player?.setPlaybackSpeed(1f)
                if (mediaItem?.mediaId?.startsWith("jm:") == true) checkEndless() else if (mediaItem != null) endlessTag = null
                updateFavButton()
                mediaItem?.let { Entry.fromMediaItem(it) }?.let { Recent.add(this@PlaybackService, it) }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Titel für Benachrichtigung, Auto und App säubern
        val shown = object : ForwardingPlayer(player) {
            override fun getMediaMetadata(): MediaMetadata {
                val m = super.getMediaMetadata()
                val t = m.title?.toString() ?: return m
                val c = Text.clean(t)
                return if (c == t) m else m.buildUpon().setTitle(c).build()
            }
        }
        session = MediaLibrarySession.Builder(this, shown, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
        Favorites.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(settingsListener)
        // Ein alter Hit-Mix von vor einem Neustart läuft nicht mehr
        HitMix.prefs(this).edit().clear().apply()
        sleepHandler.postDelayed(posRunnable, 15_000)
        // Schlaf-Timer nach Neustart des Dienstes wiederherstellen
        val left = Settings.sleepEnd(this) - System.currentTimeMillis()
        if (left > 0) sleepHandler.postDelayed(sleepRunnable, (left - 60_000L).coerceAtLeast(0L)) else Settings.setSleepEnd(this, 0L)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        savePosition()
        sleepHandler.removeCallbacks(posRunnable)
        hitMix.stop()
        sleepHandler.removeCallbacks(sleepRunnable)
        Settings.setSleepEnd(this, 0L)
        Favorites.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        sleepHandler.removeCallbacks(fadeInRunnable)
        try { dynamics?.release() } catch (_: Exception) {}
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Favoriten-Knopf (Benachrichtigung und Android Auto) ----------

    private fun currentIsFav(): Boolean {
        val id = session?.player?.currentMediaItem?.mediaId ?: return false
        return Favorites.isFav(this, id)
    }

    @Suppress("DEPRECATION")
    private fun favButton(isFav: Boolean): CommandButton =
        CommandButton.Builder()
            .setDisplayName(if (isFav) "Aus Favoriten entfernen" else "Zu Favoriten")
            .setIconResId(if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            .setSessionCommand(favCommand)
            .build()

    @Suppress("DEPRECATION")
    private fun nextButton(): CommandButton =
        CommandButton.Builder()
            .setDisplayName("Nächster Hit")
            .setIconResId(R.drawable.ic_next)
            .setSessionCommand(nextCommand)
            .build()

    private var widgetArtUrl: String? = null

    /** Bild fürs Widget laden (nur wenn es sich geändert hat). */
    private fun updateWidgetArt(url: String?) {
        if (url == widgetArtUrl) return
        widgetArtUrl = url
        if (url.isNullOrBlank()) { RadioWidget.art = null; RadioWidget.refresh(this); return }
        scope.launch {
            val req = coil.request.ImageRequest.Builder(this@PlaybackService).data(url).size(256).allowHardware(false).build()
            val bmp = (coil.Coil.imageLoader(this@PlaybackService).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (url == widgetArtUrl) { RadioWidget.art = bmp; RadioWidget.refresh(this@PlaybackService) }
        }
    }

    private var heardTitle = ""
    private var heardStation = ""
    private val heardRunnable = Runnable {
        val p = session?.player ?: return@Runnable
        val now = p.mediaMetadata.title?.toString()?.trim().orEmpty()
        if (p.isPlaying && Text.clean(now) == Text.clean(heardTitle)) Heard.add(this, Text.clean(heardTitle), heardStation)
    }

    private val stationLogos = HashMap<String, android.net.Uri?>()

    /** Lied läuft: dessen Cover zeigen. Werbung/Moderation: wieder das Senderlogo. */
    private fun updateArtwork(item: MediaItem, title: String) {
        val id = item.mediaId
        if (!stationLogos.containsKey(id)) stationLogos[id] = item.mediaMetadata.artworkUri
        scope.launch {
            val cover = if (LiveIndex.isMusic(title)) Covers.load(title)?.artwork?.let { android.net.Uri.parse(it) } else null
            val target = cover ?: stationLogos[id]
            val p = session?.player ?: return@launch
            val cur = p.currentMediaItem ?: return@launch
            if (cur.mediaId != id || cur.mediaMetadata.artworkUri == target) return@launch
            sameItemReplace = true
            p.replaceMediaItem(p.currentMediaItemIndex,
                cur.buildUpon().setMediaMetadata(cur.mediaMetadata.buildUpon().setArtworkUri(target).build()).build())
            // Falls kein Wechsel-Ereignis kam: Merker nicht stehen lassen (sonst würde ein echter Senderwechsel übersehen)
            sleepHandler.postDelayed({ sameItemReplace = false }, 500)
        }
    }

    /** Beim Bild-Tausch nicht so tun, als wäre ein neuer Sender gestartet. */
    private var sameItemReplace = false

    private fun isStory(id: String?) = id != null && (id.startsWith("pc:") || id.startsWith("ia:") || id.startsWith("lv:"))

    private fun seekButton(back: Boolean): CommandButton =
        CommandButton.Builder()
            .setDisplayName(if (back) "30 s zurück" else "30 s vor")
            .setIconResId(if (back) R.drawable.ic_replay30 else R.drawable.ic_forward30)
            .setPlayerCommand(if (back) Player.COMMAND_SEEK_BACK else Player.COMMAND_SEEK_FORWARD)
            .build()

    private fun buttons(): ImmutableList<CommandButton> = when {
        hitMix.isActive -> ImmutableList.of(nextButton(), favButton(currentIsFav()))
        isStory(session?.player?.currentMediaItem?.mediaId) -> ImmutableList.of(seekButton(true), seekButton(false), favButton(currentIsFav()))
        else -> ImmutableList.of(favButton(currentIsFav()))
    }

    @Suppress("DEPRECATION")
    private fun updateFavButton() {
        session?.setCustomLayout(buttons())
    }

    // ---------- Listen ----------

    private fun remember(items: List<MediaItem>) {
        items.forEach { itemCache[it.mediaId] = it }
        val tracks = items.filter { !it.mediaId.startsWith("st:") && !it.mediaId.startsWith("hitmix:") }
        if (tracks.size > 1) tracks.forEach { listContext[it.mediaId] = tracks }
        val stations = items.filter { it.mediaId.startsWith("st:") }
        if (stations.size > 1) stations.forEach { listContext[it.mediaId] = stations }
    }

    private suspend fun kidsEntries(): List<Entry> = coroutineScope {
        val stations = async { StationRepository.kids(applicationContext) }
        val songs = async { FreeMusic.kids() }
        stations.await().map { it.toEntry() } + songs.await()
    }

    /** "Spiel X": erst Sendername, dann Radio wo es gerade läuft, dann freie Musik. */
    private suspend fun findForVoice(query: String): MediaItem? {
        val q = query.lowercase()
        val theme = when {
            Regex("weihnacht|christmas|xmas").containsMatchIn(q) -> "xmas"
            Regex("kinderlieder|kindermusik|kinder musik").containsMatchIn(q) -> "kids"
            Regex("^(hits|charts|aktuelle hits|hit mix|hitmix|nur hits)$").containsMatchIn(q.trim()) -> "charts"
            else -> Genres.all.firstOrNull { g -> g.key != "charts" && q.trim() == g.name.lowercase() }?.key
        }
        if (theme != null) {
            hitMix.start(if (theme == "charts") null else theme)?.let { updateFavButton(); return it }
        }
        val stations = StationRepository.load(applicationContext)
        val words = Scanner.words(query)
        stations.firstOrNull { Scanner.matches(it.name, words) }?.let { return it.toEntry().toMediaItem() }
        val hit = withTimeoutOrNull(20_000) {
            Scanner.scan(stations, query, stopAfterFirst = true).firstOrNull()
        }
        if (hit != null) {
            Scanner.lastHits = listOf(hit)
            return hit.station.toEntry(hit.title).toMediaItem()
        }
        val tracks = FreeMusic.search(query).map { it.toMediaItem() }
        remember(tracks)
        return tracks.firstOrNull()
    }

    /**
     * Bei Radiosendern den Songtitel weglassen: Sonst bliebe der Titel stehen,
     * der beim Antippen lief. So kommt der Titel live aus dem Stream.
     */
    private fun liveTitle(item: MediaItem?): MediaItem? {
        if (item == null || !item.mediaId.startsWith("st:")) return item
        val m = item.mediaMetadata
        val name = m.extras?.getString("name") ?: m.artist?.toString() ?: m.title?.toString()
        return item.buildUpon()
            .setMediaMetadata(m.buildUpon().setTitle(null).setDisplayTitle(null).setArtist(name).build())
            .build()
    }

    private suspend fun resolve(item: MediaItem): MediaItem? = liveTitle(resolveRaw(item))

    /** Für Android Auto: Cover der laufenden Lieder holen (höchstens 4 Sekunden warten). */
    private suspend fun withCovers(list: List<Entry>): List<MediaItem> {
        withTimeoutOrNull(4_000) {
            coroutineScope { list.take(30).map { e -> async { Covers.load(e.title) } }.awaitAll() }
        }
        return list.map { e ->
            val d = Covers.dress(e)
            // Abspielen als Sender, angezeigt mit Lied-Cover
            e.copy(title = d.title, artwork = d.artwork).toMediaItem()
        }
    }

    private suspend fun resolveRaw(item: MediaItem): MediaItem? {
        val query = item.requestMetadata.searchQuery
        val uri = item.requestMetadata.mediaUri
        return when {
            !query.isNullOrBlank() -> findForVoice(query)
            item.localConfiguration != null -> item
            uri != null -> item.buildUpon().setUri(uri).build()
            else -> itemCache[item.mediaId]
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        @Suppress("DEPRECATION")
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon().add(favCommand).add(sleepCommand).add(hitMixCommand).add(nextCommand)
                        .add(SessionCommand(CMD_FADEIN, Bundle.EMPTY)).add(SessionCommand(CMD_BEDTIME, Bundle.EMPTY))
                        .add(SessionCommand(CMD_ENDLESS, Bundle.EMPTY)).build()
                )
                .setCustomLayout(buttons())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_HITMIX) {
                if (args.getBoolean("stop", false)) {
                    hitMix.stop()
                } else {
                    val genre = args.getString("genre")?.takeIf { it.isNotBlank() }
                    scope.launch {
                        val item = hitMix.start(genre)
                        val p = session?.player
                        if (item != null && p != null) { p.setMediaItem(item); p.prepare(); p.play() }
                        updateFavButton()
                    }
                }
                updateFavButton()
            }
            if (customCommand.customAction == CMD_NEXT) {
                val p = session.player
                if (hitMix.isActive) {
                    @Suppress("DEPRECATION")
                    return scope.future {
                        SessionResult(if (hitMix.next()) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_UNKNOWN)
                    }
                }
                if (p.hasNextMediaItem()) p.seekToNextMediaItem()
            }
            if (customCommand.customAction == CMD_SLEEP) {
                startSleepTimer(args.getInt("min", 0))
            }
            if (customCommand.customAction == CMD_ENDLESS) {
                endlessTag = args.getString("tag")
                endlessOffset = 200
            }
            if (customCommand.customAction == CMD_BEDTIME) {
                bedtime = true
                startSleepTimer(args.getInt("min", 45))
            }
            if (customCommand.customAction == CMD_FADEIN) {
                session.player.volume = 0.15f
                sleepHandler.removeCallbacks(fadeInRunnable)
                sleepHandler.postDelayed(fadeInRunnable, 4_000)
            }
            if (customCommand.customAction == favCommand.customAction) {
                session.player.currentMediaItem?.let { Entry.fromMediaItem(it) }?.let {
                    Favorites.toggle(this@PlaybackService, it)
                }
                updateFavButton()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1) // Ordner als Liste
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 2)  // Sender/Lieder als Kacheln
            }
            val out = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT_ID, "Hitradar"), out))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items: List<MediaItem> = when (parentId) {
                // Android Auto zeigt bis zu 4 Ordner als Reiter: übersichtlich wie in der App
                ROOT_ID -> listOf(
                    folderItem(FORYOU_ID, "Für dich"),
                    folderItem(RADIO_ID, "Radio"),
                    folderItem(MUSIC_ID, "Musik"),
                    folderItem(STORIES_ID, "Hörspiele"),
                )
                FORYOU_ID -> listOf(hitMixItem(null, "⚡ Hit-Mix – nur Hits")) +
                    (if (Favorites.all(applicationContext).count { it.kind == Kind.STATION } >= 2)
                        listOf(hitMixItem("favs", "⚡ Lieblingssender-Mix")) else emptyList()) +
                    Moments.now().filter { it.action == Moments.Action.HITMIX }.map { hitMixItem(it.genreKey, "⚡ " + it.title) } +
                    listOf(folderItem(FAV_ID, "⭐ Favoriten"), folderItem(RECENT_ID, "🕘 Zuletzt gehört"))
                RADIO_ID -> listOf(
                    folderItem(LIVE_ID, "🔴 Jetzt im Radio"),
                    folderItem(CHARTS_LIVE_ID, "🔥 Charts gerade im Radio"),
                    folderItem(RADIOCHARTS_ID, "📊 Meistgespielt im Radio"),
                    folderItem(POPULAR_ID, "📻 Beliebte Sender"),
                    folderItem(HITS_ID, "🔎 Letzte Suche"),
                )
                MUSIC_ID -> listOf(
                    folderItem(FREE_ID, "🎵 Freie Musik – Charts"),
                    folderItem(FREEGENRES_ID, "♾️ Freie Musik endlos"),
                    folderItem(KIDS_ID, "🧸 Kinderlieder"),
                )
                FAV_ID -> Favorites.all(applicationContext).map { it.toMediaItem() }
                MOMENTS_ID -> Moments.now().filter { it.action == Moments.Action.HITMIX }
                    .map { hitMixItem(it.genreKey, "⚡ " + it.title) }
                RECENT_ID -> Recent.all(applicationContext).map { it.toMediaItem() }
                STORY_CONT_ID -> Recent.all(applicationContext)
                    .filter { it.id.startsWith("pc:") || it.id.startsWith("ia:") }.map { it.toMediaItem() }
                LIVE_ID -> {
                    // Vom Server: alles, was gerade an Musik läuft, frisch angefangene Lieder zuerst
                    if (!Radar.sync()) {
                        val stations = StationRepository.load(applicationContext, big = false).take(80)
                        withTimeoutOrNull(15_000) { LiveIndex.refresh(stations) }
                    }
                    val list = withContext(Dispatchers.Default) { LiveIndex.topMusic().take(60) }
                    withCovers(list.map { it.station.toEntry(it.title) })
                }
                RADIOCHARTS_ID -> {
                    // Mix aus den meistgespielten Liedern + die davon, die gerade laufen
                    Radar.sync()
                    val charts = Radar.charts().take(50)
                    val live = charts.mapNotNull { (t, _) ->
                        val a = t.substringBefore(" - ").substringBefore(" – ").trim()
                        val ti = t.substringAfter(" - ").substringAfter(" – ").trim()
                        LiveIndex.findSong(ti, a)
                    }.distinctBy { it.station.id }
                    listOf(hitMixItem("radiocharts", "⚡ Mix: Meistgespielt im Radio")) +
                        withCovers(live.map { it.station.toEntry(it.title) })
                }
                CHARTS_LIVE_ID -> {
                    val charts = Deezer.topGermany()
                    if (!Radar.sync()) {
                        val stations = StationRepository.load(applicationContext, big = false).take(150)
                        withTimeoutOrNull(20_000) { LiveIndex.refresh(stations) }
                    }
                    withCovers(charts.mapNotNull { LiveIndex.findSong(it.title, it.subtitle) }
                        .distinctBy { it.station.id }
                        .map { it.station.toEntry(it.title) })
                }
                HITS_ID -> Scanner.lastHits.map { it.station.toEntry(it.title).toMediaItem() }
                KIDS_ID -> listOf(hitMixItem("kids", "⚡ Kinder-Hit-Mix")) + kidsEntries().map { it.toMediaItem() }
                FREE_ID -> FreeMusic.charts().map { it.toMediaItem() }
                POPULAR_ID -> StationRepository.load(applicationContext).take(50).map { it.toEntry().toMediaItem() }
                FREEGENRES_ID -> FREE_TAGS.map { (name, tag) -> folderItem("ftag:$tag", name) }
                STORIES_ID -> listOf(folderItem(STORY_CONT_ID, "▶ Weiterhören")) +
                    Podcasts.all.map { folderItem("cat:" + it.key, (if (it.kids) "🧸 " else "") + it.name) }
                else -> when {
                    parentId.startsWith("cat:") -> {
                        val cat = Podcasts.all.firstOrNull { it.key == parentId.removePrefix("cat:") }
                        cat?.let { Podcasts.shows(it) }.orEmpty().map { show ->
                            shows["show:" + show.id] = show
                            folderItem("show:" + show.id, show.name)
                        }
                    }
                    parentId.startsWith("ftag:") -> {
                        val tag = parentId.removePrefix("ftag:")
                        endlessTag = tag; endlessOffset = 200
                        Jamendo.many(tag).map { it.toMediaItem() }
                    }
                    parentId.startsWith("show:") ->
                        shows[parentId]?.let { Podcasts.episodes(it) }.orEmpty().map { it.toMediaItem() }
                    else -> emptyList()
                }
            }
            remember(items)
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = itemCache[mediaId]
            @Suppress("DEPRECATION")
            val result: LibraryResult<MediaItem> =
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            return Futures.immediateFuture(result)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            searchResults[query] = emptyList()
            scope.launch {
                val stations = StationRepository.load(applicationContext)
                val words = Scanner.words(query)
                val radioHits = Collections.synchronizedList(mutableListOf<MediaItem>())
                val named = stations.filter { Scanner.matches(it.name, words) }.take(10)
                    .map { it.toEntry().toMediaItem() }
                var free: List<MediaItem> = emptyList()

                fun publish() {
                    val all = synchronized(radioHits) { radioHits.toList() } + named + free
                    remember(all)
                    searchResults[query] = all
                    session.notifySearchResultChanged(browser, query, all.size, params)
                }
                publish()
                launch {
                    free = FreeMusic.search(query).map { it.toMediaItem() }
                    publish()
                }
                val hits = Scanner.scan(stations, query) { hit ->
                    radioHits.add(hit.station.toEntry(hit.title).toMediaItem())
                    scope.launch { publish() }
                }
                Scanner.lastHits = hits
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(searchResults[query].orEmpty()), params)
            )

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.mapNotNull { resolve(it) }.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val last = Recent.all(applicationContext).firstOrNull()
            val item = last?.toMediaItem()?.let { liveTitle(it) }
                ?: hitMix.start(null)
            val pos = last?.let { Positions.get(this@PlaybackService, it.id) }?.takeIf { it > 0 } ?: C.TIME_UNSET
            MediaSession.MediaItemsWithStartPosition(listOfNotNull(item), if (item == null) C.INDEX_UNSET else 0, pos)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            // Hit-Mix gewählt?
            val hm = mediaItems.firstOrNull { it.mediaId.startsWith("hitmix:") }
            if (hm != null) {
                val first = hitMix.start(hm.mediaId.removePrefix("hitmix:").ifBlank { null })
                updateFavButton()
                return@future MediaSession.MediaItemsWithStartPosition(
                    listOfNotNull(first), if (first == null) C.INDEX_UNSET else 0, C.TIME_UNSET
                )
            }
            bedtime = false
            // Etwas anderes gewählt: Hit-Mix beenden
            if (hitMix.isActive) { hitMix.stop(); updateFavButton() }
            val resolved = mediaItems.mapNotNull { resolve(it) }
            // Ein einzelnes freies Lied aus einer Liste: ganze Liste laden, damit "Weiter" geht
            if (resolved.size == 1) {
                val list = listContext[resolved[0].mediaId]
                if (list != null) {
                    val idx = list.indexOfFirst { it.mediaId == resolved[0].mediaId }.coerceAtLeast(0)
                    val saved = Positions.get(this@PlaybackService, resolved[0].mediaId).takeIf { it > 0 } ?: C.TIME_UNSET
                    return@future MediaSession.MediaItemsWithStartPosition(list.map { liveTitle(it) ?: it }, idx, saved)
                }
            }
            if (resolved.size == 1 && resolved[0].mediaId.startsWith("st:")) {
                val chosen = resolved[0]
                // Bekannte Lieder zuerst (vorberechnet), keine Sender mit Werbung beim Umschalten
                val others = withContext(Dispatchers.Default) {
                    LiveIndex.topMusic()
                        .filter { "st:" + it.station.id != chosen.mediaId }
                        .filter { !Ads.likely(this@PlaybackService, it.station.id, it.station.url) }
                        .take(30)
                }
                    .map { liveTitle(it.station.toEntry(it.title).toMediaItem()) ?: it.station.toEntry(it.title).toMediaItem() }
                if (others.isNotEmpty()) {
                    val before = others.drop(others.size / 2).reversed()
                    val after = others.take(others.size / 2)
                    return@future MediaSession.MediaItemsWithStartPosition(before + chosen + after, before.size, C.TIME_UNSET)
                }
            }
            val index = if (resolved.size == mediaItems.size) startIndex else 0
            // Geschichten dort weiterspielen, wo man aufgehört hat
            val startItem = resolved.getOrNull(if (index == C.INDEX_UNSET) 0 else index)
            val pos = if (startPositionMs == C.TIME_UNSET || startPositionMs == 0L) {
                startItem?.let { Positions.get(this@PlaybackService, it.mediaId) }?.takeIf { it > 0 } ?: startPositionMs
            } else startPositionMs
            MediaSession.MediaItemsWithStartPosition(resolved, index, pos)
        }
    }
}
