package de.gamingdoodz.radiofinder

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Liest bei einem Internetradio kurz den Titel aus, der gerade läuft
 * (die "Titelanzeige", die viele Sender im Stream mitschicken).
 * Es werden nur ein paar Kilobyte geladen, dann wird die Verbindung beendet.
 */
object IcyReader {
    private val titleRegex = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL)

    fun fetchTitle(startUrl: String): String? {
        var url = URL(startUrl)
        var conn: HttpURLConnection? = null
        try {
            for (i in 0 until 4) {
                val c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.setRequestProperty("Icy-MetaData", "1")
                c.setRequestProperty("User-Agent", "Hitradar/1.0")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) return null
                    url = URL(url, loc)
                    continue
                }
                conn = c
                break
            }
            val c = conn ?: return null
            if (c.responseCode != 200) return null
            val metaInt = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull() ?: return null
            if (metaInt <= 0 || metaInt > 128_000) return null

            val input = BufferedInputStream(c.inputStream)
            for (block in 0 until 2) {
                skipFully(input, metaInt)
                val len = input.read()
                if (len < 0) return null
                if (len == 0) continue // in diesem Block kein Titel, nächsten probieren
                return parse(readFully(input, len * 16))
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(bytes: ByteArray): String? {
        var text = String(bytes, Charsets.UTF_8)
        if (text.contains('�')) text = String(bytes, Charsets.ISO_8859_1)
        val title = titleRegex.find(text)?.groupValues?.get(1)?.trim()
        return title?.takeIf { it.isNotBlank() && it != "-" }
    }

    private fun skipFully(input: InputStream, n: Int) {
        val tmp = ByteArray(8192)
        var rest = n
        while (rest > 0) {
            val r = input.read(tmp, 0, minOf(rest, tmp.size))
            if (r < 0) throw EOFException()
            rest -= r
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }
}
