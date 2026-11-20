package de.gamingdoodz.radiofinder

import android.content.Context
import android.net.Uri

/** Musik- und Hörbuch-Dienste, in denen man ein Lied oder Hörspiel öffnen kann. */
object Services {
    data class Service(val key: String, val name: String, val searchUrl: String, val music: Boolean, val stories: Boolean)

    val all = listOf(
        Service("spotify", "Spotify", "https://open.spotify.com/search/%s", true, true),
        Service("amazon", "Amazon Music", "https://music.amazon.de/search/%s", true, true),
        Service("apple", "Apple Music", "https://music.apple.com/de/search?term=%s", true, true),
        Service("ytmusic", "YouTube Music", "https://music.youtube.com/search?q=%s", true, true),
        Service("deezer", "Deezer", "https://www.deezer.com/de/search/%s", true, true),
        Service("tidal", "Tidal", "https://listen.tidal.com/search?q=%s", true, false),
        Service("audible", "Audible", "https://www.audible.de/search?keywords=%s", false, true),
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("services", Context.MODE_PRIVATE)

    /** Ausgewählte Dienste (leer = noch nichts gewählt). */
    fun mine(c: Context): Set<String> = prefs(c).getStringSet("mine", emptySet()) ?: emptySet()

    fun toggle(c: Context, key: String) {
        val s = mine(c).toMutableSet()
        if (!s.add(key)) s.remove(key)
        prefs(c).edit().putStringSet("mine", s).apply()
    }

    /** Dienste für Lieder bzw. Geschichten: die eigenen, sonst alle passenden. */
    fun forUse(c: Context, stories: Boolean): List<Service> {
        val fitting = all.filter { if (stories) it.stories else it.music }
        val own = fitting.filter { it.key in mine(c) }
        return own.ifEmpty { fitting }
    }

    fun url(s: Service, query: String) = s.searchUrl.format(Uri.encode(query))
}
