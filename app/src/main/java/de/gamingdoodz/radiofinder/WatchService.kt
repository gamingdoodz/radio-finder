package de.gamingdoodz.radiofinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Der Wächter mit Wunschliste: sucht im Hintergrund nach allen Wunschliedern
 * und spielt das erste ab, sobald es irgendwo im Radio läuft. Danach geht's weiter.
 */
class WatchService : Service() {

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val ACTION_STOP = "de.gamingdoodz.radiofinder.STOP_WATCH"
        private const val ACTION_NOW = "de.gamingdoodz.radiofinder.WATCH_NOW"
        private const val CHANNEL_RUN = "watch_run"
        private const val CHANNEL_HIT = "watch_hit"
        private const val ID_RUN = 11
        private const val ID_HIT = 12
        private const val MAX_MINUTES = 60
        private const val ACTION_PLAY = "de.gamingdoodz.radiofinder.WATCH_PLAY"

        /** Läuft der Wächter schon länger als seine Stunde? Dann nicht wieder anwerfen. */
        fun expired(context: Context): Boolean {
            val since = prefs(context).getLong("since", 0L)
            return since > 0 && System.currentTimeMillis() - since > MAX_MINUTES * 60_000L
        }

        /**
         * Passt ein Radiotitel zu einem Wunsch? Wunsch "Titel — Künstler": alle Titelwörter müssen vorkommen,
         * der Künstler nur, wenn der Titel sehr kurz ist (sonst würde "Jingle Bells — Kinder Lieder" nie passen).
         */
        fun matchesWish(radioTitle: String, query: String): Boolean {
            val parts = query.split(" — ")
            val t = Scanner.normalize(radioTitle)
            if (parts.size < 2) return Scanner.matches(radioTitle, Scanner.words(query))
            val titleWords = Scanner.words(parts[0]).filter { it.length >= 2 }
            if (titleWords.isEmpty() || !titleWords.all { t.contains(it) }) return false
            val artistWords = Scanner.words(parts[1]).filter { it.length >= 3 }
            val artistHit = artistWords.isEmpty() || artistWords.any { t.contains(it) }
            return artistHit || titleWords.size >= 2
        }

        fun start(context: Context, query: String, skipStation: String? = null) {
            // Neue Stunde beginnt, wenn der Wächter gerade nicht läuft
            if (queries(context).isEmpty() || expired(context)) prefs(context).edit().putLong("since", System.currentTimeMillis()).apply()
            // "Nochmal hören": den Sender, auf dem es gerade läuft, eine Weile ignorieren
            if (skipStation != null) prefs(context).edit()
                .putString("skip:$query", skipStation + "|" + (System.currentTimeMillis() + 8 * 60_000L)).apply()
            val list = queries(context).toMutableList()
            if (list.none { it.equals(query, ignoreCase = true) }) list.add(query)
            saveQueries(context, list)
            ContextCompat.startForegroundService(
                context, Intent(context, WatchService::class.java).putExtra(EXTRA_QUERY, query)
            )
        }

        /** Viele Wunschlieder auf einmal (z. B. die ganzen Charts). */
        fun startMany(context: Context, list: List<String>) {
            if (list.isEmpty()) return
            if (queries(context).isEmpty() || expired(context)) prefs(context).edit().putLong("since", System.currentTimeMillis()).apply()
            val all = (queries(context) + list).distinctBy { it.lowercase() }
            saveQueries(context, all)
            ContextCompat.startForegroundService(
                context, Intent(context, WatchService::class.java).putExtra(EXTRA_QUERY, list.first())
            )
        }

        fun remove(context: Context, query: String) {
            val list = queries(context).filter { it != query }
            saveQueries(context, list)
            if (list.isEmpty()) stop(context)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)

