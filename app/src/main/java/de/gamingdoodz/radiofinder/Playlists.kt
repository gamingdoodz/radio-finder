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

    /** Eine Playlist: query = Deezer-Suche oder besondere Quelle ("community", "rising", "radiocharts"). */
    data class Def(val name: String, val sub: String, val query: String)

    /** Gerade angesagt: wird laufend aus dem Radio und von den Hitradar-Hörern berechnet. */
    val trending = listOf(
        Def("Neu & angesagt im Radio", "Lieder, die gerade öfter laufen", "rising"),
        Def("Meistgespielt im Radio", "Die letzten 24 Stunden", "radiocharts"),
        Def("Beliebt bei Hitradar-Hörern", "Von allen gelernt", "community"),
    )

    /** Passend zur Jahreszeit (Monat 1–12). */
    private fun season(month: Int, day: Int): List<Def> = buildList {
        when (month) {
            12 -> { add(Def("Weihnachtshits", "Die schönsten Weihnachtslieder", "Weihnachtshits")); add(Def("Winter-Kuschelsongs", "Für lange Abende", "Winter Chill")) }
            1 -> { add(Def("Après-Ski Hits", "Hüttengaudi", "Apres Ski Hits")); add(Def("Neues Jahr, neue Energie", "Motivation", "Motivation Hits")) }
            2 -> { add(Def("Fasching & Karneval", "Party bis Aschermittwoch", "Karneval Hits")); add(Def("Après-Ski Hits", "Hüttengaudi", "Apres Ski Hits")) }
            3, 4 -> { add(Def("Frühlingsgefühle", "Sonne tanken", "Frühling Hits")); add(Def("Gute Laune", "Für sonnige Tage", "Gute Laune Hits")) }
            5 -> { add(Def("Festival-Hits", "Die Festivalsaison startet", "Festival Hits")); add(Def("Frühlingsgefühle", "Sonne tanken", "Frühling Hits")) }
            6, 7, 8 -> { add(Def("Sommerhits", "Die Hits des Sommers", "Sommerhits")); add(Def("Grillparty", "Für laue Abende", "Grillparty")); add(Def("Roadtrip", "Fenster runter, laut drehen", "Roadtrip")) }
            9 -> { add(Def("Oktoberfest Hits", "O'zapft is!", "Oktoberfest Hits")); add(Def("Spätsommer", "Die letzten warmen Tage", "Sommerhits")) }
            10 -> { if (day <= 6) add(Def("Oktoberfest Hits", "O'zapft is!", "Oktoberfest Hits")); add(Def("Herbst-Chill", "Gemütlich in den Herbst", "Herbst Chill")); if (day >= 20) add(Def("Halloween Party", "Gruselig gut", "Halloween Party")) }
            else -> { add(Def("Herbst-Chill", "Gemütlich in den Herbst", "Herbst Chill")); add(Def("Kuschelsongs", "Für graue Tage", "Kuschelsongs")); if (day >= 20) add(Def("Bald ist Weihnachten", "Die ersten Weihnachtslieder", "Weihnachtshits")) }
        }
    }

    /** Passend zur Tageszeit und zum Wochentag. */
    private fun moment(hour: Int, weekday: Int): List<Def> = buildList {
        val weekend = weekday == java.util.Calendar.SATURDAY || weekday == java.util.Calendar.SUNDAY
        when {
            hour in 5..9 && !weekend -> add(Def("Guten Morgen", "Gut in den Tag starten", "Good Morning Hits"))
            hour in 16..18 && !weekend -> add(Def("Feierabend", "Endlich frei", "Feierabend Hits"))
            hour >= 18 && (weekday == java.util.Calendar.FRIDAY || weekday == java.util.Calendar.SATURDAY) ->
                add(Def("Freitagabend Party", "Das Wochenende beginnt", "Party Hits"))
            hour >= 21 || hour < 5 -> add(Def("Zum Einschlafen", "Ruhig und entspannt", "Sleep Music"))
        }
        if (weekday == java.util.Calendar.SUNDAY && hour in 8..20) add(Def("Sonntags-Chill", "Ganz entspannt", "Sunday Chill"))
    }

    /** Jede Woche 4 andere: so gibt es immer wieder neue Playlists. */
    private val pool = listOf(
        Def("Throwback Hits", "Erinnerst du dich?", "Throwback Hits"), Def("Deutsche Klassiker", "Zum Mitsingen", "Deutsche Klassiker"),
        Def("Ballermann Hits", "Party wie auf Malle", "Ballermann Hits"), Def("Akustik", "Unplugged & ruhig", "Acoustic Hits"),
        Def("Dance Classics", "Die großen Clubhits", "Dance Classics"), Def("Rock-Hymnen", "Laut aufdrehen", "Rock Anthems"),
        Def("One-Hit-Wonder", "Einmal ganz oben", "One Hit Wonder"), Def("Filmmusik", "Die besten Soundtracks", "Movie Soundtrack Hits"),
        Def("Disco Fieber", "Ab auf die Tanzfläche", "Disco Hits"), Def("Neue Deutsche Welle", "Die 80er auf Deutsch", "Neue Deutsche Welle"),
        Def("Eurodance", "Die 90er tanzen", "Eurodance"), Def("Hip-Hop Klassiker", "Old School", "Hip Hop Classics"),
        Def("Latin Hits", "Sommer im Herzen", "Latin Hits"), Def("Reggae Vibes", "Ganz entspannt", "Reggae Hits"),
        Def("Singer-Songwriter", "Ehrliche Lieder", "Singer Songwriter"), Def("Pop-Punk", "Skater-Jahre", "Pop Punk"),
        Def("Indie Hits", "Abseits vom Mainstream", "Indie Hits"), Def("Motivation", "Volle Energie", "Motivation Hits"),
        Def("Autofahren", "Für die Strecke", "Driving Songs"), Def("Mitsing-Hits", "Alle zusammen", "Sing Along Hits"),
    )

    fun weekly(cal: java.util.Calendar = java.util.Calendar.getInstance()): List<Def> {
        val w = cal.get(java.util.Calendar.WEEK_OF_YEAR) + cal.get(java.util.Calendar.YEAR) * 53
        return (0 until 4).map { pool[(w * 4 + it) % pool.size] }
    }

    fun forNow(cal: java.util.Calendar = java.util.Calendar.getInstance()): List<Def> =
        (season(cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)) +
            moment(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.DAY_OF_WEEK))).distinctBy { it.query }

    val classics get() = curated.map { (n, q) -> Def(n, "Playlist", q) }

    /** Alle bekannten (für Namen beim Abspielen). */
    fun nameFor(query: String): String =
        (trending + forNow() + weekly() + classics + pool).firstOrNull { it.query == query }?.name ?: query

    // ---------- Gespeicherte Playlists ----------
    fun saved(c: Context): List<Def> = try {
        val arr = JSONArray(prefs(c).getString("saved", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Def(o.getString("name"), o.optString("sub"), o.getString("query")) } }
    } catch (_: Exception) { emptyList() }

    fun isSaved(c: Context, query: String) = saved(c).any { it.query == query }

    fun toggleSaved(c: Context, d: Def): Boolean {
        val list = saved(c).toMutableList()
        val now = list.none { it.query == d.query }
        if (now) list.add(0, d) else list.removeAll { it.query == d.query }
        prefs(c).edit().putString("saved", JSONArray().apply {
            list.forEach { put(JSONObject().put("name", it.name).put("sub", it.sub).put("query", it.query)) }
        }.toString()).apply()
        return now
    }

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
