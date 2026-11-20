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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Der Wächter: sucht im Hintergrund jede Minute nach dem Lied
 * und spielt es sofort ab, sobald es irgendwo läuft (auch im Auto).
 */
class WatchService : Service() {

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val ACTION_STOP = "de.gamingdoodz.radiofinder.STOP_WATCH"
        private const val CHANNEL_RUN = "watch_run"
        private const val CHANNEL_HIT = "watch_hit"
        private const val ID_RUN = 11
        private const val ID_HIT = 12
        private const val MAX_MINUTES = 90

        fun start(context: Context, query: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, WatchService::class.java).putExtra(EXTRA_QUERY, query)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)

        fun activeQuery(context: Context): String? = prefs(context).getString("query", null)
        fun status(context: Context): String = prefs(context).getString("status", "") ?: ""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            finish()
            return START_NOT_STICKY
        }
        val query = intent?.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isEmpty()) {
            finish()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, ID_RUN, runningNotification(query, "Startet…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        setStatus(query, "Startet…")
        job?.cancel()
        job = scope.launch { watch(query) }
        return START_NOT_STICKY
    }

    private suspend fun watch(query: String) {
        val end = System.currentTimeMillis() + MAX_MINUTES * 60_000L
        var round = 0
        while (scope.isActive && System.currentTimeMillis() < end) {
            round++
            setStatus(query, "Suche läuft (Runde $round)…")
            val stations = try { StationRepository.load(applicationContext) } catch (_: Exception) { emptyList() }
            val hit = Scanner.scan(stations, query, stopAfterFirst = true).firstOrNull()
            if (hit != null) {
                onFound(query, hit)
                return
            }
            setStatus(query, "Noch nicht gefunden – nächste Suche in 1 Minute")
            delay(60_000)
        }
        notifyHit("Wächter beendet", "„$query“ lief in den letzten $MAX_MINUTES Minuten nirgends.")
        finish()
    }

    private suspend fun onFound(query: String, hit: Scanner.Hit) {
        Scanner.lastHits = listOf(hit)
        val entry = hit.station.toEntry(hit.title)
        notifyHit("„$query“ läuft jetzt! 🎉", "${hit.title} · ${hit.station.name}")
        // Gleich abspielen
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
        finish()
    }

    private fun setStatus(query: String, status: String) {
        prefs(this).edit().putString("query", query).putString("status", status).apply()
        getSystemService(NotificationManager::class.java).notify(ID_RUN, runningNotification(query, status))
    }

    private fun finish() {
        prefs(this).edit().remove("query").remove("status").apply()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        prefs(this).edit().remove("query").remove("status").apply()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun runningNotification(query: String, status: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Wächter: „$query“")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Stopp", stop)
            .build()
    }

    private fun notifyHit(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_HIT)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
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
