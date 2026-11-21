package de.gamingdoodz.radiofinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover zum Lied, das gerade im Radio läuft ("Künstler - Titel"), über die Deezer-Suche.
 * Gemerkt wird auch, wenn nichts gefunden wurde, damit nicht ständig neu gesucht wird.
 */
object Covers {
    class Info(val artwork: String, val title: String, val artist: String)

    private val cache = ConcurrentHashMap<String, Info>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val NONE = Info("", "", "")
    private val coverUrls = ConcurrentHashMap.newKeySet<String>()

    /** Ist dieses Bild ein Lied-Cover (und kein Senderlogo)? */
    fun isCover(url: String) = url in coverUrls

    private fun key(radioTitle: String) = Scanner.normalize(radioTitle)

    /** Schon bekannt? (ohne zu suchen) */
    fun get(radioTitle: String): Info? = cache[key(radioTitle)]?.takeIf { it !== NONE }

    fun known(radioTitle: String) = cache.containsKey(key(radioTitle)) || key(radioTitle) in missing

    /** Sucht das Cover (einmal pro Lied). */
    suspend fun load(radioTitle: String): Info? = withContext(Dispatchers.IO) {
        val k = key(radioTitle)
        cache[k]?.let { return@withContext it.takeIf { i -> i !== NONE } }
        if (k in missing) return@withContext null
        val artist = radioTitle.substringBefore(" - ").substringBefore(" – ").trim()
        val title = radioTitle.substringAfter(" - ").substringAfter(" – ").replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
        val hit = Deezer.search("$artist $title", 5).firstOrNull { e ->
            LiveIndex.Song(e).matches(k)
        }
        if (hit == null || hit.artwork.isBlank()) { missing.add(k); null }
        else Info(hit.artwork, hit.title, hit.subtitle).also { cache[k] = it; coverUrls.add(hit.artwork) }
    }

    /** Eintrag für einen Sender mit Lied: Cover und schöne Schreibweise, wenn bekannt. */
    fun dress(e: Entry): Entry {
        if (e.kind != Kind.STATION || e.subtitle == "Internetradio") return e
        val i = get(e.title) ?: return e
        return e.copy(title = "${i.artist} - ${i.title}", artwork = i.artwork)
    }
}
