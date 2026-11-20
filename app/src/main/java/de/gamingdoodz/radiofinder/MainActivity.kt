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
        object ChartsPage : Page()
        object GenresPage : Page()
        data class ShowPage(val show: Entry) : Page()
    }

    private sealed class Row {
        data class Header(val title: String, val sub: String? = null, val more: String? = null, val onMore: (() -> Unit)? = null) : Row()
        data class Carousel(val entries: List<Entry>, val live: Boolean = false) : Row()
        data class Item(val entry: Entry, val section: List<Entry>, val liveText: String? = null, val rank: Int? = null) : Row()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Radar.init(this)
        lifecycleScope.launch { if (Radar.sync(force = true)) render() }
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
                typing.removeCallbacks(typeSearch)
                if ((s?.trim()?.length ?: 0) >= 3 && input.hasFocus()) typing.postDelayed(typeSearch, 700)
            }
        })
        clear.setOnClickListener { input.setText(""); startSearch() }
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
        listOf(Favorites.prefs(this), WatchService.prefs(this), Recent.prefs(this), Settings.prefs(this), HitMix.prefs(this))
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
        Jamendo.highQuality = Settings.quality(this) >= 2
        startLiveRefresh()
        checkUpdate()
        // Wunschliste vorhanden, aber Wächter läuft nicht mehr (z. B. nach Neustart): weitermachen
        val wishes = WatchService.queries(this)
        if (wishes.isNotEmpty() && WatchService.status(this).isEmpty()) {
            try { WatchService.start(this, wishes.first()) } catch (_: Exception) {}
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
        tab = t
        page = null
        val accent = Color.parseColor("#FF2EE6A8")
        val dim = Color.parseColor("#80FFFFFF")
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
                return@launch
            }
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
            if (Services.mine(this).isEmpty()) "Tipp: Unter Mehr → Meine Musik-Dienste wählst du deine Apps aus." else null,
            list.map { sv -> Option(R.drawable.ic_note, sv.name, null) { go(sv) } })
    }

    private fun servicesSheet() {
        val mine = Services.mine(this)
        sheet("Meine Musik-Dienste", "Welche Apps hast du?", null, "", false,
            "Antippen zum An- und Abwählen. Die App zeigt dir dann nur diese an.",
            Services.all.map { sv ->
                Option(if (sv.key in mine) R.drawable.ic_heart else R.drawable.ic_heart_outline, sv.name,
                    if (sv.key in mine) "Ausgewählt" else null) {
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
    private fun updateStrip() {
        val strip = findViewById<View>(R.id.strip)
        val icon = findViewById<ImageView>(R.id.stripIcon)
        val text = findViewById<TextView>(R.id.stripText)
        val close = findViewById<View>(R.id.stripClose)
        val wish = WatchService.activeQuery(this)
        val mix = HitMix.activeName(this)
        when {
            wish != null && !kids() -> {
                strip.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_bell)
                text.text = "Wächter: $wish · ${WatchService.status(this)}"
                strip.setOnClickListener { watchSheet() }
                close.setOnClickListener { WatchService.stop(this) }
            }
            mix != null -> {
                strip.visibility = View.VISIBLE
                icon.setImageResource(R.drawable.ic_bolt)
                text.text = "Hit-Mix: $mix" + (HitMix.nowPlaying(this)?.let { " · $it" } ?: "")
                strip.setOnClickListener { showFull(true) }
                close.setOnClickListener { stopHitMix() }
            }
            else -> strip.visibility = View.GONE
        }
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
        val now = LiveIndex.fresh().associateBy { it.station.id }
        return dedupe(stations.mapNotNull { s -> now[s.id]?.takeIf { isMusic(it.title) }?.let { s.toEntry(it.title) } })
    }

    private fun isMusic(title: String) = LiveIndex.isMusic(title)

    /**
     * Viele Sender gehören zum selben Netzwerk und spielen dasselbe.
     * Gleiche Songs werden zu einem Eintrag zusammengefasst ("SWR3 + 3 weitere").
     */
    private fun dedupe(list: List<Entry>): List<Entry> =
        list.groupBy { if (it.kind == Kind.STATION && it.subtitle != "Internetradio") Scanner.normalize(it.title) else it.id }
            .values.map { group ->
                val first = group.first()
                if (group.size == 1) first else first.copy(subtitle = "${first.subtitle} + ${group.size - 1} weitere")
            }

    private fun liveText(e: Entry): String? =
        if (e.preview) LiveIndex.findSong(e.title, e.subtitle)?.let { "● LIVE auf ${it.station.name}" } else null

    private fun addSongs(list: List<Entry>, ranked: Boolean) {
        list.forEachIndexed { i, e -> rows.add(Row.Item(e, list, liveText(e), if (ranked) i + 1 else null)) }
    }

    private fun renderHome() {
        rows.add(Row.Header(Moments.greeting(), "Für dich jetzt"))
        val moments = Moments.now().toMutableList()
        if (Heard.all(this).size >= 3 || Favorites.all(this).any { it.kind == Kind.TRACK }) {
            moments.add(1.coerceAtMost(moments.size), Moments.Moment("mine", "Mein Mix", "Lieder, die dir gefallen – aus dem Radio", Moments.Action.HITMIX, "mine", "#FF2EE6A8", "#FF6366F1", R.drawable.ic_heart_white))
        }
        rows.add(Row.Moments(moments))

        val recent = Recent.all(this)
        val favs = Favorites.all(this)
        val again = (favs + recent).distinctBy { it.id }.take(20)
        if (again.isNotEmpty()) {
            rows.add(Row.Header("Weiterhören", "Deine Favoriten und zuletzt gehört"))
            rows.add(Row.Carousel(again))
        }

        val chartsLive = dedupe(topDe.mapNotNull { e -> LiveIndex.findSong(e.title, e.subtitle) }
            .distinctBy { it.station.id }.map { it.station.toEntry(it.title) })
        if (chartsLive.isNotEmpty()) {
            rows.add(Row.Header("Hits gerade im Radio", "Komplett hören – jetzt live"))
            rows.add(Row.Carousel(chartsLive, live = true))
        }

        val live = liveEntries(popular.take(60))
        rows.add(Row.Header("Gerade im Radio", "Was jetzt auf beliebten Sendern läuft"))
        if (live.isEmpty()) rows.add(Row.Empty("Schaue nach, was gerade läuft…")) else rows.add(Row.Carousel(live.take(25), live = true))

        rows.add(Row.Header("Deutsche Charts", "● LIVE = läuft gerade im Radio", "ALLE 100") { openPage(Page.ChartsPage) })
        if (topDe.isEmpty()) rows.add(Row.Empty("Lade Charts…")) else {
            addSongs(topDe.take(5), ranked = true)
            rows.add(Row.More("Alle 100 Charts anzeigen") { openPage(Page.ChartsPage) })
        }
    }

    private var musicAudience = 0 // 0 = Erwachsene, 1 = Kinder

    private fun audienceChips(selected: Int, onSelect: (Int) -> Unit) =
        Row.Segment(listOf("Für Erwachsene", "Für Kinder"), selected, onSelect)

    private fun renderFind() {
        if (!kids()) rows.add(audienceChips(musicAudience) { musicAudience = it; if (it == 1) loadKids(); render() })
        if (musicAudience == 1) { renderKidsMusic(); return }
        rows.add(hitMixRow(null, "Charts", "Nur aktuelle Hits aus dem Radio – springt automatisch weiter"))
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

    private fun renderSearch() {
        topRow()?.let { rows.add(it) }
        if (scanning) rows.add(Row.Status(statusText(), scanDone, scanTotal))
        else if (!deepSearch) rows.add(Row.More("📻 Alle Radios nach „$query“ durchsuchen") { startSearch() })

        rows.add(Row.Header("Läuft gerade im Radio", "Komplett hören"))
        if (radioHits.isEmpty()) {
            rows.add(Row.Empty(when {
                scanning -> "Suche läuft…"
                deepSearch -> "„$query“ läuft gerade auf keinem der Sender."
                else -> "Noch nicht durchsucht."
            }))
        } else {
            val list = dedupe(radioHits.toList())
            limited("radio", list, 5) { rows.add(Row.Item(it, list, "● LIVE")) }
        }
        if (WatchService.activeQuery(this) == null) {
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
        rows.add(Row.Header("Songs", "Tippen: im Radio hören, Vorschau oder Wächter"))
        when {
            songLoading -> rows.add(Row.Empty("Suche…"))
            songHits.isEmpty() -> rows.add(Row.Empty("Keine Songs gefunden."))
            else -> limited("songs", songHits, 5) { e -> rows.add(Row.Item(e, songHits, liveText(e))) }
        }

        if (stationHits.isNotEmpty()) {
            rows.add(Row.Header("Sender"))
            rows.add(Row.Carousel(stationHits))
        }

        storyShows["search"]?.takeIf { it.isNotEmpty() }?.let {
            rows.add(Row.Header("Geschichten & Podcasts"))
            rows.add(Row.Carousel(it))
        }
        rows.add(Row.Header("Freie Musik", "Jamendo · Audius · Internet Archive"))
        when {
            freeLoading -> rows.add(Row.Empty("Suche…"))
            freeHits.isEmpty() -> rows.add(Row.Empty("Nichts gefunden."))
            else -> limited("free", freeHits, 5) { rows.add(Row.Item(it, freeHits)) }
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

    private var storySection = 0
    private var storyAudience = 0 // 0 = Erwachsene, 1 = Kinder

    private fun renderStories() {
        if (!kids()) rows.add(audienceChips(storyAudience) { storyAudience = it; storySection = 0; render() })
        val cats = if (storyAudience == 1) Podcasts.kidsCategories else Podcasts.adultCategories
        val quick = mutableListOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_star, "Bekannte Serien", { famousSheet(storyAudience == 1) })
        )
        if (storyAudience == 1) quick.add(0, Triple(R.drawable.ic_sun, "Einschlafen", { bedtimeSheet() }))
        rows.add(Row.Quick(quick))
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
                    e.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim() + " " + e.subtitle.split(Regex("[,&]| feat| x ")).first().trim()
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
                rows.add(Row.Carousel(kidStations.map { e -> now[e.id.removePrefix("st:")]?.let { Entry(e.id, e.kind, it.title, e.name, e.url, e.artwork, e.name) } ?: e }))
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
        val favs = Favorites.all(this)
        val stations = favs.filter { it.kind == Kind.STATION }
        val tracks = favs.filter { it.kind == Kind.TRACK }
        val showsFav = favs.filter { it.kind == Kind.SHOW }
        if (showsFav.isNotEmpty()) {
            rows.add(Row.Header("Lieblingsgeschichten"))
            rows.add(Row.Carousel(showsFav))
        }
        rows.add(Row.Header("Lieblingssender"))
        if (stations.isEmpty()) rows.add(Row.Empty("Tipp bei einem Sender auf ♥ (bei Kacheln lange drücken).")) else rows.add(Row.Carousel(stations))
        rows.add(Row.Header("Lieblingslieder"))
        if (tracks.isEmpty()) rows.add(Row.Empty("Noch keine.")) else tracks.forEach { rows.add(Row.Item(it, tracks)) }
        val recent = Recent.all(this)
        if (recent.isNotEmpty()) {
            rows.add(Row.Header("Zuletzt gehört"))
            recent.forEach { rows.add(Row.Item(it, recent.filter { r -> r.kind == Kind.TRACK })) }
        }
        val heard = Heard.all(this).take(30)
        if (heard.isNotEmpty()) {
            rows.add(Row.Header("Im Radio gehört", "Wie hieß das Lied? Tippen für Wächter & mehr"))
            val list = heard.map { h ->
                val artist = h.title.substringBefore(" - ").substringBefore(" – ").trim()
                val title = h.title.substringAfter(" - ").substringAfter(" – ").trim()
                Entry("hd:" + h.title.hashCode().toString(16), Kind.TRACK, title, artist, "", "", title, preview = true)
            }
            list.forEach { e -> rows.add(Row.Item(e, list, liveText(e))) }
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
            Row.Setting(R.drawable.ic_eq, "Audioqualität", "Für freie Musik",
                value = listOf("Sparend", "Normal", "Hoch")[q.coerceIn(0, 2)]) { qualitySheet() },
            Row.Setting(R.drawable.ic_radio, "Lautstärke angleichen", "Alle Sender gleich laut",
                toggle = norm, chevron = false) { Settings.setNormalize(this, !norm); render() },
            Row.Setting(R.drawable.ic_note, "Meine Musik-Apps", "Zum Öffnen von Liedern",
                value = when (mine.size) { 0 -> "Wählen"; 1 -> mine[0].name; else -> "${mine.size} Apps" }) { servicesSheet() },
        ))

        val alarmOn = Settings.alarmOn(this)
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

        settingsGroup("Android Auto", listOf(
            Row.Setting(R.drawable.ic_play, "Automatisch weiterspielen",
                "In Android Auto „Musik automatisch starten“ einschalten", chevron = false) {
                Toast.makeText(this, "Android Auto → Einstellungen → „Musik automatisch starten“", Toast.LENGTH_LONG).show()
            },
        ))

        rows.add(Row.Empty(
            "Daten und Musik von radio-browser.info, Deezer, Jamendo, Audius, Internet Archive / LibriVox und Apple Podcasts. " +
                "Alle Rechte an Sendern, Liedern und Hörspielen liegen bei ihren Eigentümern."
        ))
    }

    private fun qualitySheet() {
        val names = listOf("Datensparend" to "96 kbit/s – ideal unterwegs", "Normal" to "96 kbit/s, Standard", "Hoch" to "≈ 192 kbit/s – beste Qualität, mehr Daten")
        sheet("Audioqualität", "Für freie Musik", null, "", false, null,
            names.mapIndexed { i, (n, sub) ->
                Option(if (i == Settings.quality(this)) R.drawable.ic_play else R.drawable.ic_eq, n, sub) {
                    Settings.setQuality(this, i)
                    Jamendo.highQuality = i >= 2
                    if (i == 0) Settings.setSaver(this, true)
                    render()
                }
            })
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
        if (e.kind == Kind.SHOW) { if ((page as? Page.ShowPage)?.show?.id != e.id) openPage(Page.ShowPage(e)); return }
        if (!e.preview) { play(e, section); return }
        val live = LiveIndex.findSong(e.title, e.subtitle)
        if (live != null) {
            Toast.makeText(this, "Läuft gerade auf ${live.station.name} – los geht's!", Toast.LENGTH_SHORT).show()
            play(live.station.toEntry(live.title), emptyList())
            return
        }
        songDialog(e)
    }

    private class Option(val icon: Int, val label: String, val sub: String?, val action: () -> Unit)

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

    private fun songDialog(e: Entry) {
        val clean = e.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
        val firstArtist = e.subtitle.split(Regex("[,&]| feat| x ")).first().trim()
        sheet(
            clean, e.subtitle, e.artwork, e.name, false,
            "Läuft gerade auf keinem bekannten Sender.",
            listOfNotNull(
                Option(R.drawable.ic_bell, "Wächter starten", "Spielt das Lied komplett ab, sobald es im Radio kommt") {
                    WatchService.start(this, "$clean $firstArtist")
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
        val indexTitle = if (isStation) LiveIndex.fresh().firstOrNull { "st:" + it.station.id == item.mediaId }?.title else null
        val title = live ?: indexTitle ?: m.title?.toString()?.takeIf { it.isNotBlank() } ?: entry?.name ?: ""
        if (isStation && live == null) checkStationTitle(item.mediaId, entry)
        val adLeft = adRemaining(c, item.mediaId, live)
        val sub = when {
            c.playbackState == Player.STATE_BUFFERING -> "Verbinde…"
            adLeft != null -> if (adLeft > 0) "Werbung · noch ca. ${fmt(adLeft)}" else "Werbung oder Ansage – gleich geht's los"
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
        val canNext = c.hasNextMediaItem() || HitMix.activeName(this) != null
        findViewById<View>(R.id.miniNext).visibility = if (canNext) View.VISIBLE else View.GONE
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
            findViewById<View>(R.id.fullNext).visibility = if (canNext) View.VISIBLE else View.GONE
            findViewById<View>(R.id.fullPrev).visibility = if (c.hasPreviousMediaItem()) View.VISIBLE else View.GONE
            findViewById<EqualizerView>(R.id.fullEq).apply { bars = 9; this.playing = c.isPlaying }
            findViewById<View>(R.id.fullSeek).visibility = if (isStation) View.INVISIBLE else View.VISIBLE
            bindArt(findViewById(R.id.fullArt), findViewById(R.id.fullInitials), art, name, isStation, 24f)
            val bg = findViewById<ImageView>(R.id.fullBg)
            if (art.isNotBlank()) bg.load(art) { crossfade(true) } else bg.setImageDrawable(null)
        }
        updateProgress()
    }

    /**
     * Läuft gerade Werbung am Senderanfang? Gibt die geschätzte Restzeit zurück
     * (0 = Dauer unbekannt) oder null, wenn keine Werbung erkannt wird.
     */
    private fun adRemaining(c: MediaController, mediaId: String, live: String?): Long? {
        if (!mediaId.startsWith("st:") || !c.isPlaying) return null
        val known = Ads.duration(this, mediaId.removePrefix("st:"))
        val elapsed = c.currentPosition
        if (Ads.isAd(live)) return (known - elapsed).coerceAtLeast(0)
        val musicNow = live != null && LiveIndex.isMusic(live)
        if (!musicNow && known > 0 && elapsed < known) return known - elapsed
        return null
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
            adRemaining(c, item.mediaId, live)?.let { left ->
                val t = if (left > 0) "Werbung · noch ca. ${fmt(left)}" else "Werbung oder Ansage – gleich geht's los"
                findViewById<TextView>(R.id.miniSub).text = t
                findViewById<TextView>(R.id.fullSub).text = t
            }
        }
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
                    val fg = if (row.dark) Color.WHITE else Color.parseColor("#FF08131C")
                    v.findViewById<TextView>(R.id.title).setTextColor(fg)
                    v.findViewById<TextView>(R.id.sub).setTextColor(if (row.dark) Color.parseColor("#B3FFFFFF") else Color.parseColor("#CC08131C"))
                    v.findViewById<ImageView>(R.id.icon).setImageResource(row.icon)
                    v.findViewById<ImageView>(R.id.icon).imageTintList =
                        ColorStateList.valueOf(if (row.dark) Color.parseColor("#FF2EE6A8") else Color.parseColor("#FF08131C"))
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
                        thumbTintList = ColorStateList.valueOf(if (row.toggle == true) Color.parseColor("#FF2EE6A8") else Color.parseColor("#FFB0B8C0"))
                        trackTintList = ColorStateList.valueOf(if (row.toggle == true) Color.parseColor("#802EE6A8") else Color.parseColor("#40FFFFFF"))
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
                        t.setTextColor(if (on) Color.parseColor("#FF2EE6A8") else Color.parseColor("#99FFFFFF"))
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
                        b.orientation = android.widget.LinearLayout.HORIZONTAL
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
                        iv.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF2EE6A8"))
                        iv.layoutParams = android.widget.LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt())
                        val tv = TextView(this@MainActivity)
                        tv.text = label
                        tv.setTextColor(Color.WHITE)
                        tv.textSize = 14f
                        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
                        tv.maxLines = 1
                        tv.ellipsize = android.text.TextUtils.TruncateAt.END
                        tv.setPadding(dp(10f).toInt(), 0, 0, 0)
                        b.addView(iv); b.addView(tv)
                        b.setOnClickListener { action() }
                        box.addView(b)
                    }
                }
                is Row.Top -> {
                    val e = row.entry
                    bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION, 14f)
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
                        chip.setTextColor(if (on) Color.parseColor("#FF2EE6A8") else Color.parseColor("#CCFFFFFF"))
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
            bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION, 12f)
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.subtitle).text =
                if (e.preview && row.liveText == null && e.url.isNotBlank()) "${e.subtitle} · Vorschau" else e.subtitle
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
