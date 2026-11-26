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
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import com.google.android.gms.cast.framework.CastContext
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
import kotlinx.coroutines.withTimeout
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
    // Fehler in Hintergrund-Aufgaben (z. B. kein Netz) dürfen die App nie abstürzen lassen
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("Hitradar", "Hintergrundfehler", e) })
    // Zwischenspeicher mit Obergrenze: älteste Einträge fliegen raus (sonst wächst der Speicher bei langer Fahrt)
    private val itemCache = lru<String, MediaItem>(1500)
    private val searchResults = lru<String, List<MediaItem>>(5)
    private var searchJob: kotlinx.coroutines.Job? = null
    // Für freie Lieder: die ganze Liste merken, damit man in Android Auto weiterspringen kann
    private val listContext = lru<String, List<MediaItem>>(1500)
    private val favCommand = SessionCommand("FAV_TOGGLE", Bundle.EMPTY)
    private val sleepCommand = SessionCommand(CMD_SLEEP, Bundle.EMPTY)
    private val hitMixCommand = SessionCommand(CMD_HITMIX, Bundle.EMPTY)
    private val nextCommand = SessionCommand(CMD_NEXT, Bundle.EMPTY)
    private val shows = lru<String, Entry>(300)
    private val posRunnable = object : Runnable {
        override fun run() {
            savePosition()
            // Nur solange etwas läuft: pausiert muss das Handy nicht alle 15 s aufwachen
            if (session?.player?.isPlaying == true) sleepHandler.postDelayed(this, 15_000)
        }
    }

    /** Wiedergabe läuft (wieder): Position merken und Werbe-Prüfung starten. */
    private fun startPlayingChecks() {
        sleepHandler.removeCallbacks(posRunnable)
        sleepHandler.postDelayed(posRunnable, 15_000)
        sleepHandler.removeCallbacks(adMuteCheck)
        sleepHandler.post(adMuteCheck)
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
            try {
                Jamendo.highQuality = Settings.highQuality(this@PlaybackService)
                val more = Jamendo.page(tag, endlessOffset)
                endlessOffset += 200
                if (more.isNotEmpty() && endlessTag == tag) p.addMediaItems(more.map { it.toMediaItem() })
            } finally {
                endlessLoading = false
            }
        }
    }
    /** Letztes freies Lied ist vorbei: ähnliche Lieder (gleicher Künstler, sonst Charts) anhängen und weiterspielen. */
    private fun autoContinue() {
        val p = session?.player ?: return
        val last = p.currentMediaItem ?: return
        val id = last.mediaId
        if (!(id.startsWith("jm:") || id.startsWith("au:")) || hitMix.isActive) return
        if (last.mediaMetadata.extras?.getBoolean("preview", false) == true) return
        scope.launch {
            val have = (0 until p.mediaItemCount).mapTo(HashSet()) { p.getMediaItemAt(it).mediaId }
            val artist = last.mediaMetadata.artist?.toString()?.substringBefore(" · ")?.trim().orEmpty()
            val more = (if (artist.length >= 2) FreeMusic.search(artist) else emptyList()) + FreeMusic.charts()
            val items = more.filter { (it.id.startsWith("jm:") || it.id.startsWith("au:")) && !it.preview && it.id !in have }
                .distinctBy { it.id }.take(30).map { it.toMediaItem() }
            val now = session?.player ?: return@launch
            // Inzwischen etwas anderes gewählt oder gestoppt: nichts tun
            if (items.isEmpty() || now.playbackState != Player.STATE_ENDED || now.currentMediaItem?.mediaId != id) return@launch
            now.addMediaItems(items)
            now.seekTo(now.currentMediaItemIndex + 1, 0L)
            now.prepare(); now.play()
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
        ArtProvider.init(this)
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
                // Liste mit freien Liedern zu Ende: wie ein normaler Player mit passender Musik weitermachen
                if (state == Player.STATE_ENDED && !bedtime) autoContinue()
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
                val fav = item?.let { Favorites.isFav(this@PlaybackService, it.mediaId) } == true
                // Nur bei echter Änderung (sonst bei jedem Player-Ereignis Speichern + Widget neu zeichnen)
                val key = "$title|$sub|${player.isPlaying}|$fav"
                if (key != widgetKey) {
                    widgetKey = key
                    RadioWidget.update(this@PlaybackService, title, sub, player.isPlaying, fav)
                }
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
                onStreamTitle(id, t)
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    Settings.prefs(this@PlaybackService).edit().putLong("user_stopped", if (playWhenReady) 0L else System.currentTimeMillis()).apply()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition() else startPlayingChecks()
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
                // Hit-Mix: in der Wiedergabeliste angetippt oder "Weiter" im Auto
                if (hitMix.isActive && mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                    hitMix.onJump(mediaItem.mediaId)
                stationStart = 0L; musicSeen = false; nonMusicSeen = false
                NowSong.tune(mediaItem?.mediaId?.takeIf { it.startsWith("st:") }?.removePrefix("st:"))
                adMuted = false
                if (exo?.volume == 0f) exo?.volume = 1f
                if (exo?.isPlaying == true) { sleepHandler.removeCallbacks(adMuteCheck); sleepHandler.post(adMuteCheck) }
                // Tempo gilt nur für Hörspiele und Hörbücher
                if (!isStory(mediaItem?.mediaId)) session?.player?.setPlaybackSpeed(1f)
                if (mediaItem?.mediaId?.startsWith("jm:") == true) checkEndless() else if (mediaItem != null) endlessTag = null
                updateFavButton()
                // "Zuletzt gehört": erst nach 20 Sekunden (nicht beim schnellen Durchschalten),
                // Sender ohne das damals laufende Lied speichern (sonst steht dort ein altes Lied)
                sleepHandler.removeCallbacks(recentRunnable)
                recentItem = mediaItem
                if (mediaItem != null) sleepHandler.postDelayed(recentRunnable, 20_000)
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
            // Radio: Laufleiste wie bei einem normalen Lied (Start und Länge bekannt)
            private fun song() = currentMediaItem?.mediaId?.startsWith("st:") == true && NowSong.known()
            override fun getDuration() = if (song()) NowSong.durMs else super.getDuration()
            override fun getContentDuration() = if (song()) NowSong.durMs else super.getContentDuration()
            override fun getCurrentPosition() = if (song()) NowSong.elapsed().coerceAtMost(NowSong.durMs) else super.getCurrentPosition()
            override fun getContentPosition() = if (song()) NowSong.elapsed().coerceAtMost(NowSong.durMs) else super.getContentPosition()
            override fun getBufferedPosition() = if (song()) NowSong.elapsed().coerceAtMost(NowSong.durMs) else super.getBufferedPosition()
            override fun getContentBufferedPosition() = if (song()) NowSong.elapsed().coerceAtMost(NowSong.durMs) else super.getContentBufferedPosition()
            override fun isCurrentMediaItemLive() = if (song()) false else super.isCurrentMediaItemLive()
        }
        localPlayer = shown
        session = MediaLibrarySession.Builder(this, shown, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
        setupCast()
        Favorites.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(settingsListener)
        // Ein alter Hit-Mix von vor einem Neustart läuft nicht mehr
        HitMix.prefs(this).edit().clear().apply()
        // Schlaf-Timer nach Neustart des Dienstes wiederherstellen
        val left = Settings.sleepEnd(this) - System.currentTimeMillis()
        if (left > 0) sleepHandler.postDelayed(sleepRunnable, (left - 60_000L).coerceAtLeast(0L)) else Settings.setSleepEnd(this, 0L)
    }

    /** Selbst gestoppt (in den letzten 12 h) und der Start kommt nicht von der App, Android Auto oder der Benachrichtigung? */
    private fun blockAutoStart(c: MediaSession.ControllerInfo): Boolean {
        val stopped = Settings.prefs(this).getLong("user_stopped", 0L)
        if (stopped == 0L || System.currentTimeMillis() - stopped > 12 * 3600_000L) return false
        val pkg = c.packageName
        return pkg != packageName && pkg != "com.android.systemui" && !pkg.startsWith("com.google.android.projection") &&
            pkg != "com.google.android.apps.automotive.media"
    }

    // ---------------- Liedposition, Lied-Ende, Werbung stumm ----------------
    private var adMuted = false

    /** Titel direkt aus dem Stream (genau zum Hören passend, ohne Verzögerung). */
    private fun onStreamTitle(stationId: String, raw: String) {
        val now = System.currentTimeMillis()
        val clean = Text.clean(raw)
        val music = LiveIndex.isMusic(clean) && !Ads.isAd(clean)
        if (music) {
            if (clean != NowSong.title) {
                val first = NowSong.title == null
                NowSong.title = clean
                // Erster Titel nach dem Einschalten: Startzeit vom Server, sonst unbekannt. Wechsel beim Hören: jetzt.
                val norm = Scanner.normalize(clean)
                NowSong.startMs = if (first) LiveIndex.get(stationId)?.takeIf { it.normalized == norm || LiveIndex.Song(
                    Entry("", Kind.TRACK, clean.substringAfter(" - ").substringAfter(" – ").trim(),
                        clean.substringBefore(" - ").substringBefore(" – ").trim(), "", "", "")).matches(it.normalized) }
                    ?.takeIf { LiveIndex.sinceKnown(it, now) }?.since?.takeIf { it < now && now - it < 9 * 60_000L } ?: 0L
                else now
                NowSong.durMs = Covers.get(clean)?.durMs ?: 0L
            }
            NowSong.localAt = now
            LiveIndex.titleFromStream(stationId, clean)
        } else if (NowSong.title != null) {
            // Lied vorbei: jetzt Moderation, Jingle oder Werbung
            NowSong.title = null; NowSong.startMs = 0L; NowSong.durMs = 0L; NowSong.localAt = now
        }
        if (hitMix.isActive) scope.launch { hitMix.onStreamTitle(stationId, clean, music) }
    }

    /** Bekannte Vorab-Werbung eines Senders: stumm schalten, bis Musik kommt (höchstens die gelernte Dauer + 10 s). */
    private val adMuteCheck = object : Runnable {
        override fun run() {
            val p = exo ?: return
            // Nur prüfen, solange es etwas bringt: es läuft, und die Werbung am Senderanfang (höchstens ~3 Min.) ist noch möglich.
            // Startet wieder beim nächsten Sender bzw. beim Weiterspielen.
            if (p.isPlaying && (adMuted || NowSong.sinceTune() < 195_000L)) sleepHandler.postDelayed(this, 1_000)
            val id = NowSong.stationId ?: return
            if (!Settings.muteAds(this@PlaybackService) || session?.player !== localPlayer || !p.isPlaying) return
            val known = Ads.duration(this@PlaybackService, id)
            val since = NowSong.sinceTune()
            // Bekannte Werbung: ihre Dauer lang stumm (auch wenn der Sender schon den Liedtitel schickt),
            // ohne Titel bis zu 10 s länger, falls der Spot länger dauert
            val shouldMute = known > 0 && (since < known || (NowSong.title == null && since < known + 10_000))
            if (shouldMute && !adMuted) { adMuted = true; p.volume = 0f }
            else if (!shouldMute && adMuted) { adMuted = false; p.volume = 1f }
        }
    }

    // ---------------- Casten (Google Home, Nest, Chromecast) ----------------
    private var localPlayer: Player? = null
    private var castWrapper: CastWrapper? = null

    /** Der Lautsprecher bekommt nur abspielbare Adressen (https bzw. über den Server). */
    @OptIn(UnstableApi::class)
    private inner class CastWrapper(cp: CastPlayer) : ForwardingPlayer(cp) {
        val originals = HashMap<String, MediaItem>()
        private fun conv(items: List<MediaItem>) = items.mapNotNull { o -> Cast.convert(o)?.also { originals[o.mediaId] = o } }
        override fun setMediaItem(mediaItem: MediaItem) = super.setMediaItems(conv(listOf(mediaItem)))
        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = super.setMediaItems(conv(listOf(mediaItem)), 0, startPositionMs)
        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = super.setMediaItems(conv(listOf(mediaItem)), resetPosition)
        override fun setMediaItems(mediaItems: List<MediaItem>) = super.setMediaItems(conv(mediaItems))
        override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = super.setMediaItems(conv(mediaItems), resetPosition)
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
            super.setMediaItems(conv(mediaItems), startIndex, startPositionMs)
        override fun addMediaItem(mediaItem: MediaItem) = super.addMediaItems(conv(listOf(mediaItem)))
        override fun addMediaItem(index: Int, mediaItem: MediaItem) = super.addMediaItems(index, conv(listOf(mediaItem)))
        override fun addMediaItems(mediaItems: List<MediaItem>) = super.addMediaItems(conv(mediaItems))
        override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = super.addMediaItems(index, conv(mediaItems))
        // Nur Cover-Tausch: auf dem Lautsprecher nicht neu laden
        override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
            if (getMediaItemAt(index).mediaId == mediaItem.mediaId) return
            conv(listOf(mediaItem)).firstOrNull()?.let { super.replaceMediaItem(index, it) }
        }
        override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
            super.replaceMediaItems(fromIndex, toIndex, conv(mediaItems))
        override fun getMediaMetadata(): MediaMetadata {
            val m = super.getMediaMetadata()
            val t = m.title?.toString() ?: return m
            val c = Text.clean(t)
            return if (c == t) m else m.buildUpon().setTitle(c).build()
        }
    }

    @OptIn(UnstableApi::class)
    private fun setupCast() {
        val ctx = try {
            @Suppress("DEPRECATION") CastContext.getSharedInstance(this)
        } catch (_: Exception) { return }   // ohne Google-Play-Dienste kein Casten
        val cp = CastPlayer(ctx)
        castWrapper = CastWrapper(cp)
        cp.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Sender geht auf dem Lautsprecher nicht: im Hit-Mix einfach weiter
                if (hitMix.isActive) scope.launch { hitMix.next() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startPlayingChecks() else savePosition()
            }
        })
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchPlayer(true)
            override fun onCastSessionUnavailable() = switchPlayer(false)
        })
        if (cp.isCastSessionAvailable) switchPlayer(true)
    }

    /** Wiedergabe zwischen Handy und Lautsprecher umziehen (gleicher Sender bzw. gleiche Stelle). */
    private fun switchPlayer(toCast: Boolean) {
        val s = session ?: return
        val target = (if (toCast) castWrapper else localPlayer) ?: return
        val old = s.player
        if (old === target) return
        val back = castWrapper?.originals
        val items = (0 until old.mediaItemCount).map { i ->
            old.getMediaItemAt(i).let { if (!toCast) back?.get(it.mediaId) ?: it else it }
        }
        val index = old.currentMediaItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val live = items.getOrNull(index)?.mediaId?.startsWith("st:") == true
        val pos = old.currentPosition
        val play = old.playWhenReady || toCast
        Cast.active = toCast
        Cast.deviceName = if (toCast) try {
            @Suppress("DEPRECATION") CastContext.getSharedInstance(this).sessionManager.currentCastSession?.castDevice?.friendlyName
        } catch (_: Exception) { null } else null
        old.stop()
        s.player = target
        if (items.isNotEmpty()) {
            if (live) target.setMediaItems(items, index, if (toCast) 0L else C.TIME_UNSET) else target.setMediaItems(items, index, pos)
            target.prepare()
            target.playWhenReady = play
        }
        if (!toCast) castWrapper?.originals?.clear()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        savePosition()
        sleepHandler.removeCallbacks(posRunnable)
        sleepHandler.removeCallbacks(adMuteCheck)
        hitMix.stop()
        sleepHandler.removeCallbacks(sleepRunnable)
        Settings.setSleepEnd(this, 0L)
        Favorites.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        sleepHandler.removeCallbacks(fadeInRunnable)
        try { dynamics?.release() } catch (_: Exception) {}
        session?.run {
            if (player !== localPlayer) localPlayer?.release()
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
    private var widgetKey = ""

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

    private var recentItem: MediaItem? = null
    private val recentRunnable = Runnable {
        val item = recentItem ?: return@Runnable
        val p = session?.player ?: return@Runnable
        if (p.currentMediaItem?.mediaId != item.mediaId || !p.isPlaying) return@Runnable
        val e = Entry.fromMediaItem(item) ?: return@Runnable
        Recent.add(this, if (e.kind == Kind.STATION) e.copy(title = e.name, subtitle = "Internetradio") else e)
    }

    private var heardTitle = ""
    private var heardStation = ""
    private val heardRunnable = Runnable {
        val p = session?.player ?: return@Runnable
        val now = p.mediaMetadata.title?.toString()?.trim().orEmpty()
        if (p.isPlaying && Text.clean(now) == Text.clean(heardTitle)) {
            Heard.add(this, Text.clean(heardTitle), heardStation)
            Radar.event("heard", Text.clean(heardTitle))
        }
    }

    private val stationLogos = HashMap<String, android.net.Uri?>()

    /** Lied läuft: dessen Cover zeigen. Werbung/Moderation: wieder das Senderlogo. */
    private fun updateArtwork(item: MediaItem, title: String) {
        val id = item.mediaId
        if (stationLogos.size > 300) stationLogos.clear()
        if (!stationLogos.containsKey(id)) stationLogos[id] = item.mediaMetadata.artworkUri
        scope.launch {
            val info = if (LiveIndex.isMusic(title)) Covers.load(title) else null
            val cover = info?.artwork?.let { android.net.Uri.parse(it) }
            // Liedlänge für Laufleiste und Lied-Ende
            var durNew = false
            if (info != null && info.durMs > 0 && NowSong.title == Text.clean(title) && NowSong.durMs != info.durMs) {
                NowSong.durMs = info.durMs; durNew = true
            }
            val target = cover ?: stationLogos[id]
            val p = session?.player ?: return@launch
            val cur = p.currentMediaItem ?: return@launch
            if (cur.mediaId != id || (cur.mediaMetadata.artworkUri == target && !durNew)) return@launch
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
        val tracks = items.filter {
            it.mediaMetadata.isPlayable == true && !it.mediaId.startsWith("st:") &&
                !it.mediaId.startsWith("hitmix:") && !it.mediaId.startsWith("news:")
        }
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
        // 1. Läuft das Lied gerade irgendwo? (Server-Stand, sofort)
        Radar.sync()
        val words = Scanner.words(query)
        withContext(Dispatchers.Default) {
            LiveIndex.find(query).filter { LiveIndex.isMusic(it.title) && !LiveIndex.endingSoon(it) }.maxByOrNull { it.since }
        }?.let {
            return liveTitle(it.station.toEntry(it.title).toMediaItem())
        }
        // 2. Ein Sender mit dem Namen (alle ~50.000 auf dem Server)
        Radar.stations(query)?.firstOrNull()?.let { return it.toMediaItem() }
        val stations = StationRepository.load(applicationContext)
        withContext(Dispatchers.Default) { stations.firstOrNull { Scanner.matches(it.name, words) } }?.let { return it.toEntry().toMediaItem() }
        // 3. Ohne Server: selbst kurz suchen
        val hit = if (Radar.ok) null else withTimeoutOrNull(8_000) {
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

    /** Neueste Nachrichtenfolge (Tagesschau in 100 Sekunden bzw. Deutschlandfunk-Nachrichten). Notfalls Deutschlandfunk live. */
    private suspend fun latestNews(key: String): MediaItem? {
        val term = if (key == "dlf") "Deutschlandfunk Nachrichten" else "tagesschau in 100 Sekunden"
        val ep = try {
            withTimeoutOrNull(8_000) {
                val show = Podcasts.search(term, limit = 3).firstOrNull()
                show?.let { Podcasts.episodes(it).lastOrNull() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        if (ep != null) return ep.toMediaItem()
        // Folge nicht erreichbar: Deutschlandfunk live (nie den Hit-Mix statt Nachrichten)
        val live = try { withTimeoutOrNull(5_000) { Radar.stations("Deutschlandfunk")?.firstOrNull() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        return live?.toMediaItem() ?: fallbackStation()
    }

    /** Irgendein Sender, auf dem gerade Musik läuft (Ersatz, damit nie "nicht geladen" kommt). Nie null. */
    private suspend fun anyMusicStation(): MediaItem =
        try { withTimeoutOrNull(10_000) { anyMusicStationRaw() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            ?: fallbackStation()

    /** Letzter Ausweg ohne Server und ohne gespeicherte Senderliste: fester Sender. */
    private fun fallbackStation(): MediaItem = Entry(
        "st:fallback-dlf", Kind.STATION, "Deutschlandfunk", "Internetradio",
        "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3", "", "Deutschlandfunk",
    ).toMediaItem().let { liveTitle(it) ?: it }

    private suspend fun anyMusicStationRaw(): MediaItem? {
        Radar.sync()
        val n = withContext(Dispatchers.Default) { LiveIndex.topMusic().firstOrNull { !Ads.likely(this@PlaybackService, it.station.id, it.station.url) } }
        return n?.let { liveTitle(it.station.toEntry(it.title).toMediaItem()) }
            ?: StationRepository.load(applicationContext, big = false).firstOrNull()?.toEntry()?.toMediaItem()
    }

    private var trafficCache: Pair<Long, List<MediaItem>>? = null

    /** Regionale Sender mit Verkehrsmeldungen (meist alle 30 Minuten und bei Staus sofort). */
    private suspend fun trafficStations(): List<MediaItem> {
        trafficCache?.let { (t, l) -> if (System.currentTimeMillis() - t < 24 * 3600_000L && l.isNotEmpty()) return l }
        val list = listOf(
            "Bayern 3" to "Bayern", "Antenne Bayern" to "Bayern", "SWR3" to "Baden-Württemberg, Rheinland-Pfalz",
            "hr3" to "Hessen", "WDR 2" to "Nordrhein-Westfalen", "NDR 2" to "Niedersachsen, Hamburg, Schleswig-Holstein, MV",
            "MDR JUMP" to "Sachsen, Sachsen-Anhalt, Thüringen", "Antenne Brandenburg" to "Berlin, Brandenburg",
            "SR 1" to "Saarland", "Bremen Vier" to "Bremen", "Hitradio Ö3" to "Österreich", "Radio SRF 1" to "Schweiz",
        )
        val items = coroutineScope {
            list.map { (name, region) ->
                async {
                    val e = Radar.stations(name)?.firstOrNull { Scanner.normalize(it.name).startsWith(Scanner.normalize(name)) }
                        ?: Radar.stations(name)?.firstOrNull()
                    e?.copy(title = name, subtitle = "Verkehr: $region")?.toMediaItem()
                }
            }.awaitAll().filterNotNull()
        }
        if (items.isNotEmpty()) trafficCache = System.currentTimeMillis() to items
        return items
    }

    private suspend fun resolveRaw(item: MediaItem): MediaItem? {
        val query = item.requestMetadata.searchQuery
        val uri = item.requestMetadata.mediaUri
        return when {
            !query.isNullOrBlank() -> findForVoice(query)
            item.mediaId.startsWith("news:") -> latestNews(item.mediaId.removePrefix("news:"))
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
                        val started = try { hitMix.start(genre) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                        // Während des Starts gestoppt: nichts abspielen (Stopp bleibt Stopp)
                        if (started == null && !hitMix.isActive) { updateFavButton(); return@launch }
                        val item = started ?: anyMusicStation()
                        val p = session?.player
                        if (p != null) { p.setMediaItem(item); p.prepare(); p.play() }
                        updateFavButton()
                    }
                }
                updateFavButton()
            }
            if (customCommand.customAction == CMD_NEXT) {
                // Weiter gedrückt, bevor überhaupt ein Lied kam: das war Werbung am Senderanfang -> merken (auch für andere)
                val sid = NowSong.stationId
                if (hitMix.isActive && sid != null && NowSong.title == null && NowSong.sinceTune() in 4_000..60_000) {
                    val ms = NowSong.sinceTune() + 10_000
                    Ads.record(this@PlaybackService, sid, ms)
                    Radar.reportAd(sid, ms)
                }
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
            // Fehler (z. B. kein Netz) nie bis zu Android Auto durchreichen: dann eben eine leere Liste
            val items: List<MediaItem> = try { withTimeout(15_000) { when (parentId) {
                // Android Auto zeigt bis zu 4 Ordner als Reiter: übersichtlich wie in der App
                ROOT_ID -> listOf(
                    folderItem(FORYOU_ID, "Für dich"),
                    folderItem(RADIO_ID, "Radio"),
                    folderItem(MUSIC_ID, "Musik"),
                    folderItem(STORIES_ID, "Hörspiele"),
                )
                FORYOU_ID -> buildList {
                    val g1 = "Hit-Mix"
                    add(hitMixItem(null, "Hit-Mix", "Nur Hits aus dem Radio", g1))
                    if (Favorites.all(applicationContext).count { it.kind == Kind.STATION } >= 2) add(hitMixItem("favs", "Lieblingssender", "Mix aus deinen Sendern", g1))
                    Moments.now().filter { it.action == Moments.Action.HITMIX }.forEach { add(hitMixItem(it.genreKey, it.title, "Passt gerade", g1)) }
                    val g2 = "Playlists für dich"
                    (Playlists.trending.take(1) + Playlists.forNow() + Playlists.weekly().take(1)).distinctBy { it.query }
                        .forEach { add(hitMixItem("pl:${it.query}", it.name, "Playlist", g2)) }
                    val g3 = "Deine Sachen"
                    Playlists.saved(applicationContext).forEach { add(hitMixItem("pl:${it.query}", it.name, "Gespeichert", g3)) }
                    add(folderItem(FAV_ID, "⭐ Favoriten").inGroup(g3))
                    add(folderItem(RECENT_ID, "🕘 Zuletzt gehört").inGroup(g3))
                }
                RADIO_ID -> {
                    val g1 = "Nachrichten & Verkehr"
                    val g2 = "Radio"
                    listOf(
                        newsItem("ts100", "Tagesschau", "In 100 Sekunden · immer aktuell", g1),
                        newsItem("dlf", "Deutschlandfunk", "Nachrichten · stündlich neu", g1),
                        folderItem(TRAFFIC_ID, "🚗 Verkehr in deiner Region").inGroup(g1),
                        folderItem(LIVE_ID, "🔴 Jetzt im Radio").inGroup(g2),
                        folderItem(CHARTS_LIVE_ID, "🔥 Charts gerade im Radio").inGroup(g2),
                        folderItem(RADIOCHARTS_ID, "📊 Meistgespielt im Radio").inGroup(g2),
                        folderItem(POPULAR_ID, "📻 Beliebte Sender").inGroup(g2),
                        folderItem(HITS_ID, "🔎 Letzte Suche").inGroup(g2),
                    )
                }
                MUSIC_ID -> buildList {
                    Playlists.trending.forEach { add(hitMixItem("pl:${it.query}", it.name, "Gerade angesagt", "Gerade angesagt")) }
                    Playlists.forNow().forEach { add(hitMixItem("pl:${it.query}", it.name, "Passend zu jetzt", "Passend zu jetzt")) }
                    Playlists.weekly().forEach { add(hitMixItem("pl:${it.query}", it.name, "Neu diese Woche", "Neu diese Woche")) }
                    Genres.all.filter { it.key != "kids" }.forEach { add(hitMixItem(it.key, it.name, "Hit-Mix", "Genres")) }
                    val g = "Mehr Musik"
                    add(folderItem("playlists", "🎶 Alle Playlists").inGroup(g))
                    add(folderItem(FREE_ID, "🎵 Freie Musik – Charts").inGroup(g))
                    add(folderItem(FREEGENRES_ID, "♾️ Freie Musik endlos").inGroup(g))
                    add(folderItem(KIDS_ID, "🧸 Kinderlieder").inGroup(g))
                }
                TRAFFIC_ID -> trafficStations()
                "playlists" -> Playlists.mine(applicationContext).keys.map { hitMixItem("my:$it", it, "Eigene Playlist", "Eigene") } +
                    (Playlists.saved(applicationContext) + Playlists.classics + Playlists.trending + Playlists.forNow() + Playlists.weekly())
                        .distinctBy { it.query }.map { hitMixItem("pl:${it.query}", it.name, "Playlist", "Playlists") }
                FAV_ID -> Favorites.all(applicationContext).map { it.toMediaItem() }
                MOMENTS_ID -> Moments.now().filter { it.action == Moments.Action.HITMIX }
                    .map { hitMixItem(it.genreKey, it.title) }
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
                    val live = withContext(Dispatchers.Default) {
                        charts.mapNotNull { (t, _) ->
                            val a = t.substringBefore(" - ").substringBefore(" – ").trim()
                            val ti = t.substringAfter(" - ").substringAfter(" – ").trim()
                            LiveIndex.findSong(ti, a)
                        }.distinctBy { it.station.id }
                    }
                    listOf(hitMixItem("radiocharts", "Mix: Meistgespielt", "Hit-Mix")) +
                        withCovers(live.map { it.station.toEntry(it.title) })
                }
                CHARTS_LIVE_ID -> {
                    val charts = Deezer.topGermany()
                    if (!Radar.sync()) {
                        val stations = StationRepository.load(applicationContext, big = false).take(150)
                        withTimeoutOrNull(20_000) { LiveIndex.refresh(stations) }
                    }
                    withCovers(withContext(Dispatchers.Default) {
                        charts.mapNotNull { LiveIndex.findSong(it.title, it.subtitle) }
                            .distinctBy { it.station.id }
                            .map { it.station.toEntry(it.title) }
                    })
                }
                HITS_ID -> Scanner.lastHits.map { it.station.toEntry(it.title).toMediaItem() }
                KIDS_ID -> listOf(hitMixItem("kids", "Kinder-Hit-Mix", "Nur Kinderlieder")) + kidsEntries().map { it.toMediaItem() }
                FREE_ID -> FreeMusic.charts().map { it.toMediaItem() }
                POPULAR_ID -> StationRepository.load(applicationContext).take(50).map { it.toEntry().toMediaItem() }
                FREEGENRES_ID -> FREE_TAGS.map { (name, tag) -> folderItem("ftag:$tag", name) }
                STORIES_ID -> listOf(folderItem(STORY_CONT_ID, "▶ Weiterhören")) +
                    Podcasts.all.map { folderItem("cat:" + it.key, (if (it.kids) "🧸 " else "") + it.name) }
                else -> when {
                    parentId.startsWith("cat:") -> {
                        val cat = Podcasts.all.firstOrNull { it.key == parentId.removePrefix("cat:") }
                        val list = cat?.let { Podcasts.shows(it) }.orEmpty()
                        rememberShows(list)
                        list.map { show -> folderItem("show:" + show.id, show.name) }
                    }
                    parentId.startsWith("ftag:") -> {
                        val tag = parentId.removePrefix("ftag:")
                        endlessTag = tag; endlessOffset = 200
                        Jamendo.many(tag).map { it.toMediaItem() }
                    }
                    parentId.startsWith("show:") ->
                        findShow(parentId)?.let { Podcasts.episodes(it) }.orEmpty().map { it.toMediaItem() }
                    else -> emptyList()
                }
            } } } catch (e: kotlinx.coroutines.CancellationException) { if (e is kotlinx.coroutines.TimeoutCancellationException) emptyList() else throw e }
            catch (_: Exception) { emptyList() }
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
            // Neue Suche: die alte (Sender-Scan im Hintergrund) abbrechen
            searchJob?.cancel()
            searchJob = scope.launch {
                Radar.sync()
                val words = Scanner.words(query)
                val radioHits = Collections.synchronizedList(mutableListOf<MediaItem>())
                // Läuft gerade irgendwo (Server-Stand): sofort da (Suche über alle Titel im Hintergrund)
                withContext(Dispatchers.Default) {
                    LiveIndex.find(query).filter { LiveIndex.isMusic(it.title) }.sortedByDescending { it.since }.take(15)
                }.forEach { radioHits.add(it.station.toEntry(it.title).toMediaItem()) }
                val stations = if (Radar.ok) emptyList() else try { StationRepository.load(applicationContext) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                val named = (Radar.stations(query)?.take(10)?.map { it.toMediaItem() }
                    ?: withContext(Dispatchers.Default) { stations.filter { Scanner.matches(it.name, words) }.take(10) }.map { it.toEntry().toMediaItem() })
                var free: List<MediaItem> = emptyList()

                fun publish() {
                    val all = (synchronized(radioHits) { radioHits.take(40) } + named + free).distinctBy { it.mediaId }
                    remember(all)
                    searchResults[query] = all
                    session.notifySearchResultChanged(browser, query, all.size, params)
                }
                publish()
                launch {
                    free = try { FreeMusic.search(query).map { it.toMediaItem() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
                    publish()
                }
                if (stations.isNotEmpty()) {
                    val hits = Scanner.scan(stations, query) { hit ->
                        if (radioHits.size < 40) {
                            radioHits.add(hit.station.toEntry(hit.title).toMediaItem())
                            launch { publish() }
                        }
                    }
                    Scanner.lastHits = hits
                }
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
            // Nie leer und nie mit Fehler antworten (Android Auto: "Auswahl konnte nicht geladen werden")
            // Jeder Eintrag einzeln: ein kaputter darf die anderen nicht mitreißen
            val out = mediaItems.mapNotNull { m ->
                try {
                    if (m.mediaId.startsWith("hitmix:")) {
                        (hitMix.start(m.mediaId.removePrefix("hitmix:").ifBlank { null }) ?: anyMusicStation()).also { updateFavButton() }
                    } else safeResolve(m)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            }
            out.ifEmpty { listOf(anyMusicStation()) }.toMutableList()
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            // Kopfhörer/Auto verbindet sich und schickt "Play": nicht starten, wenn der Nutzer vorher selbst gestoppt hat
            val key = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            val play = key?.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ||
                (key?.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && session.player.playWhenReady.not())
            if (play && blockAutoStart(controllerInfo)) return true
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = if (blockAutoStart(controller))
            Futures.immediateFailedFuture(UnsupportedOperationException("vom Nutzer gestoppt")) else scope.future {
            val last = Recent.all(applicationContext).firstOrNull()
            val item = last?.toMediaItem()?.let { liveTitle(it) }
                ?: try { hitMix.start(null) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                ?: anyMusicStation()
            val pos = last?.let { Positions.get(this@PlaybackService, it.id) }?.takeIf { it > 0 } ?: C.TIME_UNSET
            MediaSession.MediaItemsWithStartPosition(listOf(item), 0, pos)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            try {
                setItems(mediaItems, startIndex, startPositionMs)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                // Irgendetwas schiefgegangen (Netz o. Ä.): lieber Musik als eine Fehlermeldung
                MediaSession.MediaItemsWithStartPosition(listOf(anyMusicStation()), 0, C.TIME_UNSET)
            }
        }

        private suspend fun setItems(
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): MediaSession.MediaItemsWithStartPosition {
            // Hit-Mix gewählt?
            val hm = mediaItems.firstOrNull { it.mediaId.startsWith("hitmix:") }
            if (hm != null) {
                // Playlist/Mix lässt sich gerade nicht laden (Netz o. Ä.): trotzdem Musik statt Fehlermeldung
                val first = try { hitMix.start(hm.mediaId.removePrefix("hitmix:").ifBlank { null }) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                updateFavButton()
                // Nie leer zurückgeben (Android Auto zeigt sonst "Auswahl konnte nicht geladen werden"):
                // bis ein passender Hit anfängt, läuft ein Sender mit Musik, der Mix springt dann selbst um
                return MediaSession.MediaItemsWithStartPosition(listOf(first ?: anyMusicStation()), 0, C.TIME_UNSET)
            }
            bedtime = false
            // Etwas anderes gewählt: Hit-Mix beenden
            if (hitMix.isActive) { hitMix.stop(); updateFavButton() }
            val resolved = mediaItems.mapNotNull { safeResolve(it) }.ifEmpty {
                // Nichts gefunden (z. B. Sprachbefehl): lieber den Hit-Mix als eine Fehlermeldung
                listOf((try { hitMix.start(null) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null })
                    ?: anyMusicStation())
            }
            // Ein einzelnes freies Lied aus einer Liste: ganze Liste laden, damit "Weiter" geht
            if (resolved.size == 1) {
                val list = listContext[resolved[0].mediaId]
                if (list != null) {
                    val idx = list.indexOfFirst { it.mediaId == resolved[0].mediaId }.coerceAtLeast(0)
                    val saved = Positions.get(this@PlaybackService, resolved[0].mediaId).takeIf { it > 0 } ?: C.TIME_UNSET
                    return MediaSession.MediaItemsWithStartPosition(list.map { liveTitle(it) ?: it }, idx, saved)
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
                    return MediaSession.MediaItemsWithStartPosition(before + chosen + after, before.size, C.TIME_UNSET)
                }
            }
            val index = if (resolved.size == mediaItems.size) startIndex else 0
            // Geschichten dort weiterspielen, wo man aufgehört hat
            val startItem = resolved.getOrNull(if (index == C.INDEX_UNSET) 0 else index)
            val pos = if (startPositionMs == C.TIME_UNSET || startPositionMs == 0L) {
                startItem?.let { Positions.get(this@PlaybackService, it.mediaId) }?.takeIf { it > 0 } ?: startPositionMs
            } else startPositionMs
            return MediaSession.MediaItemsWithStartPosition(resolved, index, pos)
        }
    }

    /** Hörspiel-Ordner auch nach einem Neustart der App wiederfinden (Android Auto merkt sich die Ordner). */
    private fun showPrefs() = getSharedPreferences("aa_shows", MODE_PRIVATE)

    private fun rememberShows(list: List<Entry>) {
        if (list.isEmpty()) return
        val p = showPrefs()
        val e = p.edit()
        if (p.all.size > 500) e.clear()
        list.forEach { show ->
            shows["show:" + show.id] = show
            e.putString("show:" + show.id, show.toJson().toString())
        }
        e.apply()
    }

    private fun findShow(id: String): Entry? = shows[id] ?: try {
        showPrefs().getString(id, null)?.let { Entry.fromJson(org.json.JSONObject(it)) }?.also { shows[id] = it }
    } catch (_: Exception) { null }

    /** Map mit Obergrenze (am längsten nicht benutzte Einträge fliegen raus), sicher bei mehreren Threads. */
    private fun <K, V> lru(max: Int): MutableMap<K, V> = Collections.synchronizedMap(object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    })

    /** Ein Eintrag, der sich gerade nicht auflösen lässt (Netzfehler), wird übersprungen statt alles abzubrechen. */
    private suspend fun safeResolve(item: MediaItem): MediaItem? =
        try { resolve(item) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
}
