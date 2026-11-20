package de.gamingdoodz.radiofinder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private enum class Tab { HOME, KIDS, FAV }

    private sealed class Row {
        data class Header(val title: String, val sub: String? = null) : Row()
        data class Carousel(val entries: List<Entry>, val live: Boolean = false) : Row()
        data class Item(val entry: Entry, val section: List<Entry>, val live: Boolean = false) : Row()
        data class Status(val text: String, val done: Int, val total: Int) : Row()
        data class Action(val title: String, val sub: String, val dark: Boolean, val onClick: () -> Unit) : Row()
        data class Empty(val text: String) : Row()
    }

    // Ansichten
    private lateinit var input: EditText
    private lateinit var clear: ImageButton
    private lateinit var pageTitle: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var mini: View
    private lateinit var full: View
    private val adapter = MainAdapter()

    private var tab = Tab.HOME
    private val rows = mutableListOf<Row>()

    // Inhalte
    private var popular: List<Entry> = emptyList()
    private var charts: List<Entry> = emptyList()
    private var kidStations: List<Entry> = emptyList()
    private var kidSongs: List<Entry> = emptyList()
    private var kidsState = 0 // 0 = nicht geladen, 1 = lädt, 2 = fertig
    private var query = ""
    private val radioHits = mutableListOf<Entry>()
    private var stationHits: List<Entry> = emptyList()
    private var freeHits: List<Entry> = emptyList()
    private var freeLoading = false
    private var scanning = false
    private var scanDone = 0
    private var scanTotal = 0

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var searchId = 0

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { render(); updatePlayer() }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showFull(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backCallback)

        input = findViewById(R.id.search)
        clear = findViewById(R.id.clear)
        pageTitle = findViewById(R.id.pageTitle)
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
        clear.setOnClickListener {
            input.setText("")
            startSearch()
        }

        findViewById<View>(R.id.navHome).setOnClickListener { selectTab(Tab.HOME) }
        findViewById<View>(R.id.navKids).setOnClickListener { selectTab(Tab.KIDS) }
        findViewById<View>(R.id.navFav).setOnClickListener { selectTab(Tab.FAV) }

        // Mini-Player
        mini.setOnClickListener { showFull(true) }
        findViewById<View>(R.id.miniPlay).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.miniNext).setOnClickListener { controller?.seekToNextMediaItem() }

        // Großer Player
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
        Favorites.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        WatchService.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
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
        Favorites.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        WatchService.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    // ---------------- Laden ----------------

    private fun loadHome() {
        lifecycleScope.launch {
            val st = async { try { StationRepository.load(applicationContext, big = false) } catch (_: Exception) { emptyList() } }
            val ch = async { FreeMusic.charts() }
            popular = st.await().take(30).map { it.toEntry() }
            render()
            charts = ch.await()
            render()
        }
    }

    private fun loadKids() {
        if (kidsState != 0) return
        kidsState = 1
        lifecycleScope.launch {
            val st = async { try { StationRepository.kids(applicationContext) } catch (_: Exception) { emptyList() } }
            val songs = async { FreeMusic.kids() }
            kidStations = st.await().map { it.toEntry() }
            render()
            kidSongs = songs.await()
            kidsState = if (kidStations.isEmpty() && kidSongs.isEmpty()) 0 else 2
            render()
        }
    }

    private fun selectTab(t: Tab) {
        tab = t
        val accent = Color.parseColor("#FFF97316")
        val dim = Color.parseColor("#80FFFFFF")
        listOf(
            Triple(Tab.HOME, R.id.navHomeIcon, R.id.navHomeLabel),
            Triple(Tab.KIDS, R.id.navKidsIcon, R.id.navKidsLabel),
            Triple(Tab.FAV, R.id.navFavIcon, R.id.navFavLabel),
        ).forEach { (key, icon, label) ->
            val color = if (key == t) accent else dim
            findViewById<ImageView>(icon).imageTintList = ColorStateList.valueOf(color)
            findViewById<TextView>(label).setTextColor(color)
        }
        pageTitle.text = when (t) {
            Tab.HOME -> "Radio Finder"
            Tab.KIDS -> "Für Kinder"
            Tab.FAV -> "Favoriten"
        }
        if (t == Tab.KIDS) loadKids()
        render()
        recycler.scrollToPosition(0)
    }

    // ---------------- Suche ----------------

    private fun startSearch() {
        val q = input.text.toString().trim()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        searchJob?.cancel()
        val myId = ++searchId
        query = q
        radioHits.clear()
        stationHits = emptyList()
        freeHits = emptyList()
        scanning = false
        freeLoading = false
        if (tab != Tab.HOME) selectTab(Tab.HOME) else render()
        if (q.isEmpty()) return

        scanning = true
        freeLoading = true
        scanDone = 0
        scanTotal = 0
        render()

        searchJob = lifecycleScope.launch {
            launch {
                val free = FreeMusic.search(q)
                if (myId == searchId) { freeHits = free; freeLoading = false; render() }
            }
            val stations = try { StationRepository.load(applicationContext) } catch (_: Exception) { emptyList() }
            if (myId != searchId) return@launch
            val words = Scanner.words(q)
            stationHits = stations.filter { Scanner.matches(it.name, words) }.take(8).map { it.toEntry() }
            scanTotal = stations.size
            render()
            val result = Scanner.scan(
                stations, q,
                onProgress = { done, total ->
                    runOnUiThread {
                        if (myId == searchId) {
                            scanDone = done
                            scanTotal = total
                            updateStatusRow()
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

    // ---------------- Anzeige ----------------

    private fun render() {
        rows.clear()
        watchRow()?.let { rows.add(it) }
        when (tab) {
            Tab.HOME -> if (query.isEmpty()) renderHome() else renderSearch()
            Tab.KIDS -> renderKids()
            Tab.FAV -> renderFav()
        }
        adapter.notifyDataSetChanged()
    }

    private fun watchRow(): Row? {
        val q = WatchService.activeQuery(this) ?: return null
        return Row.Action(
            "Wächter sucht „$q“",
            WatchService.status(this) + " · Tippen zum Beenden",
            dark = true
        ) { WatchService.stop(this) }
    }

    private fun renderHome() {
        val favs = Favorites.all(this)
        if (favs.isNotEmpty()) {
            rows.add(Row.Header("Deine Favoriten"))
            rows.add(Row.Carousel(favs))
        }
        rows.add(Row.Header("Beliebte Sender", "Tippen und sofort hören"))
        if (popular.isEmpty()) rows.add(Row.Empty("Lade Sender…")) else rows.add(Row.Carousel(popular))
        rows.add(Row.Header("Freie Musik – Charts", "Komplett hören, beliebig weiterspringen"))
        if (charts.isEmpty()) rows.add(Row.Empty("Lade Musik…")) else rows.add(Row.Carousel(charts))
        rows.add(Row.Header("So geht's"))
        rows.add(
            Row.Empty(
                "Gib oben ein Lied ein. Radio Finder schaut, welches Radio es gerade spielt, " +
                    "und zeigt dir freie Musik dazu. Läuft es gerade nirgends, startest du den Wächter: " +
                    "Er meldet sich und spielt los, sobald das Lied irgendwo kommt."
            )
        )
    }

    private fun renderSearch() {
        if (scanning) rows.add(Row.Status(statusText(), scanDone, scanTotal))

        rows.add(Row.Header("Läuft gerade im Radio"))
        if (radioHits.isEmpty()) {
            rows.add(Row.Empty(if (scanning) "Suche läuft…" else "„$query“ läuft gerade auf keinem der Sender."))
        } else {
            val list = radioHits.toList()
            list.forEach { rows.add(Row.Item(it, list, live = true)) }
        }
        if (WatchService.activeQuery(this) == null) {
            rows.add(
                Row.Action(
                    "Wächter starten",
                    "Ich melde mich und spiele es ab, sobald „$query“ irgendwo läuft.",
                    dark = radioHits.isNotEmpty()
                ) {
                    WatchService.start(this, query)
                    Toast.makeText(this, "Wächter läuft – du kannst die App schließen", Toast.LENGTH_LONG).show()
                }
            )
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

    private fun renderKids() {
        rows.add(Row.Header("Kinderradios", "Rund um die Uhr Kindermusik"))
        when {
            kidStations.isNotEmpty() -> rows.add(Row.Carousel(kidStations))
            kidsState == 1 -> rows.add(Row.Empty("Lade…"))
            else -> rows.add(Row.Empty("Keine Kinderradios gefunden."))
        }
        rows.add(Row.Header("Kinderlieder", "Freie Lieder zum Durchhören"))
        when {
            kidSongs.isNotEmpty() -> kidSongs.forEach { rows.add(Row.Item(it, kidSongs)) }
            kidsState == 1 -> rows.add(Row.Empty("Lade…"))
            else -> rows.add(Row.Empty("Keine Lieder gefunden."))
        }
    }

    private fun renderFav() {
        val favs = Favorites.all(this)
        if (favs.isEmpty()) {
            rows.add(Row.Header("Noch leer"))
            rows.add(Row.Empty("Tipp bei einem Sender oder Lied auf das ♥, dann landet es hier – und in Android Auto unter Favoriten."))
            return
        }
        val stations = favs.filter { it.kind == Kind.STATION }
        val tracks = favs.filter { it.kind == Kind.TRACK }
        if (stations.isNotEmpty()) {
            rows.add(Row.Header("Sender"))
            stations.forEach { rows.add(Row.Item(it, stations)) }
        }
        if (tracks.isNotEmpty()) {
            rows.add(Row.Header("Lieder"))
            tracks.forEach { rows.add(Row.Item(it, tracks)) }
        }
    }

    private fun statusText() =
        if (scanTotal == 0) "Lade Senderliste…"
        else "Durchsuche Radiosender… $scanDone von $scanTotal" +
            if (Net.isUnmetered(this)) " (WLAN)" else " (mobil – im WLAN werden mehr Sender durchsucht)"

    private fun updateStatusRow() {
        val idx = rows.indexOfFirst { it is Row.Status }
        if (idx >= 0) {
            rows[idx] = Row.Status(statusText(), scanDone, scanTotal)
            adapter.notifyItemChanged(idx)
        }
    }

    // ---------------- Abspielen ----------------

    private fun play(entry: Entry, section: List<Entry>) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player startet noch, bitte nochmal tippen", Toast.LENGTH_SHORT).show()
            return
        }
        if (entry.kind == Kind.TRACK) {
            // Ganze Liste laden, damit man weiterspringen kann
            val tracks = section.filter { it.kind == Kind.TRACK }
            val idx = tracks.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
            c.setMediaItems(tracks.map { it.toMediaItem() }, idx, 0L)
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

    private fun showFull(show: Boolean) {
        if (show && controller?.currentMediaItem == null) return
        full.visibility = if (show) View.VISIBLE else View.GONE
        backCallback.isEnabled = show
        if (show) updatePlayer()
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
            c.playerError != null -> "Sender nicht erreichbar"
            isStation -> entry?.name ?: (m.artist?.toString() ?: "")
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
        findViewById<View>(R.id.miniNext).visibility = if (c.hasNextMediaItem()) View.VISIBLE else View.GONE
        bindArt(findViewById(R.id.miniArt), findViewById(R.id.miniInitials), art, name, isStation, 12f)

        if (full.visibility == View.VISIBLE) {
            findViewById<TextView>(R.id.fullTitle).text = title
            findViewById<TextView>(R.id.fullSub).text = sub
            findViewById<TextView>(R.id.fullSource).text = if (isStation) "INTERNETRADIO" else "FREIE MUSIK"
            findViewById<View>(R.id.fullBadge).visibility = if (isStation) View.VISIBLE else View.GONE
            findViewById<ImageButton>(R.id.fullPlay).setImageResource(playIcon)
            findViewById<ImageButton>(R.id.fullFav).setImageResource(if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            findViewById<View>(R.id.fullNext).visibility = if (c.hasNextMediaItem()) View.VISIBLE else View.GONE
            findViewById<View>(R.id.fullPrev).visibility = if (c.hasPreviousMediaItem()) View.VISIBLE else View.GONE
            bindArt(findViewById(R.id.fullArt), findViewById(R.id.fullInitials), art, name, isStation, 24f)
            val bg = findViewById<ImageView>(R.id.fullBg)
            if (art.isNotBlank()) bg.load(art) { crossfade(true) } else bg.setImageDrawable(null)
        }
    }

    // ---------------- Bilder ----------------

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

    /** Zeigt Logo/Cover; solange es lädt oder fehlt, bunte Kachel mit Anfangsbuchstaben. */
    private fun bindArt(image: ImageView, initialsView: TextView, url: String, name: String, station: Boolean, radiusDp: Float) {
        val color = Color.parseColor(palette[Math.floorMod(name.hashCode(), palette.size)])
        val tile = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color, adjustDark(color))
        ).apply { cornerRadius = dp(radiusDp) }
        initialsView.background = tile
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
                    // Senderlogos auf hellem Grund, damit Schrift-Logos lesbar bleiben
                    image.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FFF5F5F7")); cornerRadius = dp(radiusDp)
                    }
                    image.scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = (image.width * 0.12f).toInt().coerceAtLeast(4)
                    image.setPadding(p, p, p, p)
                } else {
                    image.background = GradientDrawable().apply {
                        setColor(Color.BLACK); cornerRadius = dp(radiusDp)
                    }
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                }
            })
        }
    }

    private fun adjustDark(color: Int): Int {
        val r = (Color.red(color) * 0.55f).toInt()
        val g = (Color.green(color) * 0.55f).toInt()
        val b = (Color.blue(color) * 0.55f).toInt()
        return Color.rgb(r, g, b)
    }

    // ---------------- Listen-Adapter ----------------

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
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val inf = LayoutInflater.from(parent.context)
            val layout = when (viewType) {
                0 -> R.layout.item_header
                1 -> R.layout.item_carousel
                2 -> R.layout.item_row
                3 -> R.layout.item_status
                4 -> R.layout.item_action
                else -> R.layout.item_empty
            }
            val v = inf.inflate(layout, parent, false)
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
                }
                is Row.Carousel -> {
                    val ca = (v as RecyclerView).adapter as CardAdapter
                    if (ca.entries != row.entries) {
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
            }
        }

        private fun bindRow(v: View, row: Row.Item) {
            val e = row.entry
            bindArt(v.findViewById(R.id.art), v.findViewById(R.id.initials), e.artwork, e.name, e.kind == Kind.STATION, 12f)
            v.findViewById<TextView>(R.id.title).text = e.title
            v.findViewById<TextView>(R.id.subtitle).text = e.subtitle
            v.findViewById<View>(R.id.badge).visibility = if (row.live) View.VISIBLE else View.GONE
            val fav = v.findViewById<ImageButton>(R.id.fav)
            fav.setImageResource(if (Favorites.isFav(this@MainActivity, e.id)) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            fav.setOnClickListener { Favorites.toggle(this@MainActivity, e) }
            v.setOnClickListener { play(e, row.section) }
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
            v.setOnClickListener { play(e, entries) }
            v.setOnLongClickListener {
                val now = Favorites.toggle(this@MainActivity, e)
                Toast.makeText(this@MainActivity, if (now) "♥ Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}
