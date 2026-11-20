package de.gamingdoodz.radiofinder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var input: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private val hits = mutableListOf<Scanner.Hit>()
    private val adapter = HitAdapter()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var searchId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.search)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        nowPlaying = findViewById(R.id.nowPlaying)

        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(hits[pos]) }

        findViewById<Button>(R.id.searchButton).setOnClickListener { startSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            controller?.stop()
            controller?.clearMediaItems()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (_: Exception) { return@addListener }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = updateNowPlaying()
            })
            updateNowPlaying()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun startSearch() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)

        searchJob?.cancel()
        val myId = ++searchId
        hits.clear()
        adapter.notifyDataSetChanged()
        progress.progress = 0
        status.text = "Lade Senderliste…"

        searchJob = lifecycleScope.launch {
            val stations = try { StationRepository.load(applicationContext) } catch (_: Exception) { emptyList() }
            if (myId != searchId) return@launch
            if (stations.isEmpty()) {
                status.text = "Senderliste konnte nicht geladen werden. Ist das Internet an?"
                return@launch
            }
            progress.max = stations.size
            val result = Scanner.scan(
                stations, query,
                onProgress = { done, total ->
                    runOnUiThread {
                        if (myId == searchId) {
                            progress.progress = done
                            status.text = "$done von $total Sendern geprüft · ${hits.size} Treffer"
                        }
                    }
                },
                onHit = { hit ->
                    runOnUiThread {
                        if (myId == searchId) {
                            hits.add(hit)
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            )
            if (myId != searchId) return@launch
            Scanner.lastHits = result
            status.text = if (result.isEmpty()) {
                "„$query“ läuft gerade nirgends. Probier's in ein paar Minuten nochmal."
            } else {
                "Fertig: ${result.size} Treffer. Antippen zum Hören."
            }
        }
    }

    private fun play(hit: Scanner.Hit) {
        val c = controller
        if (c == null) {
            Toast.makeText(this, "Player startet noch, bitte nochmal tippen", Toast.LENGTH_SHORT).show()
            return
        }
        c.setMediaItem(hit.station.toMediaItem(hit.title))
        c.prepare()
        c.play()
        nowPlaying.text = "Verbinde mit ${hit.station.name}…"
    }

    private fun updateNowPlaying() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null || c.playbackState == Player.STATE_IDLE) {
            nowPlaying.text = "Nichts läuft"
            return
        }
        val title = c.mediaMetadata.title ?: item.mediaMetadata.title ?: ""
        val station = item.mediaMetadata.artist ?: ""
        val state = when {
            c.playbackState == Player.STATE_BUFFERING -> "⏳"
            c.isPlaying -> "▶"
            else -> "⏸"
        }
        nowPlaying.text = "$state $title\n$station"
    }

    private inner class HitAdapter : BaseAdapter() {
        override fun getCount() = hits.size
        override fun getItem(position: Int) = hits[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val hit = hits[position]
            v.findViewById<TextView>(android.R.id.text1).text = hit.title
            v.findViewById<TextView>(android.R.id.text2).text = hit.station.name
            return v
        }
    }
}
