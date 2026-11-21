package de.gamingdoodz.radiofinder

import android.content.Context
import android.net.Uri

/** Musik- und Hörbuch-Dienste, in denen man ein Lied oder Hörspiel öffnen kann. */
object Services {
    data class Service(val key: String, val name: String, val searchUrl: String, val music: Boolean, val stories: Boolean, val pkg: String)

    val all = listOf(
        Service("spotify", "Spotify", "https://open.spotify.com/search/%s", true, true, "com.spotify.music"),
        Service("amazon", "Amazon Music", "https://music.amazon.de/search/%s", true, true, "com.amazon.mp3"),
        Service("ytmusic", "YouTube Music", "https://music.youtube.com/search?q=%s", true, true, "com.google.android.apps.youtube.music"),
        Service("youtube", "YouTube", "https://www.youtube.com/results?search_query=%s", true, true, "com.google.android.youtube"),
        Service("apple", "Apple Music", "https://music.apple.com/de/search?term=%s", true, true, "com.apple.android.music"),
        Service("deezer", "Deezer", "https://www.deezer.com/de/search/%s", true, true, "deezer.android.app"),
        Service("tidal", "Tidal", "https://listen.tidal.com/search?q=%s", true, false, "com.aspiro.tidal"),
        Service("soundcloud", "SoundCloud", "https://soundcloud.com/search?q=%s", true, false, "com.soundcloud.android"),
        Service("audible", "Audible", "https://www.audible.de/search?keywords=%s", false, true, "com.audible.application"),
    )

    /** Ist die App auf dem Handy installiert? (Wird nur lokal geprüft, nichts wird übertragen.) */
    fun installed(c: Context, s: Service): Boolean = try {
        c.packageManager.getPackageInfo(s.pkg, 0); true
    } catch (_: Exception) { false }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("services", Context.MODE_PRIVATE)

    /** Ausgewählte Dienste (leer = noch nichts gewählt). */
    fun mine(c: Context): Set<String> = prefs(c).getStringSet("mine", emptySet()) ?: emptySet()

    fun toggle(c: Context, key: String) {
        val s = mine(c).toMutableSet()
        if (!s.add(key)) s.remove(key)
        prefs(c).edit().putStringSet("mine", s).apply()
    }

    /** Dienste für Lieder bzw. Geschichten: selbst gewählte, sonst die installierten, sonst alle passenden. */
    fun forUse(c: Context, stories: Boolean): List<Service> {
        val fitting = all.filter { if (stories) it.stories else it.music }
        val own = fitting.filter { it.key in mine(c) }
        if (own.isNotEmpty()) return own
        val inst = fitting.filter { installed(c, it) }
        return inst.ifEmpty { fitting }
    }

    fun url(s: Service, query: String) = s.searchUrl.format(Uri.encode(query))
}
