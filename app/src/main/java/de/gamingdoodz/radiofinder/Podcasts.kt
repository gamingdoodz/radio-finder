package de.gamingdoodz.radiofinder

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder

/**
 * Geschichten, Hörspiele und Hörbücher über Podcasts (frei und legal).
 * Shows kommen aus dem Apple-Podcast-Verzeichnis, Folgen direkt aus dem Feed.
 */
object Podcasts {

    data class Category(val key: String, val name: String, val term: String, val kids: Boolean)

    val kidsCategories = listOf(
        Category("kidsbest", "Beliebte Kinder-Hörspiele", "Ohrenbär|Kakadu|Figarino|Die Maus Hörspiel|Kinderhörspiel ARD|Gute Nacht Geschichten|Sandmännchen", true),
        Category("gutenacht", "Gute-Nacht-Geschichten", "Gute Nacht Geschichten Kinder", true),
        Category("maerchen", "Märchen", "Märchen für Kinder", true),
        Category("kinderhoerspiel", "Hörspiele für Kinder", "Kinderhörspiel", true),
        Category("kinderwissen", "Wissen für Kinder", "Wissen für Kinder", true),
        Category("maerchenfrei", "Märchen-Hörbücher", "librivox:märchen OR grimm OR andersen OR kinder", true),
    )

    val adultCategories = listOf(
        Category("best", "Beliebte Hörspiele", "ARD Hörspiel|Radio Tatort|ARD Krimi Hörspiel|Hörspiel Deutschlandfunk|SWR2 Hörspiel|WDR Hörspiel Speicher|Sherlock Holmes Hörspiel", false),
        Category("krimi", "Krimi & Hörspiel", "Krimi Hörspiel", false),
        Category("klassiker", "Klassiker (frei)", "librivox:", false),
        Category("hoerbuch", "Hörbuch-Podcasts", "Hörbuch", false),
        Category("grusel", "Gruselgeschichten", "Gruselgeschichten", false),
        Category("truecrime", "True Crime", "True Crime deutsch", false),
        Category("comedy", "Comedy", "Comedy Podcast deutsch", false),
    )

    val all get() = kidsCategories + adultCategories

    private val showCache = HashMap<String, List<Entry>>()
    private val episodeCache = HashMap<String, Pair<Long, List<Entry>>>()

    /** Shows zu einer Kategorie (bei Kindern ohne als "explicit" markierte). */
    suspend fun shows(cat: Category): List<Entry> {
        synchronized(showCache) { showCache[cat.key]?.let { return it } }
        val list = when {
            cat.term.startsWith("librivox:") -> Archive.librivox(cat.term.removePrefix("librivox:"))
            // Mehrere Suchbegriffe: je die bekanntesten Treffer zusammenführen
            cat.term.contains('|') -> cat.term.split('|').flatMap { search(it.trim(), cat.kids, limit = 4) }.distinctBy { it.id }
            else -> search(cat.term, cat.kids)
        }
        if (list.isNotEmpty()) synchronized(showCache) { showCache[cat.key] = list }
        return list
    }

