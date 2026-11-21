package de.gamingdoodz.radiofinder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlists mit bekannten Liedern: fertige (von Deezer) und eigene.
 * Abgespielt werden sie als Radio-Mix: Hitradar sucht die Lieder live im Radio und spielt sie komplett.
 */
object Playlists {
    /** Fertige Playlists: Anzeigename to Suchbegriff bei Deezer. */
    val curated = listOf(
        "Top Hits Deutschland" to "Top Deutschland",
        "Weihnachtshits" to "Weihnachtshits",
        "Party Hits" to "Party Hits",
        "80er Hits" to "80er Hits",
        "90er Hits" to "90er Hits",
        "2000er Hits" to "2000er Hits",
        "Deutschpop" to "Deutschpop Hits",
        "Schlager Hits" to "Schlager Hits",
        "Rock Klassiker" to "Rock Classics",
        "Deutschrap" to "Deutschrap Hits",
        "Chill & Entspannen" to "Chill Hits",
        "Love Songs" to "Love Songs",
        "Sommerhits" to "Sommerhits",
        "Workout" to "Workout Hits",
        "Kinderlieder" to "Kinderlieder Hits",
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    /** Eigene Playlists: Name -> Lieder (in Reihenfolge der Erstellung). */
    fun mine(c: Context): LinkedHashMap<String, List<Entry>> {
        val out = LinkedHashMap<String, List<Entry>>()
        try {
            val o = JSONObject(prefs(c).getString("all", "{}") ?: "{}")
            o.keys().forEach { name ->
                val arr = o.getJSONArray(name)
                out[name] = (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(c: Context, all: Map<String, List<Entry>>) {
        val o = JSONObject()
        all.forEach { (name, list) -> o.put(name, JSONArray().apply { list.forEach { put(it.toJson()) } }) }
        prefs(c).edit().putString("all", o.toString()).apply()
    }

    fun create(c: Context, name: String) {
        val all = mine(c)
        if (!all.containsKey(name)) { all[name] = emptyList(); save(c, all) }
    }

    fun delete(c: Context, name: String) {
        val all = mine(c); all.remove(name); save(c, all)
    }

    /** Lied hinzufügen (gespeichert als Titel + Künstler, damit es im Radio gefunden wird). */
    fun add(c: Context, name: String, song: Entry): Boolean {
        val all = mine(c)
        val list = all[name].orEmpty()
        val key = Scanner.normalize(song.subtitle + " " + song.title)
        if (list.any { Scanner.normalize(it.subtitle + " " + it.title) == key }) return false
        all[name] = list + song
        save(c, all)
        Radar.event("playlist", "${song.subtitle} - ${song.title}")
        return true
    }

    fun remove(c: Context, name: String, song: Entry) {
        val all = mine(c)
        all[name] = all[name].orEmpty().filter { it.id != song.id }
        save(c, all)
    }

    fun get(c: Context, name: String): List<Entry> = mine(c)[name].orEmpty()
}

/** Was ist neu? Wird unter Mehr → Neuerungen angezeigt (neueste zuerst). */
object Changes {
    val list = listOf(
        "November 2026 – großes Update" to listOf(
            "Übersichtlichere Startseite: Schnellstart, Jetzt im Radio, Meistgespielt, Charts",
            "Playlists: fertige Playlists mit bekannten Liedern und eigene Playlists – als Radio-Mix abspielen",
            "Wünsch dir was direkt im Suchfeld (✨) – auch per Sprache",
            "Radiosender suchen: alle rund 50.000 Sender",
            "Hit-Mix nutzt alle laufenden Sender und spielt bekannte Lieder zuerst",
            "Werbung am Senderanfang wird erkannt und für alle gemerkt",
            "Wächter repariert: findet Lieder zuverlässig, läuft höchstens 1 Stunde",
            "Hell- und Dunkel-Design, Schriftgröße, kompakte Ansicht",
            "Meistgespielt im Radio: was heute auf allen Sendern am häufigsten lief",
            "Mehr davon: ähnliche Lieder, gelernt aus dem Radio",
            "Lied-Cover statt Senderlogos, auch in Android Auto und im Widget",
            "Großes Widget, Teilen-Knopf, Verlauf mit Uhrzeit",
        ),
    )
}
