package de.gamingdoodz.radiofinder

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private enum class Tab { HOME, FIND, KIDS, LIB }

    private sealed class Page {
        data class GenrePage(val genre: Genre) : Page()
        object ChartsPage : Page()
    }

    private sealed class Row {
        data class Header(val title: String, val sub: String? = null, val more: String? = null, val onMore: (() -> Unit)? = null) : Row()
        data class Carousel(val entries: List<Entry>, val live: Boolean = false) : Row()
        data class Item(val entry: Entry, val section: List<Entry>, val liveText: String? = null, val rank: Int? = null) : Row()
        data class Status(val text: String, val done: Int, val total: Int) : Row()
        data class Action(val title: String, val sub: String, val dark: Boolean, val onClick: () -> Unit) : Row()
        data class Empty(val text: String) : Row()
        data class Tiles(val g1: Genre, val g2: Genre?) : Row()
        data class Hero(val genre: Genre, val sub: String) : Row()
        data class More(val text: String, val onClick: () -> Unit) : Row()
    }

    // Ansichten
    private lateinit var input: EditText
    private lateinit var clear: ImageButton
    private lateinit var pageTitle: TextView
    private lateinit var back: ImageButton
    private lateinit var searchBox: View
    private lateinit var recycler: RecyclerView
    private lateinit var mini: View
    private lateinit var full: View
    private val adapter = MainAdapter()

    private var tab = Tab.HOME
    private var page: Page? = null
    private val rows = mutableListOf<Row>()

    // Inhalte
    private var popular: List<Station> = emptyList()
    private var topDe: List<Entry> = emptyList()
    private var freeCharts: List<Entry> = emptyList()
    private var kidStations: List<Entry> = emptyList()
    private var kidSongs: List<Entry> = emptyList()
    private var kidCharts: List<Entry> = emptyList()
    private var kidsState = 0
    private val genreStations = HashMap<String, List<Station>>()
    private val genreSongs = HashMap<String, List<Entry>>()
    private val genreFree = HashMap<String, List<Entry>>()
    private val genreLoading = HashSet<String>()

    // Suche
    private var query = ""
    private var songHits: List<Entry> = emptyList()
    private val radioHits = mutableListOf<Entry>()
    private var stationHits: List<Entry> = emptyList()
    private var freeHits: List<Entry> = emptyList()
    private var songLoading = false
    private var freeLoading = false
    private var scanning = false
    private var scanDone = 0
    private var scanTotal = 0
    private var searchJob: Job? = null
    private var searchId = 0

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var liveJob: Job? = null
    private var seeking = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ticker.postDelayed(this, 500)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render(); updatePlayer() }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                full.visibility == View.VISIBLE -> showFull(false)
                page != null -> { page = null; render(); recycler.scrollToPosition(0) }
            }
            updateBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backCallback)

        input = findViewById(R.id.search)
        clear = findViewById(R.id.clear)
        pageTitle = findViewById(R.id.pageTitle)
        back = findViewById(R.id.back)
        searchBox = findViewById(R.id.searchBox)
        recycler = findViewById(R.id.recycler)
        mini = findViewById(R.id.mini)
        full = findViewById(R.id.full)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.itemAnimator = null

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        clear.setOnClickListener { input.setText(""); startSearch() }
        back.setOnClickListener { backCallback.handleOnBackPressed() }

        findViewById<View>(R.id.navHome).setOnClickListener { selectTab(Tab.HOME) }
        findViewById<View>(R.id.navFind).setOnClickListener { selectTab(Tab.FIND) }
        findViewById<View>(R.id.navKids).setOnClickListener { selectTab(Tab.KIDS) }
        findViewById<View>(R.id.navLib).setOnClickListener { selectTab(Tab.LIB) }

        mini.setOnClickListener { showFull(true) }
        findViewById<View>(R.id.miniPlay).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.miniNext).setOnClickListener { controller?.seekToNextMediaItem() }

        findViewById<View>(R.id.fullClose).setOnClickListener { showFull(false) }
        findViewById<View>(R.id.fullPlay).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.fullNext).setOnClickListener { controller?.seekToNextMediaItem() }
        findViewById<View>(R.id.fullPrev).setOnClickListener { controller?.seekToPreviousMediaItem() }
        findViewById<View>(R.id.fullStop).setOnClickListener {
            controller?.stop()
            controller?.clearMediaItems()
            showFull(false)
        }
        findViewById<View>(R.id.fullFav).setOnClickListener {
            controller?.currentMediaItem?.let { Entry.fromMediaItem(it) }?.let { Favorites.toggle(this, it) }
        }
        findViewById<View>(R.id.fullSleep).setOnClickListener { sleepDialog() }
        findViewById<SeekBar>(R.id.fullSeek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) findViewById<TextView>(R.id.fullPos).text = fmt(p.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                controller?.seekTo(sb.progress.toLong())
            }
        })
        if (Build.VERSION.SDK_INT >= 31) {
            findViewById<View>(R.id.fullBg).setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        selectTab(Tab.HOME)
        loadHome()
    }

    override fun onStart() {
        super.onStart()
        listOf(Favorites.prefs(this), WatchService.prefs(this), Recent.prefs(this), Settings.prefs(this))
            .forEach { it.registerOnSharedPreferenceChangeListener(prefsListener) }
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
        ticker.post(tick)
        startLiveRefresh()
        render()
    }

    override fun onStop() {
        listOf(Favorites.prefs(this), WatchService.prefs(this), Recent.prefs(this), Settings.prefs(this))
            .forEach { it.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        ticker.removeCallbacks(tick)
        liveJob?.cancel()
        super.onStop()
    }

    // ================= Laden =================

    private fun loadHome() {
        lifecycleScope.launch {
            val st = async { try { StationRepository.load(applicationContext, big = false) } catch (_: Exception) { emptyList() } }
            val top = async { Deezer.topGermany() }
            val fc = async { FreeMusic.charts() }
            popular = st.await()
            render()
            topDe = top.await()
            render()
            freeCharts = fc.await()
            render()
        }
    }

    /** Schaut regelmäßig, was auf den beliebten Sendern läuft (mobil nur einmal, im WLAN alle 3 Minuten). */
    private fun startLiveRefresh() {
        liveJob?.cancel()
        liveJob = lifecycleScope.launch {
            while (isActive) {
                val list = if (popular.isEmpty()) {
                    try { StationRepository.load(applicationContext, big = false) } catch (_: Exception) { emptyList() }
                } else popular
                val wifi = Net.isUnmetered(applicationContext)
                val n = if (wifi) 400 else if (Settings.saver(applicationContext)) 60 else 150
                if (System.currentTimeMillis() - LiveIndex.lastRefresh > 150_000) {
                    LiveIndex.refresh(list.take(n))
                    render()
                }
                if (!wifi) break
                delay(180_000)
            }
        }
    }

    private fun loadKids() {
        if (kidsState != 0) return
        kidsState = 1
        lifecycleScope.launch {
            val st = async { try { StationRepository.kids(applicationContext) } catch (_: Exception) { emptyList() } }
            val ch = async { Deezer.genreChart(95, 30) }
            val songs = async { FreeMusic.kids() }
            val stations = st.await()
            kidStations = stations.map { it.toEntry() }
            render()
            kidCharts = ch.await()
            render()
            kidSongs = songs.await()
            kidsState = if (kidStations.isEmpty() && kidSongs.isEmpty()) 0 else 2
            render()
            LiveIndex.refresh(stations.take(30))
            render()
        }
    }

    private fun loadGenre(g: Genre) {
        if (genreStations.containsKey(g.key) || !genreLoading.add(g.key)) return
        lifecycleScope.launch {
            val st = async { StationRepository.byTag(g.radioTag) }
            val songs = async { if (g.deezerGenre != null) Deezer.genreChart(g.deezerGenre, 30) else emptyList() }
            val free = async { if (g.jamendoTag != null) Jamendo.byTag(g.jamendoTag) else emptyList() }
            val stations = st.await()
            genreStations[g.key] = stations
            render()
            genreSongs[g.key] = songs.await()
            genreFree[g.key] = free.await()
            render()
            LiveIndex.refresh(stations.take(40))
            genreLoading.remove(g.key)
            render()
        }
    }

    // ================= Navigation =================

    private fun selectTab(t: Tab) {
        tab = t
        page = null
        val accent = Color.parseColor("#FFF97316")
        val dim = Color.parseColor("#80FFFFFF")
        listOf(
            Triple(Tab.HOME, R.id.navHomeIcon, R.id.navHomeLabel),
            Triple(Tab.FIND, R.id.navFindIcon, R.id.navFindLabel),
            Triple(Tab.KIDS, R.id.navKidsIcon, R.id.navKidsLabel),
            Triple(Tab.LIB, R.id.navLibIcon, R.id.navLibLabel),
        ).forEach { (key, icon, label) ->
            val color = if (key == t) accent else dim
            findViewById<ImageView>(icon).imageTintList = ColorStateList.valueOf(color)
            findViewById<TextView>(label).setTextColor(color)
        }
        searchBox.visibility = if (t == Tab.FIND) View.VISIBLE else View.GONE
        if (t == Tab.KIDS) loadKids()
        render()
        recycler.scrollToPosition(0)
    }

    private fun openPage(p: Page) {
        page = p
        if (p is Page.GenrePage) loadGenre(p.genre)
        render()
        recycler.scrollToPosition(0)
    }

    private fun updateBack() {
        back.visibility = if (page != null) View.VISIBLE else View.GONE
        backCallback.isEnabled = page != null || full.visibility == View.VISIBLE
        pageTitle.text = when (val p = page) {
            is Page.GenrePage -> p.genre.name
            Page.ChartsPage -> "Deutsche Charts"
            null -> when (tab) {
                Tab.HOME -> "Radio Finder"
                Tab.FIND -> "Finden"
                Tab.KIDS -> "Für Kinder"
                Tab.LIB -> "Bibliothek"
            }
        }
    }

    // ================= Suche =================

    private fun startSearch(text: String? = null) {
        if (text != null) input.setText(text)
        val q = input.text.toString().trim()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        searchJob?.cancel()
        val myId = ++searchId
        query = q
        songHits = emptyList()
        radioHits.clear()
        stationHits = emptyList()
        freeHits = emptyList()
        scanning = false
        songLoading = false
        freeLoading = false
        if (tab != Tab.FIND) selectTab(Tab.FIND) else { page = null; render() }
        if (q.isEmpty()) return

        scanning = true
        songLoading = true
        freeLoading = true
        scanDone = 0
        scanTotal = 0
        // Sofort: was die App schon weiß
        LiveIndex.find(q).forEach { radioHits.add(it.station.toEntry(it.title)) }
        render()

        searchJob = lifecycleScope.launch {
            launch {
                val songs = Deezer.search(q)
                if (myId == searchId) { songHits = songs; songLoading = false; render() }
            }
            launch {
                val free = FreeMusic.search(q)
                if (myId == searchId) { freeHits = free; freeLoading = false; render() }
            }
            val all = try { StationRepository.scanList(applicationContext) } catch (_: Exception) { emptyList() }
            if (myId != searchId) return@launch
            val words = Scanner.words(q)
            stationHits = all.filter { Scanner.matches(it.name, words) }.take(8).map { it.toEntry() }
            val toScan = all.filter { !LiveIndex.isFresh(it) }
            scanTotal = toScan.size
            render()
            val result = Scanner.scan(
                toScan, q,
                onProgress = { done, total ->
                    runOnUiThread {
                        if (myId == searchId) { scanDone = done; scanTotal = total; updateStatusRow() }
                    }
                },
                onHit = { hit ->
                    runOnUiThread {
                        if (myId == searchId && radioHits.none { it.id == "st:" + hit.station.id }) {
                            radioHits.add(0, hit.station.toEntry(hit.title))
                            render()
                        }
                    }
                }
            )
            if (myId != searchId) return@launch
            Scanner.lastHits = result + LiveIndex.find(q).map { Scanner.Hit(it.station, it.title) }
            scanning = false
            render()
        }
    }

    // ================= Anzeige =================

    private fun render() {
        rows.clear()
        watchRow()?.let { rows.add(it) }
        when (val p = page) {
            is Page.GenrePage -> renderGenre(p.genre)
            Page.ChartsPage -> renderCharts()
            null -> when (tab) {
                Tab.HOME -> renderHome()
                Tab.FIND -> if (query.isEmpty()) renderFind() else renderSearch()
                Tab.KIDS -> renderKids()
                Tab.LIB -> renderLib()
            }
        }
        updateBack()
        adapter.notifyDataSetChanged()
    }

    private fun watchRow(): Row? {
        val q = WatchService.activeQuery(this) ?: return null
        return Row.Action("Wächter sucht „$q“", WatchService.status(this) + " · Tippen zum Beenden", dark = true) {
            WatchService.stop(this)
        }
    }

    private fun liveEntries(stations: List<Station>): List<Entry> =
        stations.mapNotNull { s -> LiveIndex.fresh().firstOrNull { it.station.id == s.id }?.let { s.toEntry(it.title) } }

    private fun liveText(e: Entry): String? =
        if (e.preview) LiveIndex.findSong(e.title, e.subtitle)?.let { "● LIVE auf ${it.station.name}" } else null

    private fun addSongs(list: List<Entry>, ranked: Boolean) {
        list.forEachIndexed { i, e -> rows.add(Row.Item(e, list, liveText(e), if (ranked) i + 1 else null)) }
    }

    private fun renderHome() {
        val live = liveEntries(popular.take(60))
        rows.add(Row.Header("Gerade im Radio", "Was jetzt auf beliebten Sendern läuft"))
        if (live.isEmpty()) rows.add(Row.Empty("Schaue nach, was gerade läuft…")) else rows.add(Row.Carousel(live.take(25), live = true))

        rows.add(Row.Header("Deutsche Charts", "● LIVE = läuft gerade im Radio, komplett hören", "ALLE 100") { openPage(Page.ChartsPage) })
        if (topDe.isEmpty()) rows.add(Row.Empty("Lade Charts…")) else {
            addSongs(topDe.take(10), ranked = true)
            rows.add(Row.More("Alle 100 Charts anzeigen") { openPage(Page.ChartsPage) })
        }

        val chartsLive = topDe.mapNotNull { e -> LiveIndex.findSong(e.title, e.subtitle) }
            .distinctBy { it.station.id }.map { it.station.toEntry(it.title) }
        if (chartsLive.isNotEmpty()) {
            rows.add(Row.Header("Charts gerade im Radio", "Diese Hits laufen jetzt – komplett hören"))
            rows.add(Row.Carousel(chartsLive, live = true))
        }

        val recent = Recent.all(this)
        if (recent.isNotEmpty()) {
            rows.add(Row.Header("Zuletzt gehört"))
            rows.add(Row.Carousel(recent))
        }
        val favs = Favorites.all(this)
        if (favs.isNotEmpty()) {
            rows.add(Row.Header("Deine Favoriten"))
            rows.add(Row.Carousel(favs))
        }
        rows.add(Row.Header("Freie Musik", "Komplett hören, beliebig weiterspringen"))
        if (freeCharts.isEmpty()) rows.add(Row.Empty("Lade Musik…")) else rows.add(Row.Carousel(freeCharts))

        rows.add(Row.Header("Beliebte Sender"))
        if (popular.isEmpty()) rows.add(Row.Empty("Lade Sender…")) else rows.add(Row.Carousel(popular.take(30).map { it.toEntry() }))
    }

    private fun renderFind() {
        rows.add(Row.Header("Musik nach Genre"))
        Genres.all.chunked(2).forEach { rows.add(Row.Tiles(it[0], it.getOrNull(1))) }
        rows.add(Row.Header("So funktioniert die Suche"))
        rows.add(Row.Empty(
            "Gib oben ein Lied ein. Du siehst sofort den Song, ob er gerade im Radio läuft, und freie Musik dazu. " +
                "Läuft er nirgends, startest du den Wächter. Er spielt das Lied ab, sobald es irgendwo kommt."
        ))
    }

    private fun renderSearch() {
        if (scanning) rows.add(Row.Status(statusText(), scanDone, scanTotal))

        rows.add(Row.Header("Läuft gerade im Radio", "Komplett hören"))
        if (radioHits.isEmpty()) {
            rows.add(Row.Empty(if (scanning) "Suche läuft…" else "„$query“ läuft gerade auf keinem der Sender."))
        } else {
            val list = radioHits.toList()
            list.forEach { rows.add(Row.Item(it, list, "● LIVE")) }
        }
        if (WatchService.activeQuery(this) == null) {
            rows.add(Row.Action("Wächter starten", "Ich spiele „$query“ ab, sobald es irgendwo im Radio läuft.", dark = radioHits.isNotEmpty()) {
                WatchService.start(this, query)
                Toast.makeText(this, "Wächter läuft – du kannst die App schließen", Toast.LENGTH_LONG).show()
            })
        }

        rows.add(Row.Header("Songs", "Tippen: im Radio hören, Vorschau oder Wächter"))
        when {
            songLoading -> rows.add(Row.Empty("Suche…"))
            songHits.isEmpty() -> rows.add(Row.Empty("Keine Songs gefunden."))
            else -> addSongs(songHits.take(10), ranked = false)
        }

        if (stationHits.isNotEmpty()) {
            rows.add(Row.Header("Sender"))
            rows.add(Row.Carousel(stationHits))
        }

        rows.add(Row.Header("Freie Musik", "Jamendo · Audius · Internet Archive"))
        when {
            freeLoading -> rows.add(Row.Empty("Suche…"))
            freeHits.isEmpty() -> rows.add(Row.Empty("Nichts gefunden."))
            else -> freeHits.forEach { rows.add(Row.Item(it, freeHits)) }
        }
    }

    private fun renderGenre(g: Genre) {
        rows.add(Row.Hero(g, "Radio live · Top-Songs · Freie Musik"))
        val stations = genreStations[g.key]
        rows.add(Row.Header("Sender", "● LIVE zeigt, was gerade läuft"))
        when {
            stations == null -> rows.add(Row.Empty("Lade Sender…"))
            stations.isEmpty() -> rows.add(Row.Empty("Keine Sender gefunden."))
            else -> {
                val now = LiveIndex.fresh().associateBy { it.station.id }
                val list = stations.take(30).map { s -> now[s.id]?.let { s.toEntry(it.title) } ?: s.toEntry() }
                list.forEach { e -> rows.add(Row.Item(e, list, if (e.subtitle != "Internetradio") "● LIVE" else null)) }
            }
        }
        val songs = genreSongs[g.key].orEmpty()
        if (songs.isNotEmpty()) {
            rows.add(Row.Header("Top-Songs", "Tippen: im Radio hören, Vorschau oder Wächter"))
            addSongs(songs, ranked = true)
        }
        val free = genreFree[g.key].orEmpty()
        if (free.isNotEmpty()) {
            rows.add(Row.Header("Freie Musik", "Komplett hören"))
            free.forEach { rows.add(Row.Item(it, free)) }
        }
    }

    private fun renderCharts() {
        rows.add(Row.Hero(Genres.all.first(), "Die Top 100 in Deutschland"))
        if (topDe.isEmpty()) rows.add(Row.Empty("Lade…")) else addSongs(topDe, ranked = true)
    }

    private fun renderKids() {
        rows.add(Row.Hero(Genres.all.last(), "Kinderradios · Kinder-Hits · Kinderlieder"))
        rows.add(Row.Header("Kinderradios", "Rund um die Uhr Kindermusik"))
        when {
            kidStations.isNotEmpty() -> {
                val now = LiveIndex.fresh().associateBy { it.station.id }
                rows.add(Row.Carousel(kidStations.map { e -> now[e.id.removePrefix("st:")]?.let { Entry(e.id, e.kind, it.title, e.name, e.url, e.artwork, e.name) } ?: e }))
            }
            kidsState == 1 -> rows.add(Row.Empty("Lade…"))
            else -> rows.add(Row.Empty("Keine Kinderradios gefunden."))
        }
        if (kidCharts.isNotEmpty()) {
            rows.add(Row.Header("Kinder-Hits", "Bekannte Kinderlieder"))
            addSongs(kidCharts.take(15), ranked = true)
        }
        rows.add(Row.Header("Kinderlieder zum Durchhören", "Freie Lieder, komplett"))
        when {
            kidSongs.isNotEmpty() -> kidSongs.forEach { rows.add(Row.Item(it, kidSongs)) }
            kidsState == 1 -> rows.add(Row.Empty("Lade…"))
            else -> rows.add(Row.Empty("Keine Lieder gefunden."))
        }
    }

    private fun renderLib() {
        val favs = Favorites.all(this)
        val stations = favs.filter { it.kind == Kind.STATION }
        val tracks = favs.filter { it.kind == Kind.TRACK }
        rows.add(Row.Header("Lieblingssender"))
        if (stations.isEmpty()) rows.add(Row.Empty("Tipp bei einem Sender auf ♥ (bei Kacheln lange drücken).")) else rows.add(Row.Carousel(stations))
        rows.add(Row.Header("Lieblingslieder"))
        if (tracks.isEmpty()) rows.add(Row.Empty("Noch keine.")) else tracks.forEach { rows.add(Row.Item(it, tracks)) }
        val recent = Recent.all(this)
        if (recent.isNotEmpty()) {
            rows.add(Row.Header("Zuletzt gehört"))
            recent.forEach { rows.add(Row.Item(it, recent.filter { r -> r.kind == Kind.TRACK })) }
        }
        rows.add(Row.Header("Einstellungen"))
        val saver = Settings.saver(this)
        rows.add(Row.Action(
            "Datensparen unterwegs: " + if (saver) "AN" else "AUS",
            if (saver) "Mobil werden nur die 150 beliebtesten Sender durchsucht. Im WLAN immer alle."
            else "Mobil werden rund 500 Sender durchsucht (mehr Daten). Tippen zum Einschalten.",
            dark = true
        ) { Settings.setSaver(this, !saver) })
    }

    private fun statusText() =
        if (scanTotal == 0) "Lade Senderliste…"
        else "Durchsuche Radiosender… $scanDone von $scanTotal" +
            if (Net.isUnmetered(this)) " (WLAN)" else " (mobil)"

    private fun updateStatusRow() {
        val idx = rows.indexOfFirst { it is Row.Status }
        if (idx >= 0) {
            rows[idx] = Row.Status(statusText(), scanDone, scanTotal)
            adapter.notifyItemChanged(idx)
        }
    }

    // ================= Abspielen =================

    private fun onEntryTap(e: Entry, section: List<Entry>) {
        if (!e.preview) { play(e, section); return }
        val live = LiveIndex.findSong(e.title, e.subtitle)
        if (live != null) {
            Toast.makeText(this, "Läuft gerade auf ${live.station.name} – los geht's!", Toast.LENGTH_SHORT).show()
            play(live.station.toEntry(live.title), emptyList())
            return
        }
        songDialog(e)
    }

    private fun songDialog(e: Entry) {
        val clean = e.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
        val firstArtist = e.subtitle.split(Regex("[,&]| feat")).first().trim()
        val options = arrayOf(
            "▶   30-Sekunden-Vorschau",
            "🔔   Wächter: abspielen, sobald es im Radio läuft",
            "📻   Jetzt alle Radios durchsuchen",
            "🎵   In Amazon Music öffnen",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("$clean – ${e.subtitle}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> play(e, emptyList())
                    1 -> {
                        WatchService.start(this, "$clean $firstArtist")
                        Toast.makeText(this, "Wächter läuft – ich spiele es ab, sobald es kommt", Toast.LENGTH_LONG).show()
                        render()
                    }
                    2 -> startSearch(clean)
                    3 -> try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.amazon.de/search/" + Uri.encode("$clean $firstArtist"))))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Amazon Music nicht gefunden", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun play(entry: Entry, section: List<Entry>) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player startet noch, bitte nochmal tippen", Toast.LENGTH_SHORT).show()
            return
        }
        if (entry.kind == Kind.TRACK && !entry.preview) {
            val tracks = section.filter { it.kind == Kind.TRACK && !it.preview }
            val idx = tracks.indexOfFirst { it.id == entry.id }
            if (idx >= 0) c.setMediaItems(tracks.map { it.toMediaItem() }, idx, 0L) else c.setMediaItem(entry.toMediaItem())
        } else {
            c.setMediaItem(entry.toMediaItem())
        }
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0 -> { c.prepare(); c.play() }
            else -> c.play()
        }
    }

    private fun sleepDialog() {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = arrayOf("Aus", "15 Minuten", "30 Minuten", "45 Minuten", "1 Stunde", "1,5 Stunden")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Schlaf-Timer")
            .setItems(labels) { _, which ->
                val args = Bundle().apply { putInt("min", minutes[which]) }
                controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY), args)
                Toast.makeText(this, if (minutes[which] == 0) "Timer aus" else "Musik stoppt in ${labels[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showFull(show: Boolean) {
        if (show && controller?.currentMediaItem == null) return
        full.visibility = if (show) View.VISIBLE else View.GONE
        updateBack()
        if (show) updatePlayer()
        if (!show) findViewById<EqualizerView>(R.id.fullEq).playing = false
    }

    private fun updatePlayer() {
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            mini.visibility = View.GONE
            if (full.visibility == View.VISIBLE) showFull(false)
            return
        }
        mini.visibility = View.VISIBLE
        val m = item.mediaMetadata
        val entry = Entry.fromMediaItem(item)
        val isStation = item.mediaId.startsWith("st:")
        val live = c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
        val title = live ?: m.title?.toString() ?: ""
        val sub = when {
            c.playbackState == Player.STATE_BUFFERING -> "Verbinde…"
            c.playerError != null -> "Nicht erreichbar – tippe auf Play"
            isStation -> entry?.name ?: (m.artist?.toString() ?: "")
            entry?.preview == true -> "${m.artist ?: ""} · Vorschau"
            else -> m.artist?.toString() ?: ""
        }
        val playing = c.isPlaying || (c.playbackState == Player.STATE_BUFFERING && c.playWhenReady)
        val playIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val isFav = Favorites.isFav(this, item.mediaId)
        val art = m.artworkUri?.toString().orEmpty()
        val name = entry?.name ?: title

        findViewById<TextView>(R.id.miniTitle).text = title
        findViewById<TextView>(R.id.miniSub).text = sub
        findViewById<ImageButton>(R.id.miniPlay).setImageResource(playIcon)
        findViewById<EqualizerView>(R.id.miniEq).playing = c.isPlaying
        findViewById<View>(R.id.miniNext).visibility = if (c.hasNextMediaItem()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.miniProgress).visibility = if (isStation) View.INVISIBLE else View.VISIBLE
        bindArt(findViewById(R.id.miniArt), findViewById(R.id.miniInitials), art, name, isStation, 12f)

        if (full.visibility == View.VISIBLE) {
            findViewById<TextView>(R.id.fullTitle).text = title
            findViewById<TextView>(R.id.fullSub).text = sub
            findViewById<TextView>(R.id.fullSource).text = when {
                isStation -> "INTERNETRADIO"
                entry?.preview == true -> "VORSCHAU"
                else -> "FREIE MUSIK"
            }
            findViewById<View>(R.id.fullBadge).visibility = if (isStation) View.VISIBLE else View.GONE
            findViewById<ImageButton>(R.id.fullPlay).setImageResource(playIcon)
            findViewById<ImageButton>(R.id.fullFav).setImageResource(if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            findViewById<View>(R.id.fullNext).visibility = if (c.hasNextMediaItem()) View.VISIBLE else View.GONE
            findViewById<View>(R.id.fullPrev).visibility = if (c.hasPreviousMediaItem()) View.VISIBLE else View.GONE
            findViewById<EqualizerView>(R.id.fullEq).apply { bars = 9; this.playing = c.isPlaying }
            findViewById<View>(R.id.fullSeek).visibility = if (isStation) View.INVISIBLE else View.VISIBLE
            bindArt(findViewById(R.id.fullArt), findViewById(R.id.fullInitials), art, name, isStation, 24f)
            val bg = findViewById<ImageView>(R.id.fullBg)
            if (art.isNotBlank()) bg.load(art) { crossfade(true) } else bg.setImageDrawable(null)
        }
        updateProgress()
    }

    /** Laufleiste und Zeiten, alle halbe Sekunde. */
    private fun updateProgress() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val isStation = item.mediaId.startsWith("st:")
        val pos = c.currentPosition.coerceAtLeast(0)
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

        val miniP = findViewById<ProgressBar>(R.id.miniProgress)
        if (!isStation && dur > 0) { miniP.max = dur.toInt(); miniP.progress = pos.toInt() }

        val sleepEnd = Settings.sleepEnd(this)
        val sleepLeft = sleepEnd - System.currentTimeMillis()
        findViewById<TextView>(R.id.fullSleep).text =
            if (sleepEnd > 0 && sleepLeft > 0) "☾ ${(sleepLeft / 60_000) + 1} min" else "☾ Timer"

        if (full.visibility != View.VISIBLE) return
        val seek = findViewById<SeekBar>(R.id.fullSeek)
        val posText = findViewById<TextView>(R.id.fullPos)
        val durText = findViewById<TextView>(R.id.fullDur)
        if (isStation) {
            posText.text = "Läuft seit ${fmt(pos)}"
            durText.text = "LIVE"
        } else {
            if (!seeking) {
                seek.max = dur.toInt().coerceAtLeast(1)
                seek.progress = pos.toInt()
                posText.text = fmt(pos)
            }
            durText.text = if (dur > 0) "-" + fmt(dur - pos) else ""
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ================= Bilder =================

    private val palette = listOf(
        "#FF7C3AED", "#FFDB2777", "#FFEA580C", "#FF0891B2", "#FF16A34A", "#FF2563EB", "#FFCA8A04", "#FFDC2626"
    )

    private fun initials(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "♪"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun gradient(c1: String, c2: String, radiusDp: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(c1), Color.parseColor(c2)))
            .apply { cornerRadius = dp(radiusDp) }

    private fun bindArt(image: ImageView, initialsView: TextView, url: String, name: String, station: Boolean, radiusDp: Float) {
        val color = Color.parseColor(palette[Math.floorMod(name.hashCode(), palette.size)])
        initialsView.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color, Color.rgb((Color.red(color) * 0.55f).toInt(), (Color.green(color) * 0.55f).toInt(), (Color.blue(color) * 0.55f).toInt()))
        ).apply { cornerRadius = dp(radiusDp) }
        initialsView.text = initials(name)
        initialsView.clipToOutline = true

        image.clipToOutline = true
        image.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        image.dispose()
        image.setImageDrawable(null)
        image.background = null
        image.setPadding(0, 0, 0, 0)
        if (url.isBlank()) return

        image.load(url) {
            crossfade(true)
            listener(onSuccess = { _, _ ->
                if (station) {
                    image.background = GradientDrawable().apply { setColor(Color.parseColor("#FFF5F5F7")); cornerRadius = dp(radiusDp) }
                    image.scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = (image.width * 0.12f).toInt().coerceAtLeast(4)
                    image.setPadding(p, p, p, p)
                } else {
                    image.background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(radiusDp) }
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                }
            })
        }
    }

    // ================= Listen-Adapter =================

    private class VH(v: View) : RecyclerView.ViewHolder(v)

    private inner class MainAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Carousel -> 1
            is Row.Item -> 2
            is Row.Status -> 3
            is Row.Action -> 4
            is Row.Empty -> 5
            is Row.Tiles -> 6
            is Row.Hero -> 7
            is Row.More -> 8
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = when (viewType) {
                0 -> R.layout.item_header
                1 -> R.layout.item_carousel
                2 -> R.layout.item_row
                3 -> R.layout.item_status
                4 -> R.layout.item_action
                6 -> R.layout.item_tiles
                7 -> R.layout.item_hero
                8 -> R.layout.item_more
                else -> R.layout.item_empty
            }
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            if (viewType == 1) {
                val rv = v as RecyclerView
                rv.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                rv.adapter = CardAdapter()
                rv.itemAnimator = null
            }
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = holder.itemView
            when (val row = rows[position]) {
                is Row.Header -> {
                    v.findViewById<TextView>(R.id.title).text = row.title
                    val sub = v.findViewById<TextView>(R.id.sub)
                    sub.text = row.sub ?: ""
                    sub.visibility = if (row.sub == null) View.GONE else View.VISIBLE
                    val more = v.findViewById<TextView>(R.id.more)
                    more.text = row.more ?: ""
                    more.visibility = if (row.more == null) View.GONE else View.VISIBLE
                    more.setOnClickListener { row.onMore?.invoke() }
                }
                is Row.Carousel -> {
                    val ca = (v as RecyclerView).adapter as CardAdapter
                    if (ca.entries != row.entries || ca.live != row.live) {
                        ca.entries = row.entries
                        ca.live = row.live
                        ca.notifyDataSetChanged()
                    }
                }
                is Row.Item -> bindRow(v, row)
                is Row.Status -> {
                    v.findViewById<TextView>(R.id.text).text = row.text
                    val p = v.findViewById<ProgressBar>(R.id.progress)
                    p.isIndeterminate = row.total == 0
                    p.max = row.total.coerceAtLeast(1)
                    p.progress = row.done
                }
                is Row.Action -> {
                    v.findViewById<TextView>(R.id.title).text = row.title
                    v.findViewById<TextView>(R.id.sub).text = row.sub
                    v.setBackgroundResource(if (row.dark) R.drawable.bg_action_dark else R.drawable.bg_action)
                    v.setOnClickListener { row.onClick() }
                }
                is Row.Empty -> (v as TextView).text = row.text
                is Row.Tiles -> {
                    bindTile(v.findViewById(R.id.tile1), row.g1)
                    val t2 = v.findViewById<View>(R.id.tile2)
                    if (row.g2 != null) { t2.visibility = View.VISIBLE; bindTile(t2, row.g2) } else t2.visibility = View.INVISIBLE
                }
                is Row.Hero -> {
                    v.background = gradient(row.genre.color1, row.genre.color2, 22f)
                    v.findViewById<ImageView>(R.id.heroIcon).setImageResource(row.genre.icon)
                    v.findViewById<TextView>(R.id.heroTitle).text = row.genre.name
                    v.findViewById<TextView>(R.id.heroSub).text = row.sub
                }
                is Row.More -> {
                    (v as TextView).text = row.text
                    v.setOnClickListener { row.onClick() }
                }
            }
        }

        private fun bindTile(t: View, g: Genre) {
            t.background = gradient(g.color1, g.color2, 16f)
            t.clipToOutline = true
            t.findViewById<ImageView>(R.id.tileIcon).setImageResource(g.icon)
            t.findViewById<TextView>(R.id.tileName).text = g.name
            t.setOnClickListener { openPage(Page.GenrePage(g)) }
        }

        private fun bindRow(v: View, row: Row.Item) {
            val e = row.entry
            bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION, 12f)
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.subtitle).text = if (e.preview && row.liveText == null) "${e.subtitle} · Vorschau" else e.subtitle
            val badge = v.findViewById<TextView>(R.id.badge)
            badge.visibility = if (row.liveText != null) View.VISIBLE else View.GONE
            badge.text = row.liveText ?: ""
            val rank = v.findViewById<TextView>(R.id.rank)
            rank.visibility = if (row.rank != null) View.VISIBLE else View.GONE
            rank.text = row.rank?.toString() ?: ""
            val fav = v.findViewById<ImageButton>(R.id.fav)
            fav.visibility = if (e.preview) View.GONE else View.VISIBLE
            fav.setImageResource(if (Favorites.isFav(this@MainActivity, e.id)) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            fav.setOnClickListener { Favorites.toggle(this@MainActivity, e) }
            v.setOnClickListener { onEntryTap(e, row.section) }
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<VH>() {
        var entries: List<Entry> = emptyList()
        var live = false

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = holder.itemView
            val e = entries[position]
            bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION, 16f)
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.subtitle).text = e.subtitle
            v.findViewById<View>(R.id.badge).visibility = if (live) View.VISIBLE else View.GONE
            v.setOnClickListener { onEntryTap(e, entries) }
            v.setOnLongClickListener {
                if (e.preview) return@setOnLongClickListener false
                val now = Favorites.toggle(this@MainActivity, e)
                Toast.makeText(this@MainActivity, if (now) "♥ Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}