    suspend fun search(term: String, kidsOnly: Boolean = false, limit: Int = 20): List<Entry> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?media=podcast&country=de&limit=$limit&term=" +
            URLEncoder.encode(term, "UTF-8")
        val text = Net.get(url) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(text).optJSONArray("results") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val feed = o.optString("feedUrl")
                if (!feed.startsWith("http")) return@mapNotNull null
                if (o.optString("collectionExplicitness") == "explicit") return@mapNotNull null
                val nameCheck = o.optString("collectionName") + " " + o.optString("artistName")
                if (Regex("(?i)\\bsex|erotik|erotisch|porn|18\\+|nsfw").containsMatchIn(nameCheck)) return@mapNotNull null
                // Kinder-Bereich: nur Shows aus "Kinder und Familie" oder mit Kinder im Namen (sonst kamen z. B. Comedy-Podcasts mit)
                if (kidsOnly) {
                    val g = o.optJSONArray("genres")
                    val genres = (0 until (g?.length() ?: 0)).joinToString(" ") { g?.optString(it).orEmpty() } + " " + o.optString("primaryGenreName")
                    val kidsGenre = Regex("(?i)kinder|kids|famil|children").containsMatchIn(genres)
                    val kidsName = Regex("(?i)kinder|kids|kind\\b|märchen|maerchen|gute.?nacht|hörspiel für|ohrenbär|kakadu|sandmann|maus").containsMatchIn(o.optString("collectionName"))
                    if (!kidsGenre && !kidsName) return@mapNotNull null
                }
                val name = o.optString("collectionName").ifBlank { o.optString("trackName") }
                Entry(
                    id = "pd:" + o.optLong("collectionId"),
                    kind = Kind.SHOW,
                    title = name,
                    subtitle = o.optString("artistName"),
                    url = feed,
                    artwork = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") },
                    name = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Folgen einer Show in Erscheinungs-Reihenfolge: Folge 1 zuerst. */
    suspend fun episodes(show: Entry): List<Entry> =
        if (show.url.startsWith("ia:")) Archive.chapters(show) else feedEpisodes(show)

    private suspend fun feedEpisodes(show: Entry): List<Entry> = withContext(Dispatchers.IO) {
        synchronized(episodeCache) {
            episodeCache[show.url]?.let { (t, l) -> if (System.currentTimeMillis() - t < 30 * 60_000L) return@withContext l }
        }
        val xml = Net.get(show.url, 10_000, 20_000, 15_000_000) ?: return@withContext emptyList()
        val list = try { parse(xml, show).asReversed().toList() } catch (_: Exception) { emptyList() }
        if (list.isNotEmpty()) synchronized(episodeCache) { episodeCache[show.url] = System.currentTimeMillis() to list }
        list
    }

    private fun parse(xml: String, show: Entry): List<Entry> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val out = mutableListOf<Entry>()
        var inItem = false
        var title = ""
        var audio = ""
        var image = ""
        var channelImage = ""
        var text = StringBuilder()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < 2000) {   // lange Feeds: auch die ersten Folgen finden
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "item" -> { inItem = true; title = ""; audio = ""; image = "" }
                        "enclosure" -> if (inItem) {
                            val type = p.getAttributeValue(null, "type").orEmpty()
                            val url = p.getAttributeValue(null, "url").orEmpty()
                            if (url.startsWith("http") && (type.startsWith("audio") || type.isEmpty())) audio = url
                        }
                        "itunes:image" -> {
                            val href = p.getAttributeValue(null, "href").orEmpty()
                            if (inItem) image = href else if (channelImage.isEmpty()) channelImage = href
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "title" -> if (inItem) title = text.toString().trim()
                    "item" -> {
                        inItem = false
                        if (audio.isNotEmpty() && title.isNotEmpty()) {
                            out.add(
                                Entry(
                                    id = "pc:" + audio.hashCode().toString(16),
                                    kind = Kind.TRACK,
                                    title = title,
                                    subtitle = show.name,
                                    url = audio,
                                    artwork = image.ifBlank { show.artwork.ifBlank { channelImage } },
                                    name = title,
                                )
                            )
                        }
                    }
                }
            }
            event = try { p.next() } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
        }
        return out
    }
}

/** Merkt sich, wo man bei langen Folgen (Geschichten, Hörbücher) aufgehört hat. */
object Positions {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun get(context: Context, id: String): Long = prefs(context).getLong(id, 0L)

    fun save(context: Context, id: String, pos: Long, duration: Long) {
        if (!id.startsWith("pc:") && !id.startsWith("ia:")) return
        val e = prefs(context).edit()
        // Fast am Ende: als fertig gehört merken
        if (duration > 0 && pos > duration - 30_000) e.remove(id).putBoolean("done:$id", true)
        else if (pos > 15_000) e.putLong(id, pos).putLong("dur:$id", duration)
        e.apply()
    }

    fun isDone(context: Context, id: String) = prefs(context).getBoolean("done:$id", false)

    /** "noch 23 Min." für angefangene Folgen, sonst null. */
    fun leftText(context: Context, id: String): String? {
        val pos = get(context, id).takeIf { it > 0 } ?: return null
        val dur = prefs(context).getLong("dur:$id", 0L)
        return if (dur > pos) "▶ noch ${(dur - pos) / 60_000 + 1} Min." else "▶ weiter bei ${pos / 60_000}:%02d".format(pos / 1000 % 60)
    }

    /** Welche Folge als Nächstes? Angefangene zuerst, sonst die nach der zuletzt fertig gehörten, sonst Folge 1. */
    fun nextIndex(context: Context, eps: List<Entry>): Int {
        if (eps.isEmpty()) return 0
        eps.indexOfFirst { get(context, it.id) > 0 }.takeIf { it >= 0 }?.let { return it }
        val lastDone = eps.indexOfLast { isDone(context, it.id) }
        return if (lastDone >= 0 && lastDone + 1 < eps.size) lastDone + 1 else if (lastDone == eps.size - 1) 0 else 0
    }
}
