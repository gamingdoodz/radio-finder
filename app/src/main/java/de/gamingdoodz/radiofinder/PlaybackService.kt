package de.gamingdoodz.radiofinder

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Spielt das Radio im Hintergrund ab (auch bei gesperrtem Bildschirm)
 * und liefert die Listen und die Suche für Android Auto.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val itemCache = ConcurrentHashMap<String, MediaItem>()
    private val searchResults = ConcurrentHashMap<String, List<MediaItem>>()

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

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun remember(items: List<MediaItem>) {
        items.forEach { itemCache[it.mediaId] = it }
    }

    /** Findet für "spiel X" einen passenden Sender: erst nach Sendername, dann nach laufendem Lied. */
    private suspend fun findForVoice(query: String): MediaItem? {
        val stations = StationRepository.load(applicationContext)
        val words = Scanner.words(query)
        stations.firstOrNull { Scanner.matches(it.name, words) }?.let { return it.toMediaItem() }
        val hit = Scanner.scan(stations, query, stopAfterFirst = true).firstOrNull() ?: return null
        Scanner.lastHits = listOf(hit)
        return hit.station.toMediaItem(hit.title)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT_ID, "Radio Finder"), params))

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
                    folderItem(HITS_ID, "Letzte Treffer"),
                    folderItem(POPULAR_ID, "Beliebte Sender"),
                )
                HITS_ID -> Scanner.lastHits.map { it.station.toMediaItem(it.title) }
                POPULAR_ID -> StationRepository.load(applicationContext).take(50).map { it.toMediaItem() }
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
                val found = Collections.synchronizedList(mutableListOf<MediaItem>())
                // Sender, deren Name passt, gleich anzeigen
                stations.filter { Scanner.matches(it.name, words) }.take(10)
                    .forEach { found.add(it.toMediaItem()) }
                fun publish() {
                    val snapshot = synchronized(found) { found.toList() }
                    remember(snapshot)
                    searchResults[query] = snapshot
                    session.notifySearchResultChanged(browser, query, snapshot.size, params)
                }
                publish()
                val hits = Scanner.scan(stations, query) { hit ->
                    found.add(0, hit.station.toMediaItem(hit.title))
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
            val resolved = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val query = item.requestMetadata.searchQuery
                val uri = item.requestMetadata.mediaUri
                val r = when {
                    !query.isNullOrBlank() -> findForVoice(query)
                    uri != null -> item.buildUpon().setUri(uri).build()
                    else -> itemCache[item.mediaId]
                }
                if (r != null) resolved.add(r)
            }
            resolved
        }
    }
}