        fun queries(context: Context): List<String> = try {
            val arr = JSONArray(prefs(context).getString("queries", "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }

        private fun saveQueries(context: Context, list: List<String>) {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            prefs(context).edit().putString("queries", arr.toString()).apply()
        }

        /** Für die Anzeige: erster Wunsch (+ Anzahl weitere) oder null, wenn aus. */
        fun activeQuery(context: Context): String? {
            val q = queries(context)
            return when {
                q.isEmpty() -> null
                q.size == 1 -> q[0]
                else -> "${q[0]} + ${q.size - 1} weitere"
            }
        }

        fun status(context: Context): String = prefs(context).getString("status", "") ?: ""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var waitJob: Job? = null
    private var logo: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Radar.init(this)
        createChannels()
        when (intent?.action) {
            ACTION_STOP -> { job?.cancel(); finish(); return START_NOT_STICKY }
            ACTION_NOW -> { waitJob?.cancel(); return START_NOT_STICKY }
            ACTION_PLAY -> {
                // Aus der Benachrichtigung "Jetzt hören" getippt
                val json = prefs(this).getString("pending", null)
                getSystemService(NotificationManager::class.java).cancel(ID_HIT)
                prefs(this).edit().remove("pending").apply()
                if (json != null) scope.launch {
                    try { playNow(Entry.fromJson(org.json.JSONObject(json))) } catch (_: Exception) {}
                    if (job?.isActive != true) finish()
                }
                if (job?.isActive == true) return START_NOT_STICKY
                ServiceCompat.startForeground(
                    this, ID_RUN, runningNotification("Starte Wiedergabe…", false),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                )
                return START_NOT_STICKY
            }
        }
        if (queries(this).isEmpty()) { finish(); return START_NOT_STICKY }
        ServiceCompat.startForeground(
            this, ID_RUN, runningNotification("Startet…", true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (job?.isActive != true) job = scope.launch { watch() }
        else setStatus("Neuer Wunsch dazu – suche…", true)
        return START_NOT_STICKY
    }

    /** Treffer auf einem Sender, der für diesen Wunsch gerade ignoriert wird? */
    private fun skipped(query: String, stationId: String): Boolean {
        val v = prefs(this).getString("skip:$query", null) ?: return false
        val (id, until) = v.split("|").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
        return id == stationId && System.currentTimeMillis() < until
    }

    private suspend fun watch() {
        val since = prefs(this).getLong("since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        val end = since + MAX_MINUTES * 60_000L
        var round = 0
        while (System.currentTimeMillis() < end) {
            val wishes = queries(this)
            if (wishes.isEmpty()) break
            round++
            setStatus("Suche läuft… (Runde $round)", true)
            val matchers = wishes.map { it to it }
            var found: Pair<String, Scanner.Hit>? = null
            // Erst den Server fragen (tausende Sender, kaum Daten)
            // Nur die Änderungen seit dem letzten Mal holen und hier vergleichen.
            // Das ist für den Server fast kostenlos, auch wenn tausende Wächter laufen.
            val serverOk = Radar.sync(force = true)
            if (serverOk) {
                found = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    val live = LiveIndex.fresh()
                    matchers.firstNotNullOfOrNull { (q, w) ->
                        live.firstOrNull { matchesWish(it.title, w) && !skipped(q, it.station.id) }?.let { q to Scanner.Hit(it.station, it.title) }
                    }
                }
            }
            val stations = if (found != null || serverOk) emptyList()
            else try { StationRepository.scanList(applicationContext) } catch (_: Exception) { emptyList() }
            if (stations.isNotEmpty()) try {
                Scanner.titles(stations) { s, title ->
                    if (found == null) {
                        matchers.firstOrNull { (q, w) -> matchesWish(title, w) && !skipped(q, s.id) }?.let { (q, _) ->
                            found = q to Scanner.Hit(s, title)
                            throw FoundSignal()
                        }
                    }
                }
            } catch (_: FoundSignal) {
            }
            val f = found
            if (f != null) {
                onFound(f.first, f.second)
                if (queries(this).isEmpty()) break
                // Das gefundene Lied in Ruhe hören lassen, dann weitersuchen
                setStatus("Gefunden – in ein paar Minuten suche ich weiter", false)
                waitFor(4 * 60_000L)
            } else {
                setStatus("Noch nicht gefunden – nächste Suche in 1 Minute", false)
                waitFor(60_000L)
            }
        }
        if (queries(this).isNotEmpty()) {
            notifyHit("Wächter beendet", "Deine Wunschlieder liefen in der letzten Stunde nicht.")
            saveQueries(this, emptyList())
        }
        finish()
    }

    private class FoundSignal : Exception()

    private suspend fun waitFor(ms: Long) {
        val w = scope.launch { delay(ms) }
        waitJob = w
        w.join()
    }

    private suspend fun onFound(query: String, hit: Scanner.Hit) {
        Scanner.lastHits = listOf(hit)
        saveQueries(this, queries(this).filter { it != query })
        val entry = hit.station.toEntry(hit.title)
        // Nur automatisch umschalten, wenn gerade Musik läuft. Sonst nicht einfach losspielen
        // (z. B. im Meeting), sondern Bescheid geben: "Jetzt hören" in der Benachrichtigung.
        val playing = try {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            val c = future.await()
            val p = c.isPlaying || c.playWhenReady
            MediaController.releaseFuture(future)
            p
        } catch (_: Exception) { false }
        if (playing) {
            notifyHit("Gefunden: „$query“ 🎉", "${hit.title}\nläuft jetzt auf ${hit.station.name}", null)
            playNow(entry)
        } else {
            prefs(this).edit().putString("pending", entry.toJson().toString()).apply()
            notifyHit("„$query“ läuft gerade! 🎉", "${hit.title}\nauf ${hit.station.name} – tippe auf „Jetzt hören“",
                serviceIntent(ACTION_PLAY, 3))
        }
    }

    private suspend fun playNow(entry: Entry) {
        try {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            val controller = future.await()
            controller.setMediaItem(entry.toMediaItem())
            controller.prepare()
            controller.play()
            delay(3000)
            MediaController.releaseFuture(future)
        } catch (_: Exception) {
        }
    }

    private fun setStatus(status: String, searching: Boolean) {
        prefs(this).edit().putString("status", status).apply()
        getSystemService(NotificationManager::class.java).notify(ID_RUN, runningNotification(status, searching))
    }

    private fun finish() {
        prefs(this).edit().remove("status").apply()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        prefs(this).edit().remove("status").apply()
        super.onDestroy()
    }

    // ---------- Benachrichtigungen ----------

    private fun logo(): Bitmap? {
        logo?.let { return it }
        val d = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return null
        val size = (64 * resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        logo = b
        return b
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, WatchService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun runningNotification(status: String, searching: Boolean): Notification {
        val wishes = queries(this)
        val title = if (wishes.size <= 1) "🔔 Wächter: „${wishes.firstOrNull() ?: ""}“" else "🔔 Wächter · ${wishes.size} Wunschlieder"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title).setSummaryText(status)
        wishes.take(6).forEach { style.addLine("♪  $it") }
        val b = NotificationCompat.Builder(this, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_bell)
            .setLargeIcon(logo())
            .setColor(0xFF2EE6A8.toInt())
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(R.drawable.ic_close, "Stopp", serviceIntent(ACTION_STOP, 1))
        if (!searching) b.addAction(R.drawable.ic_radar, "Jetzt suchen", serviceIntent(ACTION_NOW, 2))
        if (searching) b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyHit(title: String, text: String, play: PendingIntent? = null) {
        val b = NotificationCompat.Builder(this, CHANNEL_HIT)
            .setSmallIcon(R.drawable.ic_bell)
            .setLargeIcon(logo())
            .setColor(0xFF2EE6A8.toInt())
            .setContentTitle(title)
            .setContentText(text.replace("\n", " · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(play ?: openApp())
        if (play != null) b.addAction(R.drawable.ic_play, "Jetzt hören", play)
        val n = b.build()
        try {
            getSystemService(NotificationManager::class.java).notify(ID_HIT, n)
        } catch (_: SecurityException) {
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RUN, "Wächter läuft", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_HIT, "Lied gefunden", NotificationManager.IMPORTANCE_HIGH))
    }
}
