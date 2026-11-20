package de.gamingdoodz.radiofinder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private enum class Tab { DISCOVER, KIDS, FAV }

    private sealed class Row {
        data class Header(val text: String) : Row()
        data class Item(val entry: Entry, val section: List<Entry>, val live: Boolean = false) : Row()
    }

    private lateinit var input: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var playerArt: ImageView
    private lateinit var playerTitle: TextView
    private lateinit var playerSub: TextView
    private lateinit var playerFav: ImageButton
    private lateinit var playerPlay: ImageButton
    private lateinit var playerNext: ImageButton

    private val rows = mutableListOf<Row>()
    private val adapter = RowAdapter()
    private var tab = Tab.DISCOVER

    // Inhalte
    private var popular: List<Entry> = emptyList()
    private var trending: List<Entry> = emptyList()
    private var kidStations: List<Entry> = emptyList()
    private var kidSongs: List<Entry> = emptyList()
    private var kidsLoaded = false
    private var query = ""
    private val radioHits = mutableListOf<Entry>()
    private var freeHits: List<Entry> = emptyList()
    private var scanning = false

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var searchId = 0

    private val favListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render(); updatePlayer() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.search)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        playerArt = findViewById(R.id.playerArt)
        playerTitle = findViewById(R.id.playerTitle)
        playerSub = findViewById(R.id.playerSub)
        playerFav = findViewById(R.id.playerFav)
        playerPlay = findViewById(R.id.playerPlay)
        playerNext = findViewById(R.id.playerNext)
        playerArt.clipToOutline = true

        tabViews = mapOf(
            Tab.DISCOVER to findViewById(R.id.tabSearch),
            Tab.KIDS to findViewById(R.id.tabKids),
            Tab.FAV to findViewById(R.id.tabFav),
        )
        tabViews.forEach { (t, v) -> v.setOnClickListener { selectTab(t) } }

        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            (rows.getOrNull(pos) as? Row.Item)?.let { play(it) }
        }

        findViewById<View>(R.id.searchButton).setOnClickListener { startSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }

        playerPlay.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            when {
                c.isPlaying -> c.pause()
                c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0 -> { c.prepare(); c.play() }
                else -> c.play()
            }
        }
        playerNext.setOnClickListener { controller?.seekToNextMediaItem() }
        playerFav.setOnClickListener {
            val item = controller?.currentMediaItem ?: return@setOnClickListener
            Entry.fromMediaItem(item)?.let { Favorites.toggle(this, it) }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        selectTab(Tab.DISCOVER)
        loadStart()
    }

    override fun onStart() {
        super.onStart()
        Favorites.prefs(this).registerOnSharedPreferenceChangeListener(favListener)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (_: Exception) { return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updatePlayer()
            })
            updatePlayer()
        }, ContextCompat.getMainExecutor(this))
        render()
    }

    override fun onStop() {
        Favorites.prefs(this).unregisterOnSharedPreferenceChangeListener(favListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    // ---------- Laden ----------

    private fun loadStart() {
        lifecycleScope.launch {
            val stations = async { try { StationRepository.load(applicationContext) } catch (_: Exception) { emptyList() } }
            val top = async { Audius.trending(25) }
            popular = stations.await().take(40).map { it.toEntry() }
            trending = top.await().map { it.toEntry() }
            render()
        }
    }

    private fun loadKids() {
        if (kidsLoaded) return
        kidsLoaded = true
        lifecycleScope.launch {
            val st = async { try { StationRepository.kids(applicationContext) } catch (_: Exception) { emptyList() } }
            val songs1 = async { Audius.search("kinderlieder", 30) }
            val songs2 = async { Audius.search("kids songs", 20) }
            kidStations = st.await().map { it.toEntry() }
            kidSongs = (songs1.await() + songs2.await()).distinctBy { it.id }.map { it.toEntry() }
            if (kidStations.isEmpty() && kidSongs.isEmpty()) kidsLoaded = false
            render()
        }
    }

    private fun selectTab(t: Tab) {
        tab = t
        tabViews.forEach { (key, v) ->
            v.setBackgroundResource(if (key == t) R.drawable.bg_tab_on else R.drawable.bg_tab_off)
        }
        if (t == Tab.KIDS) loadKids()
        render()
    }

    // ---------- Suche ----------

    private fun startSearch() {
        val q = input.text.toString().trim()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        searchJob?.cancel()
        val myId = ++searchId
        query = q
        radioHits.clear()
        freeHits = emptyList()
        scanning = false
        selectTab(Tab.DISCOVER)
        if (q.isEmpty()) return

        scanning = true
        progress.progress = 0
        render()

        searchJob = lifecycleScope.launch {
            launch {
                val free = Audius.search(q, 25).map { it.toEntry() }
                if (myId == searchId) { freeHits = free; render() }
            }
            val stations = try { StationRepository.load(applicationContext) } catch (_: Exception) { emptyList() }
            if (myId != searchId) return@launch
            // Sender, deren Name passt, gleich oben anzeigen
            val words = Scanner.words(q)
            stations.filter { Scanner.matches(it.name, words) }.take(5).forEach { radioHits.add(it.toEntry()) }
            progress.max = stations.size.coerceAtLeast(1)
            render()
            val result = Scanner.scan(
                stations, q,
                onProgress = { done, total ->
                    runOnUiThread {
                        if (myId == searchId) {
                            progress.progress = done
                            status.text = "Durchsuche Radiosender… $done von $total"
                        }
                    }
                },
                onHit = { hit ->
                    runOnUiThread {
                        if (myId == searchId) {
                            radioHits.add(0, hit.station.toEntry(hit.title))
                            render()
                        }
                    }
                }
            )
            if (myId != searchId) return@launch
            Scanner.lastHits = result
            scanning = false
            render()
        }
    }

    // ---------- Anzeige ----------

    private fun render() {
        rows.clear()
        when (tab) {
            Tab.DISCOVER -> renderDiscover()
            Tab.KIDS -> {
                if (!kidsLoaded || (kidStations.isEmpty() && kidSongs.isEmpty())) {
                    rows.add(Row.Header("LADE KINDERSACHEN…"))
                }
                if (kidStations.isNotEmpty()) {
                    rows.add(Row.Header("KINDERRADIOS"))
                    kidStations.forEach { rows.add(Row.Item(it, kidStations)) }
                }
                if (kidSongs.isNotEmpty()) {
                    rows.add(Row.Header("FREIE KINDERLIEDER"))
                    kidSongs.forEach { rows.add(Row.Item(it, kidSongs)) }
                }
            }
            Tab.FAV -> {
                val favs = Favorites.all(this)
                if (favs.isEmpty()) {
                    rows.add(Row.Header("NOCH KEINE FAVORITEN – TIPP AUF DAS ♥"))
                } else {
                    rows.add(Row.Header("DEINE FAVORITEN"))
                    favs.forEach { rows.add(Row.Item(it, favs.filter { f -> f.kind == Kind.TRACK })) }
                }
            }
        }
        val showStatus = tab == Tab.DISCOVER && scanning
        progress.visibility = if (showStatus) View.VISIBLE else View.GONE
        status.visibility = if (showStatus) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun renderDiscover() {
        if (query.isEmpty()) {
            rows.add(Row.Header("BELIEBTE SENDER"))
            if (popular.isEmpty()) rows.add(Row.Header("Lade…"))
            popular.forEach { rows.add(Row.Item(it, popular)) }
            if (trending.isNotEmpty()) {
                rows.add(Row.Header("FREIE MUSIK · GERADE BELIEBT"))
                trending.forEach { rows.add(Row.Item(it, trending)) }
            }
            return
        }
        rows.add(Row.Header("IM RADIO"))
        if (radioHits.isEmpty()) {
            rows.add(Row.Header(if (scanning) "Suche läuft…" else "„$query“ läuft gerade nirgends. Später nochmal probieren."))
        }
        radioHits.forEach { rows.add(Row.Item(it, radioHits.toList(), live = it.subtitle != "Internetradio")) }
        rows.add(Row.Header("FREIE MUSIK"))
        if (freeHits.isEmpty()) rows.add(Row.Header("Nichts gefunden"))
        freeHits.forEach { rows.add(Row.Item(it, freeHits)) }
    }

    private fun play(row: Row.Item) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player startet noch, bitte nochmal tippen", Toast.LENGTH_SHORT).show()
            return
        }
        if (row.entry.kind == Kind.TRACK) {
            // Ganze Liste laden, damit man weiterspringen kann
            val tracks = row.section.filter { it.kind == Kind.TRACK }
            val idx = tracks.indexOfFirst { it.id == row.entry.id }.coerceAtLeast(0)
            c.setMediaItems(tracks.map { it.toMediaItem() }, idx, 0L)
        } else {
            c.setMediaItem(row.entry.toMediaItem())
        }
        c.prepare()
        c.play()
    }

    private fun updatePlayer() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            playerTitle.text = "Nichts läuft"
            playerSub.text = "Such dir was aus"
            playerArt.setImageResource(R.drawable.ic_note)
            playerPlay.setImageResource(R.drawable.ic_play)
            playerFav.visibility = View.INVISIBLE
            playerNext.visibility = View.GONE
            return
        }
        val m = item.mediaMetadata
        val liveTitle = c.mediaMetadata.title?.toString()
        playerTitle.text = liveTitle?.takeIf { it.isNotBlank() } ?: m.title ?: ""
        playerSub.text = when {
            c.playbackState == Player.STATE_BUFFERING -> "Verbinde…"
            else -> m.artist ?: ""
        }
        loadArt(playerArt, m.artworkUri?.toString().orEmpty(), item.mediaId.startsWith("st:"))
        playerPlay.setImageResource(if (c.isPlaying || c.playbackState == Player.STATE_BUFFERING && c.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        playerFav.visibility = View.VISIBLE
        playerFav.setImageResource(if (Favorites.isFav(this, item.mediaId)) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        playerNext.visibility = if (c.hasNextMediaItem()) View.VISIBLE else View.GONE
    }

    private fun loadArt(view: ImageView, url: String, station: Boolean) {
        val placeholder = if (station) R.drawable.ic_radio else R.drawable.ic_note
        if (url.isBlank()) {
            view.scaleType = ImageView.ScaleType.CENTER
            view.setImageResource(placeholder)
            return
        }
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.load(url) {
            crossfade(true)
            placeholder(placeholder)
            error(placeholder)
            listener(onError = { _, _ -> view.scaleType = ImageView.ScaleType.CENTER })
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is Row.Header -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.row_header, parent, false)
                    (v as TextView).text = row.text
                    v
                }
                is Row.Item -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.row_entry, parent, false)
                    val e = row.entry
                    val art = v.findViewById<ImageView>(R.id.art)
                    art.clipToOutline = true
                    loadArt(art, e.artwork, e.kind == Kind.STATION)
                    v.findViewById<TextView>(R.id.title).text = e.title
                    v.findViewById<TextView>(R.id.subtitle).text = e.subtitle
                    v.findViewById<TextView>(R.id.badge).visibility = if (row.live) View.VISIBLE else View.GONE
                    val fav = v.findViewById<ImageButton>(R.id.fav)
                    fav.setImageResource(if (Favorites.isFav(this@MainActivity, e.id)) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                    fav.setOnClickListener { Favorites.toggle(this@MainActivity, e) }
                    v
                }
            }
        }
    }
}
