package de.gamingdoodz.radiofinder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Lädt die neue Version herunter und installiert sie direkt aus der App. */
object Updater {

    /** Lädt die APK und übergibt sie dem Android-Installer. onProgress: 0..100 */
    suspend fun downloadAndInstall(context: Context, url: String, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var u = URL(url)
                var conn: HttpURLConnection
                // GitHub leitet auf einen Download-Server weiter
                var hops = 0
                while (true) {
                    conn = u.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("User-Agent", "RadioFinder")
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 5) {
                        u = URL(u, conn.getHeaderField("Location"))
                        conn.disconnect()
                        hops++
                        continue
                    }
                    if (code != 200) return@withContext false
                    break
                }
                val total = conn.contentLengthLong
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (total > 0) params.setSize(total)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("radiofinder.apk", 0, if (total > 0) total else -1).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var last = -1
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done += r
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != last) { last = p; withContext(Dispatchers.Main) { onProgress(p) } }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(context, 7, Intent(context, InstallReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
                conn.disconnect()
                true
            } catch (_: Exception) {
                false
            }
        }
}

/** Android fragt einmal nach, ob installiert werden darf – diese Abfrage zeigen wir an. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(confirm) } catch (_: Exception) {}
        }
    }
}
