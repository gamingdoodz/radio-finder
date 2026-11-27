package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * laut.fm-Sender veröffentlichen sekundengenau, wann das laufende Lied angefangen hat und wann es endet
 * (öffentliche, kostenlose Schnittstelle). Das Handy fragt direkt dort, der Hitradar-Server bleibt unbelastet.
 */
object LautFm {
    /** "https://stream.laut.fm/eins-a-hits?ref=x" -> "eins-a-hits" */
    fun stationName(url: String): String? {
        val m = Regex("(?i)^https?://(stream\\.)?laut\\.fm/([a-z0-9_-]+)").find(url.trim()) ?: return null
        return m.groupValues[2].lowercase()
    }

    class Song(val title: String, val startMs: Long, val durMs: Long)

    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT) }

    fun parseTime(s: String): Long = try { fmt.get()!!.parse(s.trim())?.time ?: 0L } catch (_: Exception) { 0L }

    /** Antwort von api.laut.fm/station/<name>/current_song auswerten. */
    fun parse(json: String): Song? = try {
        val o = JSONObject(json)
        val start = parseTime(o.optString("started_at"))
        val end = parseTime(o.optString("ends_at"))
        val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
        val title = o.optString("title")
        if (start <= 0 || title.isBlank()) null
        else Song(if (artist.isNotBlank()) "$artist - $title" else title, start, if (end > start) end - start else 0L)
    } catch (_: Exception) { null }

    suspend fun current(url: String): Song? {
        val name = stationName(url) ?: return null
        return withContext(Dispatchers.IO) {
            Net.get("https://api.laut.fm/station/$name/current_song", 4000, 4000, 20_000)?.let { parse(it) }
        }
    }
}
