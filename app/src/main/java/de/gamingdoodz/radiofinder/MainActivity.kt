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
import android.widget.Switch
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

    private enum class Tab { HOME, FIND, STORIES, LIB, SETTINGS }

    private sealed class Page {
        data class GenrePage(val genre: Genre) : Page()
        object RadioChartsPage : Page()
        data class PlaylistPage(val name: String, val query: String?, val own: Boolean) : Page()
        object ChartsPage : Page()
        object GenresPage : Page()
        data class ShowPage(val show: Entry) : Page()
    }

    private sealed class Row {
        data class Header(val title: String, val sub: String? = null, val more: String? = null, val onMore: (() -> Unit)? = null) : Row()
        data class Carousel(val entries: List<Entry>, val live: Boolean = false) : Row()
        data class Item(val entry: Entry, val section: List<Entry>, val liveText: String? = null, val rank: Int? = null, val meta: String? = null) : Row()
        data class Status(val text: String, val done: Int, val total: Int) : Row()
        data class Action(val title: String, val sub: String, val dark: Boolean, val icon: Int = R.drawable.ic_bell, val onClick: () -> Unit) : Row()
        data class Moments(val list: List<de.gamingdoodz.radiofinder.Moments.Moment>) : Row()
        data class Quick(val items: List<Triple<Int, String, () -> Unit>>) : Row()
        data class Segment(val labels: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : Row()
        data class Top(val entry: Entry, val label: String, val sub: String, val onClick: () -> Unit) : Row()
        data class Chips(val labels: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : Row()
        data class Empty(val text: String) : Row()
        data class Tiles(val g1: Genre, val g2: Genre?) : Row()
        data class Hero(val genre: Genre, val sub: String) : Row()
        data class More(val text: String, val onClick: () -> Unit) : Row()
        data class Label(val text: String) : Row()
        data class About(val title: String, val sub: String, val status: String, val onClick: () -> Unit) : Row()
        /** Eine Zeile in den Einstellungen. pos: 0 einzeln, 1 oben, 2 Mitte, 3 unten. */
        data class Setting(
            val icon: Int, val title: String, val sub: String? = null, val value: String? = null,
            val toggle: Boolean? = null, val chevron: Boolean = true, val pos: Int = 0, val onClick: () -> Unit,
        ) : Row()
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

    // Geschichten
    private val storyShows = HashMap<String, List<Entry>>()
    private val storyLoading = HashSet<String>()
    private val episodes = HashMap<String, List<Entry>>()
    private var artistMix: Deezer.Artist? = null

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

    /** Hell/Dunkel und Schriftgröße aus den Einstellungen anwenden. */
    override fun attachBaseContext(newBase: Context) {
        val cfg = android.content.res.Configuration(newBase.resources.configuration)
        when (Settings.theme(newBase)) {
            1 -> cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_NO
            2 -> cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        cfg.fontScale = cfg.fontScale * Settings.textScale(newBase)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Radar.init(this)
        lifecycleScope.launch { Blocklist.refresh(applicationContext) }
        lifecycleScope.launch { Radar.loadAds() }
        lifecycleScope.launch { if (Radar.sync(force = true)) render() }
        // Beim allerersten Start kurz erklären, wie die App funktioniert
        if (!Settings.prefs(this).getBoolean("intro_seen", false)) {
            Settings.prefs(this).edit().putBoolean("intro_seen", true).apply()
            window.decorView.post { intro(0) }
        }
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backCallback)

        input = findViewById(R.id.search)
        clear = findViewById(R.id.clear)
        pageTitle = findViewById(R.id.pageTitle)
        back = findViewById(R.id.back)
        searchBox = findViewById(R.id.searchBox)
        recycler = findViewById(R.id.recycler)
        // Nach unten ziehen: alles neu laden
        findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.refresh).apply {
            setColorSchemeColors(getColor(R.color.c_ff2ee6a8))
            setProgressBackgroundColorSchemeColor(getColor(R.color.c_ff111d2a))
            setOnRefreshListener {
                lifecycleScope.launch {
                    Radar.sync(force = true)
                    radioChartsAt = 0L
                    playlistTried.clear(); playlistCoversTried = false
                    if (tab == Tab.HOME && page == null) loadHome()
                    render()
                    isRefreshing = false
                }
            }
        }
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
                findViewById<View>(R.id.mic).visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                typing.removeCallbacks(typeSearch)
                if ((s?.trim()?.length ?: 0) >= 3 && input.hasFocus()) typing.postDelayed(typeSearch, 700)
            }
        })
        clear.setOnClickListener { input.setText(""); startSearch() }
        findViewById<View>(R.id.mic).setOnClickListener { listen(wish = false) }
        // ✨ Wünsch dir was: mit Text = Wunsch erfüllen, ohne Text = sprechen
        findViewById<View>(R.id.wishBtn).visibility = if (kids()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.wishBtn).setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) handleWish(t) else listen(wish = true)
        }
        findViewById<View>(R.id.fullStopMix).setOnClickListener { stopHitMix(); updatePlayer() }
        back.setOnClickListener { backCallback.handleOnBackPressed() }

        findViewById<View>(R.id.navHome).setOnClickListener { selectTab(Tab.HOME) }
        findViewById<View>(R.id.navFind).setOnClickListener { selectTab(Tab.FIND) }
        findViewById<View>(R.id.navStories).setOnClickListener { selectTab(Tab.STORIES) }
        findViewById<View>(R.id.navLib).setOnClickListener { selectTab(Tab.LIB) }
        findViewById<View>(R.id.navSettings).setOnClickListener { selectTab(Tab.SETTINGS) }

        mini.setOnClickListener { showFull(true) }
        findViewById<View>(R.id.miniPlay).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.miniNext).setOnClickListener { next() }

        findViewById<View>(R.id.fullClose).setOnClickListener { showFull(false) }
        findViewById<View>(R.id.fullPlay).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.fullNext).setOnClickListener { next() }
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
        findViewById<View>(R.id.fullCast).setOnClickListener { castDialog() }
        findViewById<View>(R.id.fullBack30).setOnClickListener { controller?.seekBack() }
        findViewById<View>(R.id.fullAgain).setOnClickListener { playAgain() }
        findViewById<View>(R.id.fullShare).setOnClickListener { shareSong() }
        findViewById<View>(R.id.fullSimilar).setOnClickListener { currentSongTitle()?.let { moreLike(it) } }
        findViewById<View>(R.id.fullFwd30).setOnClickListener { controller?.seekForward() }
        findViewById<View>(R.id.fullSpeed).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val speeds = listOf(1f, 1.25f, 1.5f, 0.8f)
            val i = speeds.indexOfFirst { kotlin.math.abs(it - c.playbackParameters.speed) < 0.01f }
            c.setPlaybackSpeed(speeds[(i + 1) % speeds.size])
            updatePlayer()
        }
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
        // Gewählter Start-Reiter
        if (savedInstanceState == null && Settings.startTab(this) != 0 && !kids()) {
            selectTab(listOf(Tab.HOME, Tab.FIND, Tab.STORIES, Tab.LIB)[Settings.startTab(this).coerceIn(0, 3)])
        }
    }

    override fun onStart() {
        super.onStart()
        listOf(Favorites.prefs(this), WatchService.prefs(this), Recent.prefs(this), Settings.prefs(this), HitMix.prefs(this))
            .forEach { it.registerOnSharedPreferenceChangeListener(prefsListener) }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (_: Exception) { return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    updatePlayer()
                }
            })
            updatePlayer()
        }, ContextCompat.getMainExecutor(this))
        ticker.post(tick)
        Jamendo.highQuality = Settings.highQuality(this)
        startLiveRefresh()
        checkUpdate()
        // Wunschliste vorhanden, aber Wächter läuft nicht mehr (z. B. nach Neustart): weitermachen
        val wishes = WatchService.queries(this)
        if (wishes.isNotEmpty() && WatchService.status(this).isEmpty()) {
            if (WatchService.expired(this)) WatchService.stop(this)
            else try { WatchService.start(this, wishes.first()) } catch (_: Exception) {}
        }
        render()
    }

    override fun onStop() {
        listOf(Favorites.prefs(this), WatchService.prefs(this), Recent.prefs(this), Settings.prefs(this), HitMix.prefs(this))
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
            val ch = async { Deezer.kidsHits() }
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

    private val freeTags = FREE_TAGS.filter { it.second != "kids" }
    private var freeTag = 0
    private val freeByTag = HashMap<String, List<Entry>>()

    private fun loadFreeTag() {
        val tag = freeTags[freeTag].second
        if (freeByTag.containsKey(tag)) return
        freeByTag[tag] = emptyList()
        lifecycleScope.launch {
            freeByTag[tag] = Jamendo.many(tag)
            render()
        }
    }

    private fun loadStories(cats: List<Podcasts.Category>) {
        cats.filter { !storyShows.containsKey(it.key) && storyLoading.add(it.key) }.forEach { cat ->
            lifecycleScope.launch {
                storyShows[cat.key] = Podcasts.shows(cat)
                storyLoading.remove(cat.key)
                render()
            }
        }
    }

    private fun loadEpisodes(show: Entry) {
        if (episodes.containsKey(show.url)) return
        lifecycleScope.launch {
            episodes[show.url] = Podcasts.episodes(show)
            render()
        }
    }

    private fun loadGenre(g: Genre) {
        if (genreStations.containsKey(g.key) || !genreLoading.add(g.key)) return
        lifecycleScope.launch {
            val st = async { StationRepository.byTag(g.radioTag) }
            val songs = async {
                when {
                    g.playlist != null -> Deezer.playlistByName(g.playlist).take(40)
                    g.deezerGenre != null -> Deezer.genreChart(g.deezerGenre, 30)
                    else -> emptyList()
                }
            }
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
        if (tab != t) { recycler.alpha = 0.3f; recycler.animate().alpha(1f).setDuration(180).start() }
        tab = t
        page = null
        val accent = getColor(R.color.c_ff2ee6a8)
        val dim = getColor(R.color.c_80ffffff)
        listOf(
            Triple(Tab.HOME, R.id.navHomeIcon, R.id.navHomeLabel),
            Triple(Tab.FIND, R.id.navFindIcon, R.id.navFindLabel),
            Triple(Tab.STORIES, R.id.navStoriesIcon, R.id.navStoriesLabel),
            Triple(Tab.LIB, R.id.navLibIcon, R.id.navLibLabel),
            Triple(Tab.SETTINGS, R.id.navSettingsIcon, R.id.navSettingsLabel),
        ).forEach { (key, icon, label) ->
            val color = if (key == t) accent else dim
            findViewById<ImageView>(icon).imageTintList = ColorStateList.valueOf(color)
            findViewById<TextView>(label).setTextColor(color)
        }
        if (kids() && (t == Tab.HOME || t == Tab.LIB || t == Tab.SETTINGS)) { selectTab(Tab.FIND); return }
        if (kids()) { musicAudience = 1; storyAudience = 1 }
        findViewById<View>(R.id.navHome).visibility = if (kids()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.navLib).visibility = if (kids()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.navSettings).visibility = if (kids()) View.GONE else View.VISIBLE
        if (t == Tab.FIND && musicAudience == 1) loadKids()
        if (t == Tab.STORIES) loadStories(Podcasts.all)
        render()
        recycler.scrollToPosition(0)
    }

    private fun openPage(p: Page) {
        page = p
        if (p is Page.GenrePage) loadGenre(p.genre)
        if (p is Page.ShowPage) loadEpisodes(p.show)
        render()
        recycler.scrollToPosition(0)
    }

    private fun updateBack() {
        back.visibility = if (page != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.brand).visibility = if (page == null && tab == Tab.HOME) View.VISIBLE else View.GONE
        searchBox.visibility = if ((tab == Tab.FIND || tab == Tab.HOME) && page == null && !kids()) View.VISIBLE else View.GONE
        val chip = findViewById<TextView>(R.id.liveChip)
        val n = LiveIndex.fresh().size
        chip.visibility = if (page == null && tab == Tab.HOME && n > 0) View.VISIBLE else View.GONE
        chip.text = "● $n live"
        backCallback.isEnabled = page != null || full.visibility == View.VISIBLE
        pageTitle.text = when (val p = page) {
            is Page.GenrePage -> p.genre.name
            Page.ChartsPage -> "Deutsche Charts"
            Page.RadioChartsPage -> "Meistgespielt im Radio"
            is Page.PlaylistPage -> p.name
            Page.GenresPage -> "Alle Genres"
            is Page.ShowPage -> p.show.name
            null -> when (tab) {
                Tab.HOME -> "Hitradar"
                Tab.FIND -> if (kids()) "Kinder-Musik" else "Musik"
                Tab.STORIES -> if (kids()) "Kinder-Geschichten" else "Hörspiele"
                Tab.LIB -> "Bibliothek"
                Tab.SETTINGS -> "Einstellungen"
            }
        }
    }

    // ================= Suche =================

    private val typing = Handler(Looper.getMainLooper())
    private val typeSearch = Runnable { startSearch(deep = false) }
    private var deepSearch = false

    /** deep = auch alle Radios durchsuchen (beim Tippen nur die schnellen Teile). */
    private fun startSearch(text: String? = null, deep: Boolean = true) {
        if (text != null) input.setText(text)
        typing.removeCallbacks(typeSearch)
        val q = input.text.toString().trim()
        if (deep) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        deepSearch = deep
        expanded.clear()
        searchJob?.cancel()
        val myId = ++searchId
        if (q != query) searchFilter = 0 // neue Suche: wieder alles zeigen
        query = q
        songHits = emptyList()
        artistMix = null
        radioHits.clear()
        stationHits = emptyList()
        freeHits = emptyList()
        scanning = false
        songLoading = false
        freeLoading = false
        if (tab != Tab.FIND) { selectTab(Tab.FIND); if (!deep) input.requestFocus() } else { page = null; render() }
        if (q.isEmpty()) return

        scanning = deep
        songLoading = true
        freeLoading = true
        scanDone = 0
        scanTotal = 0
        // Sofort: was die App schon weiß
        LiveIndex.find(q).forEach { radioHits.add(it.station.toEntry(it.title)) }
        render()

        searchJob = lifecycleScope.launch {
            launch {
                val a = Deezer.artist(q)
                if (myId == searchId) { artistMix = a; render() }
            }
            launch {
                val songs = Deezer.search(q)
                if (myId == searchId) { songHits = songs; songLoading = false; render() }
            }
            if (deep) launch {
                val st = Podcasts.search(q, limit = 12)
                if (myId == searchId) { storyShows["search"] = st; render() }
            }
            launch {
                val free = FreeMusic.search(q, quick = !deep)
                if (myId == searchId) { freeHits = free; freeLoading = false; render() }
            }
            val all = try { StationRepository.scanList(applicationContext) } catch (_: Exception) { emptyList() }
            if (myId != searchId) return@launch
            if (!deep) {
                val words = Scanner.words(q)
                stationHits = all.filter { Scanner.matches(it.name, words) }.take(8).map { it.toEntry() }
                render()
                Radar.stations(q)?.let { if (myId == searchId && it.isNotEmpty()) { stationHits = it; render() } }
                return@launch
            }
            val words = Scanner.words(q)
            stationHits = all.filter { Scanner.matches(it.name, words) }.take(8).map { it.toEntry() }
            launch { Radar.stations(q)?.let { if (myId == searchId && it.isNotEmpty()) { stationHits = it; render() } } }
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

    private fun kids() = Settings.kidsMode(this)

    private fun render() {
        rows.clear()
        if (kids()) {
            rows.add(Row.Action("Kinder-Modus", "Nur Kinderinhalte · Tippen und PIN eingeben zum Beenden", dark = true, icon = R.drawable.ic_kids) { exitKidsMode() })
        } else {
            updateRow?.let { rows.add(it) }
        }
        updateStrip()
        when (val p = page) {
            is Page.GenrePage -> renderGenre(p.genre)
            Page.ChartsPage -> renderCharts()
            Page.RadioChartsPage -> renderRadioCharts()
            is Page.PlaylistPage -> renderPlaylist(p)
            Page.GenresPage -> Genres.all.filter { it.key != "kids" }.chunked(2).forEach { rows.add(Row.Tiles(it[0], it.getOrNull(1))) }
            is Page.ShowPage -> renderShow(p.show)
            null -> when (tab) {
                Tab.HOME -> renderHome()
                Tab.FIND -> if (query.isEmpty()) renderFind() else renderSearch()
                Tab.STORIES -> renderStories()
                Tab.LIB -> renderLib()
                Tab.SETTINGS -> renderSettings()
            }
        }
        updateBack()
        adapter.notifyDataSetChanged()
    }

    private fun watchRow(): Row? {
        val q = WatchService.activeQuery(this) ?: return null
        return Row.Action("Wächter: „$q“", WatchService.status(this) + " · Tippen zum Verwalten", dark = true) {
            watchSheet()
        }
    }

    // ---------- Kinder-Modus ----------

    private fun pinDialog(title: String, onPin: (String) -> Unit) {
        val et = EditText(this)
        et.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "4 Ziffern"
        et.textSize = 22f
        et.gravity = android.view.Gravity.CENTER
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("OK") { _, _ -> onPin(et.text.toString().trim()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** In einer Musik-/Hörbuch-App öffnen: eigene Dienste, bei nur einem direkt. */
    private fun openIn(query: String, stories: Boolean) {
        val list = Services.forUse(this, stories)
        fun go(sv: Services.Service) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Services.url(sv, query)))) }
            catch (_: Exception) { Toast.makeText(this, "${sv.name} konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show() }
        }
        if (list.size == 1) { go(list[0]); return }
        sheet("Öffnen in …", query, null, "", false,
            null,
            list.map { sv -> Option(R.drawable.ic_note, sv.name, null) { go(sv) } })
    }

    private fun servicesSheet() {
        val mine = Services.mine(this)
        sheet("Meine Musik-Apps", "Zum Öffnen von Liedern und Hörspielen", null, "", false,
            "Ohne Auswahl zeigt Hitradar automatisch die Apps, die auf deinem Handy installiert sind. Antippen, um selbst auszuwählen.",
            Services.all.sortedByDescending { Services.installed(this, it) }.map { sv ->
                val inst = Services.installed(this, sv)
                Option(if (sv.key in mine) R.drawable.ic_heart else R.drawable.ic_heart_outline, sv.name,
                    listOfNotNull(if (sv.key in mine) "Ausgewählt" else null, if (inst) "installiert" else null).joinToString(" · ").ifEmpty { null }) {
                    Services.toggle(this, sv.key); servicesSheet(); render()
                }
            })
    }

    /** Bekannte Hörspiele sind nicht frei – mit einem Tipp in der eigenen App öffnen. */
    private fun famousSheet(kidsList: Boolean) {
        val titles = if (kidsList) listOf(
            "Bibi Blocksberg", "Bibi & Tina", "Benjamin Blümchen", "Paw Patrol", "Conni",
            "Die Schule der magischen Tiere", "Peppa Wutz", "Feuerwehrmann Sam", "Die Olchis", "TKKG Junior"
        ) else listOf(
            "Die drei ???", "TKKG", "Harry Potter Hörbuch", "Der Herr der Ringe Hörspiel", "Sherlock Holmes Hörspiel",
            "John Sinclair", "Point Whitmark", "Gruselkabinett"
        )
        sheet("Bekannte Serien", "Öffnet die Suche in deiner Musik- oder Hörbuch-App", null, "", false,
            "Diese Hörspiele sind nicht frei, du findest sie z. B. in Spotify, Amazon Music, Apple Music oder Audible.",
            titles.map { t -> Option(R.drawable.ic_book, t, null) { openIn(t, stories = true) } })
    }

    private fun startKidsMode() {
        pinDialog("PIN für den Kinder-Modus festlegen") { pin ->
            if (pin.length < 4) {
                Toast.makeText(this, "Bitte 4 Ziffern eingeben", Toast.LENGTH_SHORT).show(); return@pinDialog
            }
            Settings.setPin(this, pin)
            Settings.setKidsMode(this, true)
            musicAudience = 1; storyAudience = 1; storySection = 0
            query = ""; input.setText("")
            selectTab(Tab.FIND)
        }
    }

    private fun exitKidsMode() {
        pinDialog("PIN eingeben") { pin ->
            if (pin == Settings.pin(this)) {
                Settings.setKidsMode(this, false)
                musicAudience = 0; storyAudience = 0
                selectTab(Tab.HOME)
            } else {
                Toast.makeText(this, "Falsche PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Einschlaf-Modus ----------

    private fun bedtimeSheet() {
        val cat = Podcasts.kidsCategories.first()
        val shows = storyShows[cat.key].orEmpty().take(6)
        if (shows.isEmpty()) {
            loadStories(listOf(cat))
            Toast.makeText(this, "Lade Geschichten, bitte gleich nochmal tippen", Toast.LENGTH_SHORT).show(); return
        }
        sheet("Einschlaf-Modus", "Welche Geschichte?", null, "", false,
            "Danach leise Schlaflieder, nach 45 Minuten wird es still.",
            shows.map { show -> Option(R.drawable.ic_book, show.name, show.subtitle) { startBedtime(show) } })
    }

    private fun startBedtime(show: Entry) {
        lifecycleScope.launch {
            val eps = Podcasts.episodes(show)
            val c = controller ?: return@launch
            if (eps.isEmpty()) { Toast.makeText(this@MainActivity, "Keine Folgen gefunden", Toast.LENGTH_SHORT).show(); return@launch }
            // Nächste noch nicht gehörte Folge (beim ersten Mal Folge 1), danach Schlaflieder
            val ep = eps[Positions.nextIndex(this@MainActivity, eps)]
            c.setMediaItem(ep.toMediaItem(), 0L)
            c.prepare()
            c.play()
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_BEDTIME, Bundle.EMPTY), Bundle().apply { putInt("min", 45) })
            Toast.makeText(this@MainActivity, "Gute Nacht! 🌙", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Radio-Wecker ----------

    /** Android 12+: Erlaubnis für pünktliche Wecker holen (einmalig). */
    private fun ensureExactAlarm() {
        if (Build.VERSION.SDK_INT < 31) return
        val am = getSystemService(android.app.AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) return
        Toast.makeText(this, "Bitte „Wecker und Erinnerungen“ für Hitradar erlauben", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun alarmSheet() {
        if (Settings.alarmOn(this)) ensureExactAlarm()
        val on = Settings.alarmOn(this)
        sheet("Radio-Wecker", if (on) "Klingelt um %02d:%02d Uhr".format(Settings.alarmHour(this), Settings.alarmMinute(this)) else "Aus", null, "", false,
            "Die Musik startet leise und wird in einer Minute lauter.",
            listOfNotNull(
                Option(R.drawable.ic_bell, "Uhrzeit wählen", "%02d:%02d Uhr".format(Settings.alarmHour(this), Settings.alarmMinute(this))) {
                    android.app.TimePickerDialog(this, android.R.style.Theme_Material_Dialog_Alert, { _, h, m ->
                        Settings.setAlarm(this, on = true, hour = h, minute = m)
                        ensureExactAlarm()
                        Toast.makeText(this, "Wecker gestellt: %02d:%02d Uhr".format(h, m), Toast.LENGTH_SHORT).show()
                    }, Settings.alarmHour(this), Settings.alarmMinute(this), true).show()
                },
                Option(R.drawable.ic_radio, "Was soll spielen?", Settings.alarmLabel(this)) { alarmSourceSheet() },
                Option(R.drawable.ic_sun, if (Settings.alarmWeekdays(this)) "Nur Mo–Fr (tippen für jeden Tag)" else "Jeden Tag (tippen für nur Mo–Fr)", null) {
                    Settings.setAlarm(this, weekdays = !Settings.alarmWeekdays(this))
                },
                if (on) Option(R.drawable.ic_close, "Wecker ausschalten", null) { Settings.setAlarm(this, on = false) }
                else Option(R.drawable.ic_play, "Wecker einschalten", null) { Settings.setAlarm(this, on = true) },
            )
        )
    }

    private fun alarmSourceSheet() {
        val opts = mutableListOf(
            Option(R.drawable.ic_sun, "Guten-Morgen-Mix", "Gute-Laune-Hits") { Settings.setAlarm(this, source = "hitmix:pop", label = "Guten-Morgen-Mix") },
            Option(R.drawable.ic_star, "Charts-Mix", "Aktuelle Hits") { Settings.setAlarm(this, source = "hitmix:", label = "Charts-Mix") },
        )
        (Favorites.all(this) + Recent.all(this)).filter { it.kind == Kind.STATION }.distinctBy { it.id }.take(6).forEach { st ->
            opts.add(Option(R.drawable.ic_radio, st.name, "Sender") { Settings.setAlarm(this, source = st.toJson().toString(), label = st.name) })
        }
        sheet("Was soll spielen?", "Beim Wecken", null, "", false, null, opts)
    }

    // ---------- Updates ----------

    private var updateRow: Row? = null
    private var updateChecked = false

    /** Schaut auf GitHub nach einer neueren Version. */
    private fun checkUpdate() {
        if (updateChecked) return
        updateChecked = true
        // Aus dem Play Store installiert? Dann kümmert sich Google Play um Updates.
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(packageName).installingPackageName
            else @Suppress("DEPRECATION") packageManager.getInstallerPackageName(packageName)
        } catch (_: Exception) { null }
        if (installer == "com.android.vending") return
        lifecycleScope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Net.get("https://api.github.com/repos/gamingdoodz/radio-finder/releases/latest")
            } ?: return@launch
            try {
                val o = org.json.JSONObject(text)
                val latest = o.optString("tag_name").removePrefix("build-").toLongOrNull() ?: return@launch
                val assets = o.optJSONArray("assets") ?: return@launch
                val url = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") ?: return@launch
                val info = packageManager.getPackageInfo(packageName, 0)
                val mine = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
                if (latest > mine) {
                    updateUrl = url
                    updateRow = Row.Action("Neue Version verfügbar", "Tippen zum Herunterladen – danach die Datei öffnen und installieren", dark = true, icon = R.drawable.ic_star) { runUpdate() }
                    render()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Schmaler Balken über dem Player: was gerade im Hintergrund läuft. */
    /** Wächter oben als kleine Glocke (statt Balken über dem Player, der Inhalte verdeckt). */
    private fun updateStrip() {
        findViewById<View>(R.id.strip).visibility = View.GONE
        val btn = findViewById<TextView>(R.id.watchBtn)
        val n = WatchService.queries(this).size
        btn.visibility = if (n > 0 && !kids()) View.VISIBLE else View.GONE
        btn.text = if (n > 1) "$n" else "Wächter"
        btn.setOnClickListener { watchSheet() }
    }

    private var updateUrl: String? = null

    /** Neue Version im Browser herunterladen; installiert wird über die Download-Benachrichtigung. */
    private fun runUpdate() {
        val url = updateUrl ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    private fun watchSheet() {
        val wishes = WatchService.queries(this)
        val options = wishes.map { q -> Option(R.drawable.ic_close, q, "Tippen zum Entfernen") { WatchService.remove(this, q) } } +
            Option(R.drawable.ic_stop, "Wächter beenden", "Alle Wunschlieder entfernen") { WatchService.stop(this) }
        sheet("Wunschliste", WatchService.status(this), null, "", false,
            "Das erste Lied, das irgendwo läuft, wird sofort abgespielt.", options)
    }

    private fun startHitMix(key: String?, label: String) {
        val c = controller ?: run {
            Toast.makeText(this, "Player startet noch, bitte nochmal tippen", Toast.LENGTH_SHORT).show(); return
        }
        c.setMediaItem(hitMixItem(key, "⚡ Hit-Mix $label"))
        c.prepare()
        c.play()
        Toast.makeText(this, "Hit-Mix sucht einen Sender mit einem Hit…", Toast.LENGTH_SHORT).show()
    }

    private fun stopHitMix() {
        controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_HITMIX, Bundle.EMPTY), Bundle().apply { putBoolean("stop", true) })
    }

    private fun button(icon: Int, label: String, action: () -> Unit) = Row.Quick(listOf(Triple(icon, label, action)))

    /** Einheitlicher Knopf zum Starten eines Hit-Mix (läuft er schon, steht das im Statusbalken). */
    @Suppress("UNUSED_PARAMETER")
    private fun hitMixRow(key: String?, label: String, sub: String): Row =
        button(R.drawable.ic_bolt, "Hit-Mix $label") { startHitMix(key, label) }

    private fun next() {
        val c = controller ?: return
        if (HitMix.activeName(this) != null) {
            val f = c.sendCustomCommand(SessionCommand(PlaybackService.CMD_NEXT, Bundle.EMPTY), Bundle.EMPTY)
            f.addListener({
                val ok = try { f.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS } catch (_: Exception) { false }
                if (!ok) Toast.makeText(this, "Gerade läuft kein anderer Hit – ich springe automatisch, sobald einer anfängt", Toast.LENGTH_LONG).show()
            }, ContextCompat.getMainExecutor(this))
        } else c.seekToNextMediaItem()
    }

    private fun liveEntries(stations: List<Station>): List<Entry> {
        return dedupe(stations.mapNotNull { s -> LiveIndex.get(s.id)?.takeIf { isMusic(it.title) }?.let { s.toEntry(it.title) } })
    }

    private fun isMusic(title: String) = LiveIndex.isMusic(title)

    private var coverJob: Job? = null

    private var coverTitleJob: Job? = null

    /** Cover für Liedtitel ("Künstler - Titel") nachladen und dann neu anzeigen. */
    private fun loadCoverTitles(titles: List<String>) {
        val todo = titles.filter { isMusic(it) && !Covers.known(it) }
        if (todo.isEmpty() || coverTitleJob?.isActive == true) return
        coverTitleJob = lifecycleScope.launch {
            var found = false
            todo.take(15).forEach { if (Covers.load(it) != null) found = true }
            if (found) { render(); updatePlayer() }
        }
    }

    /** Cover für laufende Lieder nachladen und dann neu anzeigen. */
    private fun loadCovers(list: List<Entry>) {
        val todo = list.filter { it.kind == Kind.STATION && it.title != it.name && !Covers.known(it.title) }
        if (todo.isEmpty() || coverJob?.isActive == true) return
        coverJob = lifecycleScope.launch {
            var found = false
            todo.take(25).forEach { if (Covers.load(it.title) != null) found = true }
            if (found) render()
        }
    }

    /**
     * Viele Sender gehören zum selben Netzwerk und spielen dasselbe.
     * Gleiche Songs werden zu einem Eintrag zusammengefasst ("SWR3 + 3 weitere").
     */
    private fun dedupe(list: List<Entry>): List<Entry> =
        list.groupBy { if (it.kind == Kind.STATION && it.title != it.name) Scanner.normalize(it.title) else it.id }
            .values.map { group ->
                val first = group.first()
                if (group.size == 1) first else first.copy(subtitle = "${first.subtitle} + ${group.size - 1} weitere")
            }

    private fun liveText(e: Entry): String? =
        if (e.preview) LiveIndex.findSong(e.title, e.subtitle)?.let { "● LIVE auf ${it.station.name}" } else null

    private fun addSongs(list: List<Entry>, ranked: Boolean) {
        list.forEachIndexed { i, e -> rows.add(Row.Item(e, list, liveText(e), if (ranked) i + 1 else null)) }
    }

    private var radioCharts: List<Pair<String, Int>> = emptyList()
    private var radioChartsAt = 0L

    /** Meistgespielt im Radio (24 h) vom Server, alle 10 Minuten neu. */
    private fun loadRadioCharts() {
        if (System.currentTimeMillis() - radioChartsAt < 10 * 60_000L) return
        radioChartsAt = System.currentTimeMillis()
        lifecycleScope.launch {
            val list = Radar.charts()
            if (list.isNotEmpty()) { radioCharts = list; render() } else radioChartsAt = 0L
        }
    }

    /** Lied aus dem Radio ("Künstler - Titel") als Eintrag mit Cover (Antippen: im Radio hören oder Wächter). */
    private fun songEntry(radioTitle: String): Entry {
        val artist = radioTitle.substringBefore(" - ").substringBefore(" – ").trim()
        val title = radioTitle.substringAfter(" - ").substringAfter(" – ").trim()
        val c = Covers.get(radioTitle)
        return Entry("hd:" + radioTitle.hashCode().toString(16), Kind.TRACK, c?.title ?: title, c?.artist ?: artist,
            "", c?.artwork.orEmpty(), c?.title ?: title, preview = true)
    }

    private fun renderHome() {
        if (!Net.online(this)) rows.add(Row.Empty("⚠ Keine Internetverbindung – Radio und Suche gehen gerade nicht."))

        // Schnellstart
        if (Settings.homeOn(this, "quick")) {
            val season = Moments.now().firstOrNull { it.action == Moments.Action.HITMIX && it.genreKey != null && it.genreKey != "charts" }
            rows.add(Row.Quick(listOfNotNull(
                Triple(R.drawable.ic_bolt, "Hit-Mix", { startHitMix(null, "– Charts") }),
                season?.let { m -> Triple(R.drawable.ic_star, m.title.substringBefore("-").substringBefore(" ").take(12), { startHitMix(m.genreKey, "– ${m.title}") }) },
                Triple(R.drawable.ic_heart, "Mein Mix", { startHitMix("mine", "– Mein Mix") }),
                Triple(R.drawable.ic_kids, "Kinder", { musicAudience = 1; loadKids(); selectTab(Tab.FIND) }),
            )))
        }

        // Weiterhören: die zuletzt gehörten Lieder (mit Cover), dazu angefangene freie Musik und Hörspiele
        if (Settings.homeOn(this, "again")) {
            val heard = Heard.all(this).take(12)
            loadCoverTitles(heard.map { it.title })
            val songs = heard.map { songEntry(it.title) }.distinctBy { Scanner.normalize(it.subtitle + " " + it.title) }
            val others = Recent.all(this).filter { it.kind != Kind.STATION }.take(6)
            val again = (songs + others).distinctBy { it.id }
            if (again.isNotEmpty()) {
                rows.add(Row.Header("Weiterhören"))
                rows.add(Row.Carousel(again))
            }
        }

        // Jetzt im Radio: erst Chart-Hits, dann bekannte Lieder
        if (Settings.homeOn(this, "live")) {
            val chartsLive = topDe.mapNotNull { e -> LiveIndex.findSong(e.title, e.subtitle) }
                .distinctBy { it.station.id }.map { it.station.toEntry(it.title) }
            val liveOthers = LiveIndex.topMusic().take(60).map { it.station.toEntry(it.title) }
            val live = dedupe(chartsLive + liveEntries(popular.take(80)) + liveOthers).take(25)
            loadCovers(live)
            rows.add(Row.Header("Jetzt im Radio", if (chartsLive.isNotEmpty()) "${chartsLive.size} Chart-Hits laufen gerade" else null))
            if (live.isEmpty()) rows.add(Row.Empty("Schaue nach, was gerade läuft…")) else rows.add(Row.Carousel(live.map { Covers.dress(it) }, live = true))
        }

        // Meistgespielt im Radio (vom Server, über alle Sender aus DE/AT/CH)
        if (Settings.homeOn(this, "radiocharts")) {
            loadRadioCharts()
            if (radioCharts.isNotEmpty()) {
                val list = radioCharts.take(10).map { songEntry(it.first) }
                loadCoverTitles(radioCharts.take(10).map { it.first })
                rows.add(Row.Header("Meistgespielt im Radio", "Heute auf allen Sendern", "ALLE") { openPage(Page.RadioChartsPage) })
                rows.add(Row.Carousel(list))
            }
        }

        // Beliebt bei Hitradar-Hörern (gelernt aus allen Apps)
        if (Settings.homeOn(this, "community")) {
            val list = playlistSongs["community"].orEmpty()
            if (list.isEmpty()) loadCommunityOnce()
            else {
                rows.add(Row.Header("Beliebt bei Hitradar-Hörern", "Was andere gerade gern hören", "ALLE") {
                    openPage(Page.PlaylistPage("Beliebt bei Hitradar-Hörern", "community", false))
                })
                rows.add(Row.Carousel(list.take(15)))
            }
        }

        // Deutsche Charts kurz, Rest auf eigener Seite
        if (Settings.homeOn(this, "charts")) {
            rows.add(Row.Header("Deutsche Charts", null, "ALLE") { openPage(Page.ChartsPage) })
            if (topDe.isEmpty()) rows.add(Row.Empty("Lade Charts…")) else addSongs(topDe.take(5), ranked = true)
        }
    }

    private val playlistCovers = HashMap<String, String>()
    private val playlistSongs = HashMap<String, List<Entry>>()
    private val playlistTried = HashSet<String>()
    private var playlistCoversLoading = false

    /** Cover der fertigen Playlists (erstes Lied) im Hintergrund laden. */
    private var playlistCoversTried = false

    private fun loadPlaylistCovers() {
        // Nur einmal pro Start versuchen (sonst würde bei einem Fehler bei jedem Bildaufbau neu geladen)
        if (playlistCoversLoading || playlistCoversTried) return
        playlistCoversTried = true
        playlistCoversLoading = true
        lifecycleScope.launch {
            loadCommunity()
            Playlists.curated.forEach { (_, q) ->
                val songs = Deezer.playlistByName(q)
                if (songs.isNotEmpty()) { playlistSongs[q] = songs; playlistCovers[q] = songs.first().artwork }
            }
            playlistCoversLoading = false
            render()
        }
    }

    private var communityTried = 0L

    private fun loadCommunityOnce() {
        if (System.currentTimeMillis() - communityTried < 10 * 60_000L) return
        communityTried = System.currentTimeMillis()
        lifecycleScope.launch { loadCommunity(); if (playlistSongs["community"] != null) render() }
    }

    /** "Beliebt bei Hitradar-Hörern": vom Server, gelernt aus allen Apps (anonym). */
    private suspend fun loadCommunity() {
        val songs = Radar.popular()?.songs.orEmpty().take(50)
        if (songs.isEmpty()) return
        songs.take(12).forEach { Covers.load(it) }
        val list = songs.map { songEntry(it) }
        playlistSongs["community"] = list
        list.firstOrNull { it.artwork.isNotBlank() }?.let { playlistCovers["community"] = it.artwork }
    }

    private fun renderPlaylist(p: Page.PlaylistPage) {
        val songs = if (p.own) Playlists.get(this, p.name) else playlistSongs[p.query].orEmpty()
        if (!p.own && songs.isEmpty()) {
            val q = p.query ?: return
            if (q in playlistTried) { rows.add(Row.Empty("Playlist konnte nicht geladen werden. Nach unten ziehen zum Neuladen.")); return }
            playlistTried.add(q)
            rows.add(Row.Empty("Lade Playlist…"))
            lifecycleScope.launch { if (q == "community") loadCommunity() else playlistSongs[q] = Deezer.playlistByName(q); render() }
            return
        }
        if (songs.isNotEmpty()) {
            rows.add(button(R.drawable.ic_bolt, "Als Radio-Mix abspielen") {
                startHitMix(if (p.own) "my:${p.name}" else "pl:${p.query}", "– ${p.name}")
            })
            rows.add(Row.Empty("Hitradar spielt die Lieder, sobald sie irgendwo im Radio anfangen – komplett und kostenlos."))
        } else rows.add(Row.Empty("Noch leer. Halte bei einem Lied den Finger drauf → „Zur Playlist hinzufügen“."))
        if (p.own) rows.add(Row.More("Playlist löschen") {
            android.app.AlertDialog.Builder(this).setTitle("„${p.name}“ löschen?")
                .setPositiveButton("Löschen") { _, _ -> Playlists.delete(this, p.name); page = null; render(); updateBack() }
                .setNegativeButton("Abbrechen", null).show()
        })
        rows.add(Row.Header("Lieder", "${songs.size} Lieder · Tippen: im Radio hören, Vorschau oder Wächter"))
        songs.forEach { e -> rows.add(Row.Item(e, songs, liveText(e))) }
    }

    /** Lied zu einer eigenen Playlist hinzufügen (vorhandene wählen oder neue anlegen). */
    private fun addToPlaylist(song: Entry) {
        val names = Playlists.mine(this).keys.toList()
        fun newList() {
            val edit = EditText(this).apply { hint = "Name der Playlist"; setSingleLine() }
            android.app.AlertDialog.Builder(this).setTitle("Neue Playlist").setView(edit)
                .setPositiveButton("Anlegen") { _, _ ->
                    val n = edit.text.toString().trim()
                    if (n.isNotEmpty()) { Playlists.create(this, n); Playlists.add(this, n, song); Toast.makeText(this, "Zu „$n“ hinzugefügt", Toast.LENGTH_SHORT).show(); render() }
                }.setNegativeButton("Abbrechen", null).show()
        }
        if (names.isEmpty()) { newList(); return }
        sheet("Zur Playlist hinzufügen", song.title, song.artwork.ifBlank { null }, song.name, false, null,
            names.map { n -> Option(R.drawable.ic_note, n, "${Playlists.get(this, n).size} Lieder") {
                val ok = Playlists.add(this, n, song)
                Toast.makeText(this, if (ok) "Zu „$n“ hinzugefügt" else "Ist schon in „$n“", Toast.LENGTH_SHORT).show()
            } } + Option(R.drawable.ic_heart_outline, "Neue Playlist…", null) { newList() })
    }

    private fun renderRadioCharts() {
        rows.add(Row.Header("Meistgespielt im Radio", "Die letzten 24 Stunden · alle Sender aus DE/AT/CH"))
        loadRadioCharts()
        if (radioCharts.isEmpty()) { rows.add(Row.Empty("Lade…")); return }
        loadCoverTitles(radioCharts.take(15).map { it.first })
        if (HitMix.activeName(this) == null) rows.add(button(R.drawable.ic_bolt, "Mix aus den Radio-Charts starten") {
            startHitMix("radiocharts", "– Meistgespielt im Radio")
        })
        val list = radioCharts.map { songEntry(it.first) }
        list.forEachIndexed { i, e ->
            val plays = radioCharts[i].second
            rows.add(Row.Item(e, list, liveText(e) ?: "$plays× gespielt", i + 1))
        }
    }

    private var musicAudience = 0 // 0 = Erwachsene, 1 = Kinder

    private fun audienceChips(selected: Int, onSelect: (Int) -> Unit) =
        Row.Segment(listOf("Für Erwachsene", "Für Kinder"), selected, onSelect)

    private fun renderFind() {
        if (!kids()) rows.add(audienceChips(musicAudience) { musicAudience = it; if (it == 1) loadKids(); render() })
        if (musicAudience == 1) { renderKidsMusic(); return }
        rows.add(hitMixRow(null, "Charts", "Nur aktuelle Hits aus dem Radio – springt automatisch weiter"))
        // Playlists mit bekannten Liedern (als Radio-Mix)
        loadPlaylistCovers()
        rows.add(Row.Header("Playlists", "Bekannte Lieder – komplett aus dem Radio"))
        rows.add(Row.Carousel(listOf(Entry("pl:community", Kind.SHOW, "Beliebt bei Hitradar-Hörern", "Von allen gelernt", "",
            playlistCovers["community"].orEmpty(), "Hitradar")) + Playlists.curated.map { (name, q) ->
            Entry("pl:$q", Kind.SHOW, name, "Playlist", "", playlistCovers[q].orEmpty(), name)
        }))
        val genres = Genres.all.filter { it.key != "kids" }
        rows.add(Row.Header("Genres & Stimmungen", "Jedes mit eigenem Hit-Mix", "ALLE ${genres.size}") { openPage(Page.GenresPage) })
        genres.take(6).chunked(2).forEach { rows.add(Row.Tiles(it[0], it.getOrNull(1))) }
        rows.add(Row.More("Alle ${genres.size} Genres anzeigen") { openPage(Page.GenresPage) })
        rows.add(Row.Header("Freie Musik", "Über 600.000 Lieder – komplett hören, beliebig weiterspringen"))
        rows.add(Row.Chips(freeTags.map { it.first }, freeTag) { freeTag = it; loadFreeTag(); render() })
        val tag = freeTags[freeTag].second
        val list = freeByTag[tag]
        if (list == null) { loadFreeTag(); rows.add(Row.Empty("Lade Musik…")) } else if (list.isNotEmpty()) {
            rows.add(button(R.drawable.ic_play, "${freeTags[freeTag].first} endlos abspielen") {
                play(list.first(), list)
                controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_ENDLESS, Bundle.EMPTY), Bundle().apply { putString("tag", tag) })
            })
            rows.add(Row.Carousel(list.take(25)))
        }
        rows.add(Row.Header("Beliebte Sender"))
        if (popular.isEmpty()) rows.add(Row.Empty("Lade Sender…")) else rows.add(Row.Carousel(popular.take(30).map { it.toEntry() }))
    }

    private val expanded = HashSet<String>()

    private fun <T> limited(key: String, list: List<T>, n: Int, add: (T) -> Unit) {
        val show = if (key in expanded) list else list.take(n)
        show.forEach(add)
        if (list.size > n && key !in expanded) rows.add(Row.More("${list.size - n} weitere anzeigen") { expanded.add(key); render() })
    }

    /** Der eine beste Treffer ganz oben. */
    private fun topRow(): Row? {
        radioHits.firstOrNull()?.let { e ->
            return Row.Top(e, "LÄUFT GERADE LIVE", "${e.subtitle} · tippen und komplett hören") { play(e, radioHits.toList()) }
        }
        val nq = Scanner.normalize(query)
        stationHits.firstOrNull { Scanner.normalize(it.name).startsWith(nq) }?.let { e ->
            return Row.Top(e, "SENDER", "Jetzt einschalten") { play(e, stationHits) }
        }
        songHits.firstOrNull { Scanner.normalize(it.title) == nq }?.let { e ->
            return Row.Top(e, "SONG", "${e.subtitle} · im Radio suchen, Vorschau oder Wächter") { onEntryTap(e, songHits) }
        }
        artistMix?.let { a ->
            val e = Entry("ar:${a.id}", Kind.TRACK, a.name, "Künstler", "", a.picture, a.name)
            return Row.Top(e, "KÜNSTLER", "${a.name}-Mix: nur ${a.name} aus dem Radio") { startHitMix("artist:${a.id}:${a.name}", "– ${a.name}") }
        }
        songHits.firstOrNull()?.let { e ->
            return Row.Top(e, "SONG", "${e.subtitle} · im Radio suchen, Vorschau oder Wächter") { onEntryTap(e, songHits) }
        }
        return null
    }

    private var searchFilter = 0 // 0 Alles, 1 Radio, 2 Lieder, 3 Hörspiele

    private fun renderSearch() {
        rows.add(Row.Chips(listOf("Alles", "Radio", "Lieder", "Hörspiele"), searchFilter) { searchFilter = it; render() })
        val showRadio = searchFilter == 0 || searchFilter == 1
        val showSongs = searchFilter == 0 || searchFilter == 2
        val showStories = searchFilter == 0 || searchFilter == 3
        val n = if (searchFilter == 0) 5 else 30
        if (searchFilter == 0) topRow()?.let { rows.add(it) }
        if (scanning) rows.add(Row.Status(statusText(), scanDone, scanTotal))
        else if (!deepSearch) rows.add(Row.More("📻 Alle Radios nach „$query“ durchsuchen") { startSearch() })

        if (showRadio) {
        rows.add(Row.Header("Läuft gerade im Radio", "Komplett hören"))
        if (radioHits.isEmpty()) {
            rows.add(Row.Empty(when {
                scanning -> "Suche läuft…"
                deepSearch -> "„$query“ läuft gerade auf keinem der Sender."
                else -> "Noch nicht durchsucht."
            }))
        } else {
            val list = dedupe(radioHits.toList())
            loadCovers(list)
            limited("radio", list, n) { rows.add(Row.Item(Covers.dress(it), list, "● LIVE")) }
        }
        val asGenre = Genres.fromText(query)
        if (asGenre != null) {
            // Musikrichtung statt Lied: der Wächter sucht genaue Titel, hier passt der Hit-Mix
            rows.add(Row.Empty("Der Wächter sucht bestimmte Lieder. Für „${asGenre.name}“ spielt der Hit-Mix nur passende Lieder."))
            rows.add(button(R.drawable.ic_bolt, "${asGenre.name}-Mix starten") { startHitMix(asGenre.key, "– ${asGenre.name}") })
        } else if (WatchService.activeQuery(this) == null) {
            rows.add(button(R.drawable.ic_bell, "Wächter: abspielen, sobald es läuft") {
                WatchService.start(this, query)
                Toast.makeText(this, "Wächter läuft – du kannst die App schließen", Toast.LENGTH_LONG).show()
            })
        }

        artistMix?.let { a ->
            if (HitMix.activeName(this) == null) {
                if (radioHits.isNotEmpty()) rows.add(button(R.drawable.ic_bolt, "${a.name}-Mix starten") {
                    startHitMix("artist:${a.id}:${a.name}", "– ${a.name}")
                })
            }
        }
        artistMix?.let { a ->
            val aw = Scanner.words(a.name)
            val now = dedupe(LiveIndex.fresh().filter { n -> aw.all { n.normalized.contains(it) } }
                .map { it.station.toEntry(it.title) }).filter { e -> radioHits.none { it.id == e.id } }
            if (now.isNotEmpty()) {
                rows.add(Row.Header("Von ${a.name} gerade im Radio", "Komplett hören"))
                now.forEach { rows.add(Row.Item(it, now, "● LIVE")) }
            }
        }
        if (stationHits.isNotEmpty()) {
            rows.add(Row.Header("Sender", "Tippen: Sender hören"))
            limited("stations", stationHits, if (searchFilter == 0) 4 else 30) { rows.add(Row.Item(it, stationHits)) }
        }
        }
        if (showSongs) {
        rows.add(Row.Header("Songs", "Tippen: im Radio hören, Vorschau oder Wächter"))
        when {
            songLoading -> rows.add(Row.Empty("Suche…"))
            songHits.isEmpty() -> rows.add(Row.Empty("Keine Songs gefunden."))
            else -> limited("songs", songHits, n) { e -> rows.add(Row.Item(e, songHits, liveText(e))) }
        }
        rows.add(Row.Header("Freie Musik", "Jamendo · Audius · Internet Archive"))
        when {
            freeLoading -> rows.add(Row.Empty("Suche…"))
            freeHits.isEmpty() -> rows.add(Row.Empty("Nichts gefunden."))
            else -> limited("free", freeHits, n) { rows.add(Row.Item(it, freeHits)) }
        }
        }
        if (showStories) {
            val st = storyShows["search"].orEmpty()
            rows.add(Row.Header("Hörspiele & Podcasts"))
            when {
                st.isNotEmpty() -> if (searchFilter == 3) st.forEach { rows.add(Row.Item(it, emptyList())) } else rows.add(Row.Carousel(st))
                !deepSearch -> rows.add(Row.More("Hörspiele zu „$query“ suchen") { startSearch() })
                else -> rows.add(Row.Empty("Nichts gefunden."))
            }
        }
    }

    private fun renderGenre(g: Genre) {
        rows.add(Row.Hero(g, "Radio live · Top-Songs · Freie Musik"))
        rows.add(hitMixRow(g.key, g.name, "Nur ${g.name}-Hits aus dem Radio – springt automatisch weiter"))
        val stations = genreStations[g.key]
        rows.add(Row.Header("Sender", "● LIVE zeigt, was gerade läuft"))
        when {
            stations == null -> rows.add(Row.Empty("Lade Sender…"))
            stations.isEmpty() -> rows.add(Row.Empty("Keine Sender gefunden."))
            else -> {
                val now = LiveIndex.fresh().associateBy { it.station.id }
                val list = dedupe(stations.take(30).map { s -> now[s.id]?.let { s.toEntry(it.title) } ?: s.toEntry() })
                loadCovers(list)
                list.forEach { e -> rows.add(Row.Item(Covers.dress(e), list, if (e.subtitle != "Internetradio") "● LIVE" else null)) }
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

    private var storySection = 0
    private var storyAudience = 0 // 0 = Erwachsene, 1 = Kinder

    private fun renderStories() {
        if (!kids()) rows.add(audienceChips(storyAudience) { storyAudience = it; storySection = 0; render() })
        val cats = if (storyAudience == 1) Podcasts.kidsCategories else Podcasts.adultCategories
        if (storyAudience == 1) rows.add(button(R.drawable.ic_sun, "Einschlafen mit Geschichten") { bedtimeSheet() })
        // Angefangene Folgen mit Restzeit
        if (!kids()) {
            val started = Recent.all(this)
                .filter { (it.id.startsWith("pc:") || it.id.startsWith("ia:")) && !Positions.isDone(this, it.id) && Positions.get(this, it.id) > 0 }
                .take(5)
            if (started.isNotEmpty()) {
                rows.add(Row.Header("Weiterhören"))
                started.forEach { rows.add(Row.Item(it, emptyList(), Positions.leftText(this, it.id))) }
            }
        }
        rows.add(Row.Chips(cats.map { it.name }, storySection) { storySection = it; render() })
        val cat = cats[storySection.coerceIn(0, cats.size - 1)]
        val list = storyShows[cat.key]
        when {
            list == null -> rows.add(Row.Empty("Lade…"))
            list.isEmpty() -> rows.add(Row.Empty("Nichts gefunden."))
            else -> list.forEach { rows.add(Row.Item(it, emptyList())) }
        }
    }

    private fun addCategory(cat: Podcasts.Category) {
        val list = storyShows[cat.key]
        rows.add(Row.Header(cat.name))
        when {
            list == null -> rows.add(Row.Empty("Lade…"))
            list.isEmpty() -> rows.add(Row.Empty("Nichts gefunden."))
            else -> rows.add(Row.Carousel(list))
        }
    }

    private fun renderShow(show: Entry) {
        val list = episodes[show.url]
        rows.add(Row.Item(show, emptyList()))
        if (!list.isNullOrEmpty()) {
            val i = Positions.nextIndex(this, list)
            val ep = list[i]
            rows.add(button(R.drawable.ic_play,
                if (i == 0 && Positions.get(this, ep.id) == 0L) "Von Anfang an hören" else "Weiterhören: ${ep.title}"
            ) { play(ep, list) })
        }
        rows.add(Row.Header("Folgen", "Von Anfang an · ⏭ spielt die nächste Folge · merkt sich, wo du warst"))
        when {
            list == null -> rows.add(Row.Empty("Lade Folgen…"))
            list.isEmpty() -> rows.add(Row.Empty("Keine Folgen gefunden."))
            else -> list.forEach { e ->
                val pos = Positions.get(this, e.id)
                val mark = when {
                    pos > 0 -> "▶ weiter bei ${fmt(pos)}"
                    Positions.isDone(this, e.id) -> "✓ gehört"
                    else -> null
                }
                rows.add(Row.Item(e, list, mark))
            }
        }
    }

    private fun renderCharts() {
        rows.add(Row.Hero(Genres.all.first { it.key == "charts" }, "Die Top 100 in Deutschland"))
        if (topDe.isNotEmpty()) {
            rows.add(button(R.drawable.ic_bell, "Alle 100 auf die Wunschliste") {
                WatchService.startMany(this, topDe.map { e ->
                    e.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim() + " — " + e.subtitle.split(Regex("[,&]| feat| x ")).first().trim()
                })
                Toast.makeText(this, "100 Hits auf der Wunschliste – der Wächter sucht", Toast.LENGTH_LONG).show()
                render()
            })
        }
        if (topDe.isEmpty()) rows.add(Row.Empty("Lade…")) else addSongs(topDe, ranked = true)
    }

    private fun renderKidsMusic() {
        rows.add(hitMixRow("kids", "für Kinder", "Bekannte Kinderlieder aus Kinderradios – springt automatisch weiter"))
        rows.add(Row.Header("Kinderradios", "Rund um die Uhr Kindermusik"))
        when {
            kidStations.isNotEmpty() -> {
                val now = LiveIndex.fresh().associateBy { it.station.id }
                val kl = kidStations.map { e -> now[e.id.removePrefix("st:")]?.let { Entry(e.id, e.kind, it.title, e.name, e.url, e.artwork, e.name) } ?: e }
                loadCovers(kl)
                rows.add(Row.Carousel(kl.map { Covers.dress(it) }))
            }
            kidsState == 1 -> rows.add(Row.Empty("Lade…"))
            else -> rows.add(Row.Empty("Keine Kinderradios gefunden."))
        }
        if (kidCharts.isNotEmpty()) {
            rows.add(Row.Header("Bekannte Kinderlieder", "Rolf Zuckowski, Bibi Blocksberg & Co."))
            addSongs(kidCharts.take(20), ranked = false)
        }
        rows.add(Row.Header("Kinderlieder zum Durchhören", "Freie Lieder, komplett und mit Weiter"))
        when {
            kidSongs.isNotEmpty() -> kidSongs.forEach { rows.add(Row.Item(it, kidSongs)) }
            kidsState == 1 -> rows.add(Row.Empty("Lade…"))
            else -> rows.add(Row.Empty("Keine Lieder gefunden."))
        }
    }

    private fun renderLib() {
        val mine = Playlists.mine(this)
        rows.add(Row.Header("Meine Playlists", if (mine.isEmpty()) "Lieder sammeln und als Radio-Mix hören" else null))
        if (mine.isNotEmpty()) rows.add(Row.Carousel(mine.map { (n, list) ->
            Entry("mp:$n", Kind.SHOW, n, "${list.size} Lieder", "", list.firstOrNull { it.artwork.isNotBlank() }?.artwork.orEmpty(), n)
        }))
        rows.add(button(R.drawable.ic_heart_outline, "Neue Playlist anlegen") {
            val edit = EditText(this).apply { hint = "Name der Playlist"; setSingleLine() }
            android.app.AlertDialog.Builder(this).setTitle("Neue Playlist").setView(edit)
                .setPositiveButton("Anlegen") { _, _ -> edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { Playlists.create(this, it); render() } }
                .setNegativeButton("Abbrechen", null).show()
        })
        val favs = Favorites.all(this)
        val stations = favs.filter { it.kind == Kind.STATION }
        val tracks = favs.filter { it.kind == Kind.TRACK }
        val showsFav = favs.filter { it.kind == Kind.SHOW }
        if (showsFav.isNotEmpty()) {
            rows.add(Row.Header("Lieblingsgeschichten"))
            rows.add(Row.Carousel(showsFav))
        }
        rows.add(Row.Header("Lieblingssender"))
        if (stations.size >= 2 && HitMix.activeName(this) == null) rows.add(button(R.drawable.ic_bolt, "Lieblingssender-Mix starten") {
            startHitMix("favs", "– Lieblingssender")
        })
        if (stations.isEmpty()) rows.add(Row.Empty("Tipp bei einem Sender auf ♥ oder halte ihn gedrückt.")) else rows.add(Row.Carousel(stations))
        rows.add(Row.Header("Lieblingslieder"))
        if (tracks.isEmpty()) rows.add(Row.Empty("Noch keine.")) else tracks.forEach { rows.add(Row.Item(it, tracks)) }
        // Zuletzt gehört: Sender mit dem Lied, das dort JETZT läuft (nicht dem von damals)
        val recent = Recent.all(this).map { e ->
            if (e.kind != Kind.STATION) e
            else {
                val now = LiveIndex.get(e.id.removePrefix("st:"))?.title?.takeIf { isMusic(it) }
                e.copy(title = e.name, subtitle = now?.let { "▶ $it" } ?: "Internetradio")
            }
        }
        if (recent.isNotEmpty()) {
            rows.add(Row.Header("Zuletzt gehört", "Sender und Lieder, die du zuletzt gestartet hast"))
            limited("recent", recent, 8) { rows.add(Row.Item(it, recent.filter { r -> r.kind == Kind.TRACK })) }
        }
        val heard = Heard.all(this).take(50)
        if (heard.isNotEmpty()) {
            rows.add(Row.Header("Verlauf", "Was lief wann? Tippen für Radio, Wächter & mehr", "LEEREN") {
                Heard.clear(this); render()
            })
            loadCoverTitles(heard.take(15).map { it.title })
            val list = heard.map { songEntry(it.title) }
            val fmtTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY)
            val fmtDay = java.text.SimpleDateFormat("EE HH:mm", java.util.Locale.GERMANY)
            val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
            list.forEachIndexed { i, e ->
                val h = heard[i]
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = h.time }
                val time = if (h.time <= 0) "" else if (cal.get(java.util.Calendar.DAY_OF_YEAR) == today) fmtTime.format(h.time) else fmtDay.format(h.time)
                val meta = listOf(time, h.station).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
                rows.add(Row.Item(e, list, liveText(e), meta = meta))
            }
        }
    }

    /** Fügt eine Gruppe von Einstellungen als zusammenhängende Karte hinzu. */
    private fun settingsGroup(label: String, items: List<Row.Setting>) {
        rows.add(Row.Label(label))
        items.forEachIndexed { i, it ->
            val pos = when {
                items.size == 1 -> 0
                i == 0 -> 1
                i == items.lastIndex -> 3
                else -> 2
            }
            rows.add(it.copy(pos = pos))
        }
    }

    private fun renderSettings() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        rows.add(Row.About(
            "Hitradar", "Version $version · Entwickelt von gamingdoodz",
            if (Radar.ok) "● Hitradar-Server verbunden" else "○ Suche direkt über die App"
        ) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gamingdoodz/radio-finder"))) } catch (_: Exception) {}
        })

        val q = Settings.quality(this)
        val norm = Settings.normalize(this)
        val mine = Services.all.filter { it.key in Services.mine(this) }
        settingsGroup("Wiedergabe", listOf(
            Row.Setting(R.drawable.ic_radio, "Sender aus", "Gegen fremdsprachige Werbung", value = Region.label(this)) { regionSheet() },
            Row.Setting(R.drawable.ic_eq, "Audioqualität", if (q == 3) "Gerade: " + (if (Settings.highQuality(this)) "Hoch (WLAN)" else "Normal (mobil)") else "Für freie Musik",
                value = listOf("Sparend", "Normal", "Hoch", "Auto")[q.coerceIn(0, 3)]) { qualitySheet() },
            Row.Setting(R.drawable.ic_radio, "Lautstärke angleichen", "Alle Sender gleich laut",
                toggle = norm, chevron = false) { Settings.setNormalize(this, !norm); render() },
            Row.Setting(R.drawable.ic_note, "Meine Musik-Apps", "Zum Öffnen von Liedern",
                value = when (mine.size) {
                    0 -> Services.all.count { Services.installed(this, it) }.let { if (it == 0) "Wählen" else "Auto ($it)" }
                    1 -> mine[0].name
                    else -> "${mine.size} Apps"
                }) { servicesSheet() },
        ))

        val alarmOn = Settings.alarmOn(this)
        val themeName = listOf("Wie das Handy", "Hell", "Dunkel")[Settings.theme(this).coerceIn(0, 2)]
        val sizeName = listOf("Klein", "Normal", "Groß", "Sehr groß")[Settings.textSize(this).coerceIn(0, 3)]
        val tabName = listOf("Start", "Musik", "Hörspiele", "Bibliothek")[Settings.startTab(this).coerceIn(0, 3)]
        val compact = Settings.compact(this)
        settingsGroup("Darstellung", listOf(
            Row.Setting(R.drawable.ic_sun, "Design", "Hell, dunkel oder automatisch", value = themeName) {
                choiceSheet("Design", listOf("Wie das Handy", "Hell", "Dunkel"), Settings.theme(this)) {
                    Settings.setTheme(this, it); recreate()
                }
            },
            Row.Setting(R.drawable.ic_search, "Schriftgröße", null, value = sizeName) {
                choiceSheet("Schriftgröße", listOf("Klein", "Normal", "Groß", "Sehr groß"), Settings.textSize(this)) {
                    Settings.setTextSize(this, it); recreate()
                }
            },
            Row.Setting(R.drawable.ic_library, "Kompakte Ansicht", "Kleinere Karten, mehr auf einen Blick",
                toggle = compact, chevron = false) { Settings.setCompact(this, !compact); render() },
            Row.Setting(R.drawable.ic_home, "Startseite anpassen", "Bereiche ein- und ausblenden") { homeSheet() },
            Row.Setting(R.drawable.ic_play, "Start mit", null, value = tabName) {
                choiceSheet("App startet mit", listOf("Start", "Musik", "Hörspiele", "Bibliothek"), Settings.startTab(this)) {
                    Settings.setStartTab(this, it); render()
                }
            },
        ))

        settingsGroup("Wecker", listOf(
            Row.Setting(R.drawable.ic_bell, "Radio-Wecker",
                if (alarmOn) "${if (Settings.alarmWeekdays(this)) "Mo–Fr" else "Täglich"} · ${Settings.alarmLabel(this)}" else "Sanft mit Musik aufwachen",
                value = if (alarmOn) "%02d:%02d".format(Settings.alarmHour(this), Settings.alarmMinute(this)) else "Aus") { alarmSheet() },
        ))

        settingsGroup("Kinder", listOf(
            Row.Setting(R.drawable.ic_kids, "Kinder-Modus", "Nur Kinderinhalte, Beenden mit PIN",
                value = "Starten") { startKidsMode() },
        ))

        val saver = Settings.saver(this)
        settingsGroup("Daten", listOf(
            Row.Setting(R.drawable.ic_bolt, "Datensparen unterwegs",
                if (saver) "Mobil weniger Sender, im WLAN alle" else "Mobil mehr Sender, mehr Daten",
                toggle = saver, chevron = false) { Settings.setSaver(this, !saver); render() },
            Row.Setting(R.drawable.ic_radar, "Senderlisten neu laden", "Falls Sender fehlen") {
                StationRepository.clear(this)
                popular = emptyList()
                loadHome()
                Toast.makeText(this, "Senderlisten werden neu geladen", Toast.LENGTH_SHORT).show()
            },
        ))

        settingsGroup("Hilfe", listOf(
            Row.Setting(R.drawable.ic_star, "Neuerungen", "Was ist neu in Hitradar?") {
                val text = Changes.list.joinToString("\n\n") { (title, items) -> title + "\n" + items.joinToString("\n") { "•  $it" } }
                android.app.AlertDialog.Builder(this).setTitle("✨ Neuerungen").setMessage(text).setPositiveButton("OK", null).show()
            },
            Row.Setting(R.drawable.ic_radar, "So funktioniert's", "Kurze Einführung in 4 Schritten") { intro(0) },
        ))

        val share = Settings.share(this)
        settingsGroup("Gemeinsam besser", listOf(
            Row.Setting(R.drawable.ic_heart, "Anonym beitragen", "Gehörte und gemerkte Lieder zählen für „Beliebt bei Hitradar-Hörern“ – ohne Daten über dich",
                toggle = share, chevron = false) { Settings.setShare(this, !share); render() },
        ))

        settingsGroup("Rechtliches", listOf(
            Row.Setting(R.drawable.ic_book, "Datenschutz", null) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gamingdoodz/radio-finder/blob/main/PRIVACY.md"))) } catch (_: Exception) {}
            },
            Row.Setting(R.drawable.ic_note, "Open-Source-Lizenzen", null) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Open-Source-Lizenzen")
                    .setMessage(
                        "Hitradar verwendet diese Bibliotheken, alle unter der Apache License 2.0 " +
                            "(apache.org/licenses/LICENSE-2.0):\n\n" +
                            "• AndroidX Media3 (ExoPlayer) – © The Android Open Source Project\n" +
                            "• AndroidX Core, Activity, Lifecycle, RecyclerView – © The Android Open Source Project\n" +
                            "• Kotlin & Kotlin Coroutines – © JetBrains s.r.o.\n" +
                            "• Coil – © Coil Contributors\n" +
                            "• Guava – © The Guava Authors"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            },
        ))

        rows.add(Row.Empty(
            "Daten und Musik von radio-browser.info, Deezer, Jamendo, Audius, Internet Archive / LibriVox und Apple Podcasts. " +
                "Alle Rechte an Sendern, Liedern und Hörspielen liegen bei ihren Eigentümern."
        ))
    }

    /** Auswahl aus einer Liste (aktuelle Wahl markiert). */
    private fun choiceSheet(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        sheet(title, "", null, "", false, null, options.mapIndexed { i, o ->
            Option(if (i == selected) R.drawable.ic_play else R.drawable.ic_chevron_right, o, if (i == selected) "Ausgewählt" else null) { onPick(i) }
        })
    }

    /** Startseite anpassen: Bereiche an/aus. */
    private fun homeSheet() {
        sheet("Startseite anpassen", "Antippen zum Ein- und Ausblenden", null, "", false, null,
            Settings.homeSections.map { (key, name) ->
                val on = Settings.homeOn(this, key)
                Option(if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline, name, if (on) "Wird angezeigt" else "Ausgeblendet") {
                    Settings.setHomeOn(this, key, !on); homeSheet(); render()
                }
            })
    }

    private fun regionSheet() {
        sheet("Sender aus", "Für Suche, Hit-Mix und Wächter", null, "", false, null,
            Region.options.map { (key, ns) ->
                val (n, sub) = ns
                Option(if (key == Settings.region(this)) R.drawable.ic_play else R.drawable.ic_radio, n, sub) {
                    Settings.setRegion(this, key)
                    Region.load(this)
                    Radar.resetSync()
                    lifecycleScope.launch { Radar.sync(force = true); render() }
                    render()
                }
            })
    }

    private fun qualitySheet() {
        val names = listOf(
            3 to ("Automatisch" to "Im WLAN hoch, unterwegs normal – empfohlen"),
            0 to ("Datensparend" to "96 kbit/s – ideal unterwegs"),
            1 to ("Normal" to "96 kbit/s, Standard"),
            2 to ("Hoch" to "≈ 192 kbit/s – beste Qualität, mehr Daten"),
        )
        sheet("Audioqualität", "Für freie Musik", null, "", false, null,
            names.map { (i, ns) ->
                val (n, sub) = ns
                Option(if (i == Settings.quality(this)) R.drawable.ic_play else R.drawable.ic_eq, n, sub) {
                    Settings.setQuality(this, i)
                    Jamendo.highQuality = Settings.highQuality(this)
                    if (i == 0) Settings.setSaver(this, true)
                    render()
                }
            })
    }

    private fun statusText() =
        if (scanTotal == 0) "Lade Senderliste…"
        else if (!Scanner.searchingSelf && scanDone == 0) "Frage den Hitradar-Server…"
        else "Server nicht erreichbar – durchsuche selbst… $scanDone von $scanTotal" +
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
        if (e.id.startsWith("pl:")) { openPage(Page.PlaylistPage(e.title, e.id.removePrefix("pl:"), false)); return }
        if (e.id.startsWith("mp:")) { openPage(Page.PlaylistPage(e.title, null, true)); return }
        if (e.kind == Kind.SHOW) { if ((page as? Page.ShowPage)?.show?.id != e.id) openPage(Page.ShowPage(e)); return }
        // Sender mit laufendem Lied: das Lied am besten Ort hören (ohne Werbung, frisch angefangen)
        if (e.kind == Kind.STATION && e.title != e.name && isMusic(e.title)) {
            val artist = e.title.substringBefore(" - ").substringBefore(" – ").trim()
            val title = e.title.substringAfter(" - ").substringAfter(" – ").trim()
            tapSong(title, artist, e, null)
            return
        }
        if (!e.preview) { play(e, section); return }
        tapSong(e.title, e.subtitle, null, e)
    }

    /** Gewünschtes Lied nach dem Antippen: wird gegen den echten Titel im Stream geprüft. */
    private class Wanted(val song: LiveIndex.Song, val title: String, val others: List<Station>, val at: Long, val preview: Entry?)
    private var wanted: Wanted? = null

    /** Läuft im Stream etwas anderes als gewünscht? Dann den nächsten Sender versuchen. */
    private fun checkWanted(live: String?) {
        val w = wanted ?: return
        if (System.currentTimeMillis() - w.at > 45_000) { wanted = null; return }
        if (live == null || !isMusic(live) || Ads.isAd(live)) return
        if (w.song.matches(Scanner.normalize(live))) { wanted = null; return }
        val next = w.others.firstOrNull()
        if (next != null) {
            wanted = Wanted(w.song, w.title, w.others.drop(1), System.currentTimeMillis(), w.preview)
            Toast.makeText(this, "Dort läuft schon etwas anderes – versuche ${next.name}", Toast.LENGTH_SHORT).show()
            play(next.toEntry(), emptyList())
        } else {
            wanted = null
            Toast.makeText(this, "„${w.title}“ ist gerade überall zu Ende", Toast.LENGTH_LONG).show()
            w.preview?.let { songDialog(it, "Das Lied ist gerade zu Ende gegangen.") }
        }
    }

    /**
     * Lied antippen: alle Sender suchen, auf denen es gerade läuft, direkt nachsehen, ob es dort
     * wirklich noch läuft, und den besten nehmen – ohne Vorab-Werbung und möglichst frisch angefangen.
     */
    /** Google Home, Nest-Lautsprecher, Chromecast im WLAN suchen und darauf abspielen. */
    private fun castDialog() {
        val ctx = try {
            @Suppress("DEPRECATION") com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
        } catch (_: Exception) {
            Toast.makeText(this, "Zum Casten werden die Google-Play-Dienste gebraucht", Toast.LENGTH_LONG).show(); return
        }
        val current = ctx.sessionManager.currentCastSession
        if (current != null && current.isConnected) {
            AlertDialog.Builder(this).setTitle(current.castDevice?.friendlyName ?: "Lautsprecher")
                .setMessage("Hitradar spielt gerade auf diesem Lautsprecher.")
                .setPositiveButton("Trennen") { _, _ -> ctx.sessionManager.endCurrentSession(true) }
                .setNegativeButton("Schließen", null).show()
            return
        }
        val router = androidx.mediarouter.media.MediaRouter.getInstance(this)
        val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
            .addControlCategory(com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
                com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        val routes = ArrayList<androidx.mediarouter.media.MediaRouter.RouteInfo>()
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val pad = dp(20f).toInt()
        val status = TextView(this).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextColor(getColor(R.color.c_b3ffffff))
            text = "Suche Lautsprecher im WLAN…"
        }
        val list = android.widget.ListView(this).apply { this.adapter = adapter }
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; addView(status); addView(list) }
        fun refresh() {
            routes.clear()
            routes.addAll(router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) })
            adapter.clear(); adapter.addAll(routes.map { it.name })
            status.text = if (routes.isEmpty()) "Suche Lautsprecher im WLAN…\nHandy und Lautsprecher müssen im selben WLAN sein."
                else "Wähle einen Lautsprecher:"
        }
        val cb = object : androidx.mediarouter.media.MediaRouter.Callback() {
            override fun onRouteAdded(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(selector, cb, androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        val dlg = AlertDialog.Builder(this).setTitle("Auf Lautsprecher abspielen").setView(box)
            .setNegativeButton("Abbrechen", null).create()
        list.setOnItemClickListener { _, _, i, _ ->
            routes.getOrNull(i)?.let { router.selectRoute(it) }
            Toast.makeText(this, "Verbinde mit ${routes.getOrNull(i)?.name ?: "Lautsprecher"}…", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }
        dlg.setOnDismissListener { router.removeCallback(cb) }
        dlg.show()
    }

    private fun tapSong(title: String, artist: String, station: Entry?, preview: Entry?) {
        val t = System.currentTimeMillis()
        val candidates = LiveIndex.findSongs(title, artist).distinctBy { it.station.id }
            .sortedWith(compareBy<LiveIndex.Now> { if (Ads.likely(this, it.station.id, it.station.url)) 1 else 0 }
                .thenBy { if (Cast.active && !Cast.isHttps(it.station.url)) 1 else 0 }   // Casten: https direkt
                .thenByDescending { it.since })
            .take(6)
        if (candidates.isEmpty()) {
            if (station != null) play(station, emptyList()) else preview?.let { songDialog(it) }
            return
        }
        Toast.makeText(this, "Prüfe, wo das Lied gerade läuft…", Toast.LENGTH_SHORT).show()
        val song = LiveIndex.Song(Entry("", Kind.TRACK, title, artist, "", "", title))
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            // Der Server prüft die Sender (schneller, ohne mobile Daten); geht das nicht, prüft das Handy selbst
            val remote = Radar.verify(candidates.map { it.station.id })
            if (remote != null) candidates.forEach { c -> remote[c.station.id]?.let { (title, _) -> LiveIndex.put(c.station, title) } }
            else Scanner.titles(candidates.map { it.station }) { _, _ -> }
            val checked = candidates.filter { c -> (LiveIndex.get(c.station.id)?.time ?: 0L) >= t0 }
            // Bestätigt läuft es noch – oder der Sender verrät nichts, dann trotzdem versuchen
            val ok = checked.firstOrNull { c -> LiveIndex.get(c.station.id)?.let { song.matches(it.normalized) } == true }
                ?: candidates.firstOrNull { c -> checked.none { it.station.id == c.station.id } }
            if (ok != null) {
                val now = LiveIndex.get(ok.station.id) ?: ok
                // Merken, was gewünscht war: stimmt der Titel im Stream nicht, nimmt die App den nächsten Sender
                wanted = Wanted(song, title, candidates.filter { it.station.id != ok.station.id }
                    .filter { c -> checked.none { it.station.id == c.station.id } || LiveIndex.get(c.station.id)?.let { song.matches(it.normalized) } == true }
                    .map { it.station }, System.currentTimeMillis(), preview)
                val ads = Ads.likely(this@MainActivity, ok.station.id, ok.station.url)
                val left = if (t - now.since > 3 * 60_000L) " (läuft schon länger)" else ""
                Toast.makeText(this@MainActivity,
                    "Läuft auf ${ok.station.name}$left" + if (ads) " – evtl. kurz Werbung vorab" else "", Toast.LENGTH_SHORT).show()
                play(ok.station.toEntry(now.title), emptyList())
            } else {
                render()
                if (preview != null) songDialog(preview, "Das Lied ist gerade zu Ende gegangen.")
                else if (station != null) {
                    Toast.makeText(this@MainActivity, "Das Lied ist gerade zu Ende – ${station.name} läuft trotzdem", Toast.LENGTH_SHORT).show()
                    play(station, emptyList())
                }
            }
        }
    }

    private class Option(val icon: Int, val label: String, val sub: String?, val action: () -> Unit)

    /**
     * Das laufende Radio-Lied nochmal von vorn: läuft es gerade woanders frisch an, sofort dorthin.
     * Sonst übernimmt der Wächter und spielt es, sobald es wieder irgendwo anfängt.
     */
    private fun playAgain() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val live = c.mediaMetadata.title?.toString()?.takeIf { LiveIndex.isMusic(it) } ?: return
        val artist = live.substringBefore(" - ").substringBefore(" – ").trim()
        val title = live.substringAfter(" - ").substringAfter(" – ").trim()
        val here = item.mediaId.removePrefix("st:")
        val t = System.currentTimeMillis()
        val best = LiveIndex.findSongs(title, artist).filter { it.station.id != here && t - it.since < 90_000 }.firstOrNull()
        if (best != null) {
            Toast.makeText(this, "Läuft gerade von vorn auf ${best.station.name}", Toast.LENGTH_SHORT).show()
            play(best.station.toEntry(best.title), emptyList())
            return
        }
        WatchService.start(this, "$title — $artist", skipStation = here)
        Toast.makeText(this, "Wächter läuft: Sobald „$title“ woanders anfängt, geht's los", Toast.LENGTH_LONG).show()
        render()
    }

    // ================= KI: Sprechen, Wünsch dir was, Mehr wie dieses =================

    private var listenForWish = false
    private val speech = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
        // "Spiel was Ruhiges", "ich will Party-Musik" … -> Wunsch; sonst normale Suche
        val looksLikeWish = Regex("(?i)^(spiel|spiele|ich will|ich möchte|ich hätte gern|mach|was |etwas |musik zum|musik für|lieder zum)").containsMatchIn(text)
        if (listenForWish || looksLikeWish) handleWish(text) else { selectTab(Tab.FIND); startSearch(text) }
    }

    /** Spracheingabe (eingebaut in Android, kostenlos). */
    private fun listen(wish: Boolean) {
        listenForWish = wish
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, if (wish) "Was möchtest du hören?" else "Lied, Künstler oder Sender")
        try { speech.launch(i) } catch (_: Exception) {
            Toast.makeText(this, "Spracheingabe ist auf diesem Handy nicht verfügbar", Toast.LENGTH_SHORT).show()
        }
    }

    /** "Wünsch dir was": Satz eingeben oder sprechen. */
    private fun wishDialog() {
        val edit = EditText(this).apply {
            hint = "z. B. „was Ruhiges zum Einschlafen“"
            setSingleLine()
            setPadding(dp(20f).toInt(), dp(16f).toInt(), dp(20f).toInt(), dp(16f).toInt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("✨ Wünsch dir was")
            .setMessage("Sag oder schreib, worauf du Lust hast – Stimmung, Jahrzehnt, Anlass oder Künstler.")
            .setView(edit)
            .setPositiveButton("Los") { _, _ -> edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { handleWish(it) } }
            .setNeutralButton("🎤 Sprechen") { _, _ -> listen(wish = true) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun handleWish(text: String) {
        Toast.makeText(this, "Einen Moment, ich suche passende Musik…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val w = Radar.wish(text) ?: localWish(text)
            val artist = w?.artist?.let { Deezer.artist(it) }
            when {
                artist != null -> startHitMix("artist:${artist.id}:${artist.name}", "– ${artist.name}")
                w?.genre != null -> {
                    val g = Genres.all.firstOrNull { it.key == w.genre }
                    startHitMix(if (w.genre == "charts") null else w.genre, "– ${g?.name ?: "Charts"}")
                }
                else -> { selectTab(Tab.FIND); startSearch(text) }
            }
        }
    }

    /** Falls der Server nicht antwortet: einfache Stichwörter. */
    private fun localWish(text: String): Radar.Wish? {
        val t = text.lowercase()
        val rules = listOf("xmas" to "weihnacht|christmas", "kids" to "kinder", "chill" to "ruhig|entspann|chill|schlaf|kuschel",
            "party" to "party|feier|ballermann", "80s" to "80er", "90s" to "90er", "2000s" to "2000er", "oldies" to "oldie|60er|70er",
            "rap" to "rap|hip ?hop", "rock" to "rock|metal", "schlager" to "schlager", "dance" to "dance|techno|house|tanz",
            "summer" to "sommer|gute laune", "classic" to "klassik", "jazz" to "jazz|soul", "charts" to "hits|charts")
        return rules.firstOrNull { Regex(it.second).containsMatchIn(t) }?.let { Radar.Wish(it.first, null, false) }
    }

    /** Titel des Liedes, das gerade läuft ("Künstler - Titel"). */
    private fun currentSongTitle(): String? {
        val c = controller ?: return null
        val live = c.mediaMetadata.title?.toString()?.takeIf { isMusic(it) }
        if (live != null) return live
        val m = c.currentMediaItem?.mediaMetadata ?: return null
        return if (m.artist != null && m.title != null) "${m.artist} - ${m.title}" else null
    }

    /** Hit-Mix mit Liedern, die Sender oft zusammen mit diesem Lied spielen. */
    private fun moreLike(song: String) {
        startHitMix("similar:$song", "– Mehr wie dieses")
    }

    /** Laufendes Lied an Freunde schicken (Text mit Links zu Spotify und YouTube). */
    private fun shareSong() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val live = c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
        val song = live?.let { Covers.get(it)?.let { i -> "${i.artist} - ${i.title}" } ?: it }
            ?: item.mediaMetadata.let { "${it.artist ?: ""} - ${it.title ?: ""}" }.trim(' ', '-')
        if (song.isBlank()) return
        val q = Uri.encode(song)
        val text = "Hör mal: $song\n\n" +
            "Spotify: https://open.spotify.com/search/$q\n" +
            "YouTube: https://www.youtube.com/results?search_query=$q\n\n" +
            "Gefunden mit Hitradar"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Lied teilen"))
    }

    /** Menü beim langen Drücken auf einen Eintrag. */
    private fun itemMenu(e: Entry, section: List<Entry>) {
        val liveSong = e.kind == Kind.STATION && e.title != e.name
        val song = when {
            liveSong -> e.title
            e.kind == Kind.TRACK && (e.preview || e.id.startsWith("hd:")) ->
                e.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim() + " — " + e.subtitle.split(Regex("[,&]| feat| x ")).first().trim()
            else -> null
        }
        val isFav = Favorites.isFav(this, e.id)
        sheet(
            if (liveSong) e.title else e.title, if (liveSong) e.name else e.subtitle, e.artwork.ifBlank { null }, e.name,
            e.kind == Kind.STATION, null,
            listOfNotNull(
                Option(R.drawable.ic_play, if (e.kind == Kind.SHOW) "Öffnen" else "Abspielen", null) { onEntryTap(e, section) },
                if (!e.preview && !e.id.startsWith("hd:")) Option(if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline,
                    if (isFav) "Aus Favoriten entfernen" else "Zu Favoriten", null) {
                    Favorites.toggle(this, e); render()
                } else null,
                song?.let {
                    Option(R.drawable.ic_bell, "Wächter für dieses Lied", "Spielt es, sobald es wieder im Radio läuft") {
                        WatchService.start(this, it)
                        Toast.makeText(this, "Wächter läuft", Toast.LENGTH_SHORT).show()
                        render()
                    }
                },
                if (song != null) Option(R.drawable.ic_note, "Zur Playlist hinzufügen", null) {
                    val artist = if (liveSong) e.title.substringBefore(" - ").substringBefore(" – ").trim() else e.subtitle
                    val title = if (liveSong) e.title.substringAfter(" - ").substringAfter(" – ").trim() else e.title
                    addToPlaylist(Entry("dz:" + Scanner.normalize("$artist $title").hashCode(), Kind.TRACK, title, artist,
                        if (e.preview) e.url else "", if (Covers.isCover(e.artwork) || e.preview) e.artwork else "", title, preview = true))
                } else null,
                (page as? Page.PlaylistPage)?.takeIf { it.own }?.let { pl ->
                    Option(R.drawable.ic_close, "Aus „${pl.name}“ entfernen", null) { Playlists.remove(this, pl.name, e); render() }
                },
                if (song != null && !kids()) Option(R.drawable.ic_bolt, "✨ Mehr wie dieses", "Mix mit Liedern, die Sender oft dazu spielen") {
                    moreLike(if (liveSong) e.title else "${e.subtitle} - ${e.title}")
                } else null,
                if (song != null && !kids()) Option(R.drawable.ic_note, "In deiner Musik-App öffnen", null) {
                    openIn(song, stories = false)
                } else null,
            )
        )
    }

    /** Kurze Einführung in 4 Schritten (beim ersten Start und unter Mehr → So funktioniert's). */
    private fun intro(step: Int) {
        val pages = listOf(
            Triple(R.drawable.ic_radar, "Willkommen bei Hitradar",
                "Finde Lieder, die gerade im Radio laufen – und hör sie komplett und kostenlos." to
                    "Songs mit ● LIVE laufen genau jetzt. Antippen, und es geht los."),
            Triple(R.drawable.ic_search, "Lied suchen",
                "Unter „Musik“ einen Song oder Künstler eingeben. Du siehst sofort, auf welchem Sender er läuft." to
                    "Läuft er gerade nirgends? Tippe auf „Wächter starten“ 🔔 – sobald er im Radio kommt, spielt Hitradar ihn ab."),
            Triple(R.drawable.ic_bolt, "Hit-Mix",
                "Nur Hits, wenig Gerede: Der Hit-Mix springt automatisch zum nächsten Sender, auf dem gerade ein Hit anfängt." to
                    "Mit ⏭ springst du jederzeit zum nächsten Hit."),
            Triple(R.drawable.ic_kids, "Kinder, Hörspiele & Auto",
                "Kinderlieder und Hörspiele haben eigene Bereiche. Der Kinder-Modus zeigt nur Kinderinhalte." to
                    "Alles läuft auch in Android Auto und im Hintergrund weiter. Tipp: In den Android-Auto-Einstellungen „Musik automatisch starten“ einschalten."),
        )
        val (icon, title, texts) = pages[step]
        val last = step == pages.lastIndex
        sheet(
            title, "${step + 1} von ${pages.size}", null, "", false,
            texts.first + "\n\n" + texts.second,
            listOfNotNull(
                Option(icon, if (last) "Los geht's" else "Weiter", null) { if (!last) intro(step + 1) },
                if (!last) Option(R.drawable.ic_close, "Überspringen", null) {} else null,
            )
        )
    }

    /** Eigenes Auswahlfenster, das von unten hereinfährt. */
    private fun sheet(title: String, sub: String, art: String?, name: String, station: Boolean, hint: String?, options: List<Option>) {
        val v = layoutInflater.inflate(R.layout.dialog_sheet, null)
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(v)
        v.findViewById<TextView>(R.id.sheetTitle).text = title
        v.findViewById<TextView>(R.id.sheetSub).text = sub
        val hintView = v.findViewById<TextView>(R.id.sheetHint)
        hintView.text = hint ?: ""
        hintView.visibility = if (hint == null) View.GONE else View.VISIBLE
        val artBox = v.findViewById<View>(R.id.sheetArtBox)
        if (art == null) artBox.visibility = View.GONE
        else bindArt(v.findViewById(R.id.sheetArt), v.findViewById(R.id.sheetInitials), art, name, station, 14f)
        val box = v.findViewById<ViewGroup>(R.id.sheetOptions)
        options.forEach { o ->
            val ov = layoutInflater.inflate(R.layout.item_option, box, false)
            ov.findViewById<ImageView>(R.id.optIcon).setImageResource(o.icon)
            ov.findViewById<TextView>(R.id.optLabel).text = o.label
            val os = ov.findViewById<TextView>(R.id.optSub)
            os.text = o.sub ?: ""
            os.visibility = if (o.sub == null) View.GONE else View.VISIBLE
            ov.setOnClickListener { d.dismiss(); o.action() }
            box.addView(ov)
        }
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.6f)
        }
        d.show()
    }

    private fun songDialog(e: Entry, hint: String = "Läuft gerade auf keinem bekannten Sender.") {
        val clean = e.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
        val firstArtist = e.subtitle.split(Regex("[,&]| feat| x ")).first().trim()
        sheet(
            clean, e.subtitle, e.artwork, e.name, false,
            hint,
            listOfNotNull(
                Option(R.drawable.ic_bell, "Wächter starten", "Spielt das Lied komplett ab, sobald es im Radio kommt") {
                    WatchService.start(this, "$clean — $firstArtist")
                    Toast.makeText(this, "Wächter läuft – du kannst die App schließen", Toast.LENGTH_LONG).show()
                    render()
                },
                if (e.url.isNotBlank()) Option(R.drawable.ic_play, "Vorschau anhören", "30 Sekunden") { play(e, emptyList()) } else null,
                Option(R.drawable.ic_radar, "Alle Radios durchsuchen", "Auch weniger bekannte Sender") { startSearch(clean) },
                if (kids()) null else Option(R.drawable.ic_note, "In deiner Musik-App öffnen", "Spotify, Amazon Music, YouTube Music …") {
                    openIn("$clean $firstArtist", stories = false)
                },
            )
        )
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
        } else if (entry.kind == Kind.STATION) {
            // Ganze Senderliste laden: "Weiter" springt zum nächsten Sender
            val stations = section.filter { it.kind == Kind.STATION }
            val idx = stations.indexOfFirst { it.id == entry.id }
            if (idx >= 0 && stations.size > 1) c.setMediaItems(stations.map { it.toMediaItem() }, idx, 0L)
            else c.setMediaItem(entry.toMediaItem())
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
        val choices = listOf(15 to "15 Minuten", 30 to "30 Minuten", 45 to "45 Minuten", 60 to "1 Stunde", 90 to "1,5 Stunden")
        fun set(min: Int, label: String) {
            val args = Bundle().apply { putInt("min", min) }
            controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY), args)
            Toast.makeText(this, if (min == 0) "Schlaf-Timer aus" else "Musik stoppt in $label", Toast.LENGTH_SHORT).show()
        }
        val running = Settings.sleepEnd(this) > System.currentTimeMillis()
        val options = choices.map { (m, l) -> Option(R.drawable.ic_sun, l, null) { set(m, l) } }.toMutableList()
        if (running) options.add(0, Option(R.drawable.ic_close, "Timer ausschalten", null) { set(0, "") })
        sheet("Schlaf-Timer", "Die Musik geht danach automatisch aus", null, "", false, null, options)
    }

    private var tintedArt: String? = null

    /** Großer Player: Hintergrund in der Farbe des Covers. */
    private fun tintPlayer(art: String) {
        if (art == tintedArt) return
        tintedArt = art
        if (art.isBlank()) { full.setBackgroundColor(getColor(R.color.c_ff0a0b18)); return }
        lifecycleScope.launch {
            val req = coil.request.ImageRequest.Builder(this@MainActivity).data(art).size(128).allowHardware(false).build()
            val bmp = (coil.Coil.imageLoader(this@MainActivity).execute(req).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@launch
            val pal = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { androidx.palette.graphics.Palette.from(bmp).generate() }
            if (art != tintedArt) return@launch
            val base = getColor(R.color.c_ff060b11)
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val tone = if (night) pal.getDarkVibrantColor(pal.getDominantColor(base))
            else pal.getLightVibrantColor(pal.getLightMutedColor(base))
            full.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(androidx.core.graphics.ColorUtils.blendARGB(tone, base, 0.35f), base))
        }
    }

    private fun showFull(show: Boolean) {
        if (show && controller?.currentMediaItem == null) return
        if (show && full.visibility != View.VISIBLE) {
            full.translationY = resources.displayMetrics.heightPixels * 0.15f
            full.alpha = 0f
            full.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
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
        val t0 = System.currentTimeMillis()
        val indexTitle = if (isStation) LiveIndex.get(item.mediaId.removePrefix("st:"))
            ?.takeIf { t0 - it.time < 120_000 && t0 - it.since < 6 * 60_000 }?.title else null
        val title = live ?: indexTitle ?: m.title?.toString()?.takeIf { it.isNotBlank() } ?: entry?.name ?: ""
        if (isStation && live == null) checkStationTitle(item.mediaId, entry)
        checkWanted(live)
        val ad = adInfo(c, item.mediaId, live)
        val sub = when {
            c.playbackState == Player.STATE_BUFFERING -> "Verbinde…"
            ad != null -> adText(ad)
            c.playerError != null -> "Nicht erreichbar – tippe auf Play"
            isStation && HitMix.activeName(this) != null -> "⚡ ${HitMix.activeName(this)} · ${entry?.name ?: ""}"
            isStation && live == null && indexTitle != null -> (entry?.name ?: "") + " · Titel laut Senderliste"
            isStation -> entry?.name ?: (m.artist?.toString() ?: "")
            entry?.preview == true -> "${m.artist ?: ""} · Vorschau"
            else -> m.artist?.toString() ?: ""
        }
        val playing = c.isPlaying || (c.playbackState == Player.STATE_BUFFERING && c.playWhenReady)
        val playIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val isFav = Favorites.isFav(this, item.mediaId)
        // Radio: Cover des Liedes, das gerade läuft (sonst Senderlogo)
        val songCover = if (isStation && live != null && isMusic(live)) Covers.get(live)?.artwork else null
        if (isStation && live != null && isMusic(live) && !Covers.known(live)) loadCoverTitles(listOf(live))
        val art = songCover ?: m.artworkUri?.toString().orEmpty()
        val name = entry?.name ?: title

        findViewById<TextView>(R.id.miniTitle).text = title
        findViewById<TextView>(R.id.miniSub).text = sub
        findViewById<ImageButton>(R.id.miniPlay).setImageResource(playIcon)
        findViewById<EqualizerView>(R.id.miniEq).playing = c.isPlaying
        val canNext = c.hasNextMediaItem() || HitMix.activeName(this) != null
        findViewById<View>(R.id.miniNext).visibility = if (canNext) View.VISIBLE else View.GONE
        findViewById<View>(R.id.miniProgress).visibility = if (isStation) View.INVISIBLE else View.VISIBLE
        bindArt(findViewById(R.id.miniArt), findViewById(R.id.miniInitials), art, name, isStation && songCover == null, 12f)

        if (full.visibility == View.VISIBLE) {
            findViewById<TextView>(R.id.fullTitle).text = title
            findViewById<TextView>(R.id.fullSub).text = sub
            findViewById<TextView>(R.id.fullSource).text = when {
                ad != null -> "📢 WERBUNG"
                isStation -> "INTERNETRADIO"
                entry?.preview == true -> "VORSCHAU"
                else -> "FREIE MUSIK"
            }
            findViewById<View>(R.id.fullBadge).visibility = if (isStation) View.VISIBLE else View.GONE
            findViewById<ImageButton>(R.id.fullPlay).setImageResource(playIcon)
            findViewById<ImageButton>(R.id.fullFav).setImageResource(if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            findViewById<View>(R.id.fullNext).visibility = if (canNext) View.VISIBLE else View.GONE
            findViewById<View>(R.id.fullPrev).visibility = if (c.hasPreviousMediaItem()) View.VISIBLE else View.GONE
            findViewById<EqualizerView>(R.id.fullEq).apply { bars = 9; this.playing = c.isPlaying }
            findViewById<View>(R.id.fullSeek).visibility = if (isStation) View.INVISIBLE else View.VISIBLE
            val story = item.mediaId.startsWith("pc:") || item.mediaId.startsWith("ia:")
            val againOk = isStation && live != null && LiveIndex.isMusic(live)
            val canShare = (isStation && live != null && isMusic(live)) || (!isStation && !story)
            findViewById<View>(R.id.fullStoryBar).visibility = if (story || againOk || canShare) View.VISIBLE else View.GONE
            findViewById<View>(R.id.fullShare).visibility = if (canShare) View.VISIBLE else View.GONE
            findViewById<View>(R.id.fullSimilar).visibility = if (canShare && !kids()) View.VISIBLE else View.GONE
            val mixOn = HitMix.activeName(this) != null
            findViewById<View>(R.id.fullStopMix).visibility = if (mixOn) View.VISIBLE else View.GONE
            if (mixOn) findViewById<View>(R.id.fullStoryBar).visibility = View.VISIBLE
            findViewById<View>(R.id.fullAgain).visibility = if (againOk && !story) View.VISIBLE else View.GONE
            listOf(R.id.fullBack30, R.id.fullSpeed, R.id.fullFwd30).forEach {
                findViewById<View>(it).visibility = if (story) View.VISIBLE else View.GONE
            }
            if (story) findViewById<TextView>(R.id.fullSpeed).text =
                "%.2f".format(c.playbackParameters.speed).trimEnd('0').trimEnd('.', ',').replace('.', ',') + "×"
            bindArt(findViewById(R.id.fullArt), findViewById(R.id.fullInitials), art, name, isStation && songCover == null, 24f)
            tintPlayer(art)
            val bg = findViewById<ImageView>(R.id.fullBg)
            if (art.isNotBlank()) bg.load(art) { crossfade(true) } else bg.setImageDrawable(null)
        }
        updateProgress()
    }

    /** Werbung am Senderanfang: left = Restzeit (-1 = unbekannt), total = gelernte Dauer. */
    private data class AdInfo(val left: Long, val total: Long, val elapsed: Long)

    /** Läuft gerade Werbung am Senderanfang? null = nein. */
    private fun adInfo(c: MediaController, mediaId: String, live: String?): AdInfo? {
        if (!mediaId.startsWith("st:") || !c.isPlaying) return null
        val id = mediaId.removePrefix("st:")
        val known = Ads.duration(this, id)
        val elapsed = c.currentPosition.coerceAtLeast(0)
        if (live != null && LiveIndex.isMusic(live) && !Ads.isAd(live)) return null
        if (Ads.isAd(live)) return AdInfo(if (known > elapsed) known - elapsed else -1, known, elapsed)
        // Bekannte Vorab-Werbung: Countdown (mit etwas Puffer)
        if (known > 0 && elapsed < known + 15_000) return AdInfo((known - elapsed).coerceAtLeast(0), known, elapsed)
        // Sender schickt im Player sonst Liedtitel, gerade aber keinen: am Anfang ist das fast immer Werbung.
        // (Sender, die nie Titel in den Stream schreiben, zählen nicht – sonst stünde dort fälschlich "Werbung".)
        val url = c.currentMediaItem?.let { Entry.fromMediaItem(it)?.url }.orEmpty()
        if (live == null && (Ads.hasIcy(this, id) || Ads.likely(this, id, url)) && elapsed < 90_000) return AdInfo(-1, 0, elapsed)
        return null
    }

    private fun adText(ad: AdInfo) = when {
        ad.left > 0 -> "📢 Werbung · noch ${fmt(ad.left)}"
        ad.left == 0L -> "📢 Werbung · gleich geht's los"
        else -> "📢 Werbung · läuft seit ${fmt(ad.elapsed)}"
    }

    private var lastTitleCheck = 0L

    /** Wenn ein Sender selbst keinen Titel mitschickt: alle 30 Sekunden selbst nachsehen. */
    private fun checkStationTitle(mediaId: String, entry: Entry?) {
        if (entry == null || System.currentTimeMillis() - lastTitleCheck < 30_000) return
        lastTitleCheck = System.currentTimeMillis()
        val station = Station(mediaId.removePrefix("st:"), entry.name, entry.url, entry.artwork, false)
        lifecycleScope.launch {
            Scanner.titles(listOf(station)) { _, _ -> }
            updatePlayer()
        }
    }

    /** Laufleiste und Zeiten, alle halbe Sekunde. */
    private fun updateProgress() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val isStation = item.mediaId.startsWith("st:")
        if (isStation) {
            val live = c.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
            val ad = adInfo(c, item.mediaId, live)
            val miniP = findViewById<ProgressBar>(R.id.miniProgress)
            if (ad != null) {
                val t = adText(ad)
                findViewById<TextView>(R.id.miniSub).text = t
                findViewById<TextView>(R.id.fullSub).text = t
                findViewById<TextView>(R.id.fullSource).text = "📢 WERBUNG"
                // Fortschritt der Werbung als Balken, wenn die Dauer bekannt ist
                if (ad.total > 0) {
                    miniP.visibility = View.VISIBLE
                    miniP.max = ad.total.toInt()
                    miniP.progress = ad.elapsed.coerceAtMost(ad.total).toInt()
                } else miniP.visibility = View.INVISIBLE
            } else {
                miniP.visibility = View.INVISIBLE
                if (full.visibility == View.VISIBLE) findViewById<TextView>(R.id.fullSource).text = "INTERNETRADIO"
            }
        }
        val pos = c.currentPosition.coerceAtLeast(0)
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

        val miniP = findViewById<ProgressBar>(R.id.miniProgress)
        if (!isStation && dur > 0) { miniP.max = dur.toInt(); miniP.progress = pos.toInt() }

        val sleepEnd = Settings.sleepEnd(this)
        val sleepLeft = sleepEnd - System.currentTimeMillis()
        findViewById<ImageButton>(R.id.fullCast).setImageResource(if (Cast.active) R.drawable.ic_cast_on else R.drawable.ic_cast)
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

    /** Weicher farbiger Lichtschein für Kacheln. */
    private fun glow(color: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(65f)
        val c = Color.parseColor(color)
        colors = intArrayOf(Color.argb(150, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)))
    }

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
            is Row.Chips -> 9
            is Row.Moments -> 10
            is Row.Segment -> 11
            is Row.Top -> 12
            is Row.Quick -> 13
            is Row.Label -> 14
            is Row.Setting -> 15
            is Row.About -> 16
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = when (viewType) {
                0 -> R.layout.item_header
                1, 10 -> R.layout.item_carousel
                2 -> R.layout.item_row
                3 -> R.layout.item_status
                4 -> R.layout.item_action
                6 -> R.layout.item_tiles
                7 -> R.layout.item_hero
                8 -> R.layout.item_more
                9 -> R.layout.item_chips
                11 -> R.layout.item_segment
                12 -> R.layout.item_top
                13 -> R.layout.item_quick
                14 -> R.layout.item_label
                15 -> R.layout.item_setting
                16 -> R.layout.item_about
                else -> R.layout.item_empty
            }
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            if (viewType == 10) {
                val rv = v as RecyclerView
                rv.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
                rv.adapter = MomentAdapter()
                rv.itemAnimator = null
            }
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
                    val fg = if (row.dark) getColor(R.color.c_ffffffff) else Color.parseColor("#FF08131C")
                    v.findViewById<TextView>(R.id.title).setTextColor(fg)
                    v.findViewById<TextView>(R.id.sub).setTextColor(if (row.dark) getColor(R.color.c_b3ffffff) else Color.parseColor("#CC08131C"))
                    v.findViewById<ImageView>(R.id.icon).setImageResource(row.icon)
                    v.findViewById<ImageView>(R.id.icon).imageTintList =
                        ColorStateList.valueOf(if (row.dark) getColor(R.color.c_ff2ee6a8) else Color.parseColor("#FF08131C"))
                    v.setOnClickListener { row.onClick() }
                }
                is Row.Empty -> (v as TextView).text = row.text
                is Row.Label -> (v as TextView).text = row.text
                is Row.About -> {
                    v.findViewById<TextView>(R.id.title).text = row.title
                    v.findViewById<TextView>(R.id.sub).text = row.sub
                    v.findViewById<TextView>(R.id.value).text = row.status
                    v.setOnClickListener { row.onClick() }
                }
                is Row.Setting -> {
                    val box = v.findViewById<View>(R.id.box)
                    box.setBackgroundResource(when (row.pos) {
                        1 -> R.drawable.bg_set_first; 2 -> R.drawable.bg_set_mid; 3 -> R.drawable.bg_set_last; else -> R.drawable.bg_set_only
                    })
                    v.findViewById<View>(R.id.divider).visibility = if (row.pos == 2 || row.pos == 3) View.VISIBLE else View.GONE
                    v.findViewById<ImageView>(R.id.icon).setImageResource(row.icon)
                    v.findViewById<TextView>(R.id.title).text = row.title
                    v.findViewById<TextView>(R.id.sub).apply { text = row.sub; visibility = if (row.sub.isNullOrBlank()) View.GONE else View.VISIBLE }
                    v.findViewById<TextView>(R.id.value).apply { text = row.value; visibility = if (row.value == null) View.GONE else View.VISIBLE }
                    v.findViewById<Switch>(R.id.toggle).apply {
                        visibility = if (row.toggle == null) View.GONE else View.VISIBLE
                        isChecked = row.toggle == true
                        thumbTintList = ColorStateList.valueOf(if (row.toggle == true) getColor(R.color.c_ff2ee6a8) else getColor(R.color.c_ffb0b8c0))
                        trackTintList = ColorStateList.valueOf(if (row.toggle == true) getColor(R.color.c_802ee6a8) else getColor(R.color.c_40ffffff))
                    }
                    v.findViewById<View>(R.id.chevron).visibility = if (row.chevron) View.VISIBLE else View.GONE
                    box.setOnClickListener { row.onClick() }
                }
                is Row.Tiles -> {
                    bindTile(v.findViewById(R.id.tile1), row.g1)
                    val t2 = v.findViewById<View>(R.id.tile2)
                    if (row.g2 != null) { t2.visibility = View.VISIBLE; bindTile(t2, row.g2) } else t2.visibility = View.INVISIBLE
                }
                is Row.Hero -> {
                    v.clipToOutline = true
                    v.findViewById<View>(R.id.heroGlow).background = glow(row.genre.color1)
                    v.findViewById<View>(R.id.heroLine).background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor(row.genre.color1), Color.parseColor(row.genre.color2)))
                    val hi = v.findViewById<ImageView>(R.id.heroIcon)
                    hi.setImageResource(row.genre.icon)
                    hi.imageTintList = ColorStateList.valueOf(Color.parseColor(row.genre.color1))
                    v.findViewById<TextView>(R.id.heroTitle).text = row.genre.name
                    v.findViewById<TextView>(R.id.heroSub).text = row.sub
                }
                is Row.More -> {
                    (v as TextView).text = row.text
                    v.setOnClickListener { row.onClick() }
                }
                is Row.Segment -> {
                    val box = v.findViewById<ViewGroup>(R.id.segBox)
                    box.removeAllViews()
                    row.labels.forEachIndexed { i, label ->
                        val t = TextView(this@MainActivity)
                        t.text = label
                        t.gravity = android.view.Gravity.CENTER
                        t.textSize = 15f
                        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
                        val on = i == row.selected
                        t.setTextColor(if (on) getColor(R.color.c_ff2ee6a8) else getColor(R.color.c_99ffffff))
                        if (on) t.setBackgroundResource(R.drawable.bg_segment_on) else t.background = null
                        t.layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        t.setOnClickListener { row.onSelect(i) }
                        box.addView(t)
                    }
                }
                is Row.Quick -> {
                    val box = v.findViewById<ViewGroup>(R.id.quickBox)
                    box.removeAllViews()
                    row.items.forEach { (icon, label, action) ->
                        val b = android.widget.LinearLayout(this@MainActivity)
                        val stacked = row.items.size >= 4
                        b.orientation = if (stacked) android.widget.LinearLayout.VERTICAL else android.widget.LinearLayout.HORIZONTAL
                        b.gravity = android.view.Gravity.CENTER
                        b.minimumHeight = dp(50f).toInt()
                        b.setBackgroundResource(R.drawable.bg_option)
                        val pad = dp(12f).toInt()
                        b.setPadding(pad, pad, pad, pad)
                        val lp = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        lp.marginEnd = dp(6f).toInt()
                        b.layoutParams = lp
                        val iv = ImageView(this@MainActivity)
                        iv.setImageResource(icon)
                        iv.imageTintList = ColorStateList.valueOf(getColor(R.color.c_ff2ee6a8))
                        iv.layoutParams = android.widget.LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt())
                        val tv = TextView(this@MainActivity)
                        tv.text = label
                        tv.setTextColor(getColor(R.color.c_ffffffff))
                        tv.textSize = if (stacked) 12f else 14f
                        if (stacked) tv.gravity = android.view.Gravity.CENTER
                        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
                        tv.maxLines = 1
                        tv.ellipsize = android.text.TextUtils.TruncateAt.END
                        if (stacked) tv.setPadding(0, dp(6f).toInt(), 0, 0) else tv.setPadding(dp(10f).toInt(), 0, 0, 0)
                        b.addView(iv); b.addView(tv)
                        b.setOnClickListener { action() }
                        box.addView(b)
                    }
                }
                is Row.Top -> {
                    val e = row.entry
                    bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION && !Covers.isCover(e.artwork), 14f)
                    v.findViewById<TextView>(R.id.label).text = row.label
                    v.findViewById<TextView>(R.id.title).text = e.title
                    v.findViewById<TextView>(R.id.subtitle).text = row.sub
                    v.setOnClickListener { row.onClick() }
                }
                is Row.Moments -> {
                    val ma = (v as RecyclerView).adapter as MomentAdapter
                    if (ma.list != row.list) { ma.list = row.list; ma.notifyDataSetChanged() }
                }
                is Row.Chips -> {
                    val box = v.findViewById<ViewGroup>(R.id.chipBox)
                    box.removeAllViews()
                    row.labels.forEachIndexed { i, label ->
                        val chip = TextView(this@MainActivity)
                        chip.text = label
                        chip.textSize = 14f
                        chip.setTypeface(chip.typeface, android.graphics.Typeface.BOLD)
                        val on = i == row.selected
                        chip.setTextColor(if (on) getColor(R.color.c_ff2ee6a8) else getColor(R.color.c_ccffffff))
                        chip.setBackgroundResource(if (on) R.drawable.bg_chip_sel else R.drawable.bg_chip_off)
                        val ph = dp(16f).toInt(); val pv = dp(9f).toInt()
                        chip.setPadding(ph, pv, ph, pv)
                        val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        lp.marginEnd = dp(8f).toInt()
                        chip.layoutParams = lp
                        chip.setOnClickListener { row.onSelect(i) }
                        box.addView(chip)
                    }
                }
            }
        }

        private fun bindTile(t: View, g: Genre) {
            t.clipToOutline = true
            t.findViewById<View>(R.id.tileGlow).background = glow(g.color1)
            t.findViewById<View>(R.id.tileLine).background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor(g.color1), Color.parseColor(g.color2)))
            val icon = t.findViewById<ImageView>(R.id.tileIcon)
            icon.setImageResource(g.icon)
            icon.imageTintList = ColorStateList.valueOf(Color.parseColor(g.color1))
            t.findViewById<TextView>(R.id.tileName).text = g.name
            t.setOnClickListener { openPage(Page.GenrePage(g)) }
        }

        private fun bindRow(v: View, row: Row.Item) {
            val e = row.entry
            bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION && !Covers.isCover(e.artwork), 12f)
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.subtitle).text =
                (if (e.preview && row.liveText == null && e.url.isNotBlank()) "${e.subtitle} · Vorschau" else e.subtitle) +
                    (row.meta?.let { " · $it" } ?: "")
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
            v.setOnLongClickListener { itemMenu(e, row.section); true }
        }
    }

    private inner class MomentAdapter : RecyclerView.Adapter<VH>() {
        var list: List<Moments.Moment> = emptyList()
        override fun getItemCount() = list.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_moment, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = holder.itemView
            val m = list[position]
            v.background = gradient(m.color1, m.color2, 20f)
            v.clipToOutline = true
            v.findViewById<View>(R.id.momentGlow).background = glow("#FFFFFFFF")
            v.findViewById<View>(R.id.momentGlow).alpha = 0.25f
            v.findViewById<ImageView>(R.id.momentIcon).setImageResource(m.icon)
            v.findViewById<TextView>(R.id.momentTitle).text = m.title
            v.findViewById<TextView>(R.id.momentSub).text = m.sub
            v.setOnClickListener { openMoment(m) }
        }
    }

    private fun openMoment(m: Moments.Moment) {
        when (m.action) {
            Moments.Action.HITMIX -> startHitMix(m.genreKey, "– ${m.title}")
            Moments.Action.KIDS_STORIES -> { storyAudience = 1; storySection = 0; selectTab(Tab.STORIES) }
            Moments.Action.ADULT_STORIES -> { storyAudience = 0; storySection = m.storyIndex; selectTab(Tab.STORIES) }
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<VH>() {
        var entries: List<Entry> = emptyList()
        var live = false

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
            if (Settings.compact(this@MainActivity)) {
                // Kompakte Ansicht: kleinere Karten
                val w = dp(108f).toInt()
                v.layoutParams = v.layoutParams.apply { width = w }
                val box = (v as ViewGroup).getChildAt(0)
                box.layoutParams = box.layoutParams.apply { width = w; height = w }
                v.findViewById<TextView>(R.id.title).textSize = 13f
                v.findViewById<TextView>(R.id.subtitle).textSize = 12f
            }
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val v = holder.itemView
            val e = entries[position]
            bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION && !Covers.isCover(e.artwork), 16f)
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.subtitle).text = e.subtitle
            v.findViewById<View>(R.id.badge).visibility = if (live) View.VISIBLE else View.GONE
            v.setOnClickListener { onEntryTap(e, entries) }
            v.setOnLongClickListener { itemMenu(e, entries); true }
        }
    }
}
