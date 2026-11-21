package de.gamingdoodz.radiofinder

import android.content.Context
import android.net.ConnectivityManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Net {
    /** Lädt eine Seite als Text (höchstens maxBytes), oder null bei Fehler. */
    fun get(url: String, connectMs: Int = 8000, readMs: Int = 12000, maxBytes: Int = 4_000_000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.setRequestProperty("User-Agent", "Hitradar/1.0")
        try {
            if (c.responseCode != 200) null else {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                c.inputStream.use { input ->
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        if (out.size() > maxBytes) return null
                    }
                }
                out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** true im WLAN (bzw. bei Verbindungen ohne Datenvolumen). */
    /** Gibt es überhaupt Internet? */
    fun online(context: Context): Boolean = try {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
    } catch (_: Exception) {
        true
    }

    fun isUnmetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    } catch (_: Exception) {
        false
    }
}
