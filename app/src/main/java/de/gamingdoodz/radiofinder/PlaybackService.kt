package de.gamingdoodz.radiofinder

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
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
    }

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val itemCache = ConcurrentHashMap<String, MediaItem>()
    private val searchResults = ConcurrentHashMap<String, List<MediaItem>>()
    // Für freie Lieder: die ganze Liste merken, damit man in Android Auto weiterspringen kann
    private val listContext = ConcurrentHashMap<String, List<MediaItem>>()
    private val favCommand = SessionCommand("FAV_TOGGLE", Bundle.EMPTY)
    private val sleepCommand = SessionCommand(CMD_SLEEP, Bundle.EMPTY)
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepRunnable = Runnable {
        session?.player?.pause()
        Settings.setSleepEnd(this, 0L)
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateFavButton() }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("RadioFinder/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val player = ExoPlayer.Builder(this)
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
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateFavButton()
                mediaItem?.let { Entry.fromMediaItem(it) }?.let { Recent.add(this@PlaybackService, it) }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
        Favorites.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        sleepHandler.removeCallbacks(sleepRunnable)
        Settings.setSleepEnd(this, 0L)
        Favorites.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
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
    private fun updateFavButton() {
        session?.setCustomLayout(ImmutableList.of(favButton(currentIsFav())))
    }

    // ---------- Listen ----------

    private fun remember(items: List<MediaItem>) {
        items.forEach { itemCache[it.mediaId] = it }
        val tracks = items.filter { !it.mediaId.startsWith("st:") }
        if (tracks.size > 1) tracks.forEach { listContext[it.mediaId] = tracks }
    }

    private suspend fun kidsEntries(): List<Entry> = coroutineScope {
        val stations = async { StationRepository.kids(applicationContext) }
        val songs = async { FreeMusic.kids() }
        stations.await().map { it.toEntry() } + songs.await()
    }

    /** "Spiel X": erst Sendername, dann Radio wo es gerade läuft, dann freie Musik. */
    private suspend fun findForVoice(query: String): MediaItem? {
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

    private suspend fun resolve(item: MediaItem): MediaItem? {
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
                        .buildUpon().add(favCommand).add(sleepCommand).build()
                )
                .setCustomLayout(ImmutableList.of(favButton(currentIsFav())))
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SLEEP) {
                val min = args.getInt("min", 0)
                sleepHandler.removeCallbacks(sleepRunnable)
                if (min > 0) {
                    sleepHandler.postDelayed(sleepRunnable, min * 60_000L)
                    Settings.setSleepEnd(this@PlaybackService, System.currentTimeMillis() + min * 60_000L)
                } else {
                    Settings.setSleepEnd(this@PlaybackService, 0L)
                }
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
            return Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT_ID, "Radio Finder"), out))
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
                ROOT_ID -> listOf(
                    folderItem(FAV_ID, "⭐ Favoriten"),
                    folderItem(RECENT_ID, "🕘 Zuletzt gehört"),
                    folderItem(LIVE_ID, "🔴 Gerade im Radio"),
                    folderItem(CHARTS_LIVE_ID, "🔥 Charts gerade im Radio"),
                    folderItem(POPULAR_ID, "📻 Beliebte Sender"),
                    folderItem(FREE_ID, "🎵 Freie Musik"),
                    folderItem(KIDS_ID, "🧸 Kinder"),
                    folderItem(HITS_ID, "🔎 Letzte Suche"),
                )
                FAV_ID -> Favorites.all(applicationContext).map { it.toMediaItem() }
                RECENT_ID -> Recent.all(applicationContext).map { it.toMediaItem() }
                LIVE_ID -> {
                    val stations = StationRepository.load(applicationContext, big = false).take(80)
                    withTimeoutOrNull(15_000) { LiveIndex.refresh(stations) }
                    val now = LiveIndex.fresh().associateBy { it.station.id }
                    stations.mapNotNull { s -> now[s.id]?.let { s.toEntry(it.title).toMediaItem() } }
                }
                CHARTS_LIVE_ID -> {
                    val stations = StationRepository.load(applicationContext, big = false).take(150)
                    val charts = Deezer.topGermany()
                    withTimeoutOrNull(20_000) { LiveIndex.refresh(stations) }
                    charts.mapNotNull { LiveIndex.findSong(it.title, it.subtitle) }
                        .distinctBy { it.station.id }
                        .map { it.station.toEntry(it.title).toMediaItem() }
                }
                HITS_ID -> Scanner.lastHits.map { it.station.toEntry(it.title).toMediaItem() }
                KIDS_ID -> kidsEntries().map { it.toMediaItem() }
                FREE_ID -> FreeMusic.charts().map { it.toMediaItem() }
                POPULAR_ID -> StationRepository.load(applicationContext).take(50).map { it.toEntry().toMediaItem() }
                else -> emptyList()
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

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val resolved = mediaItems.mapNotNull { resolve(it) }
            // Ein einzelnes freies Lied aus einer Liste: ganze Liste laden, damit "Weiter" geht
            if (resolved.size == 1) {
                val list = listContext[resolved[0].mediaId]
                if (list != null) {
                    val idx = list.indexOfFirst { it.mediaId == resolved[0].mediaId }.coerceAtLeast(0)
                    return@future MediaSession.MediaItemsWithStartPosition(list, idx, C.TIME_UNSET)
                }
            }
            val index = if (resolved.size == mediaItems.size) startIndex else 0
            MediaSession.MediaItemsWithStartPosition(resolved, index, startPositionMs)
        }
    }
}
