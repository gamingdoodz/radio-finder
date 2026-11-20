package de.gamingdoodz.radiofinder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Vorschläge passend zu Tageszeit und Jahreszeit. */
object Moments {
    enum class Action { HITMIX, KIDS_STORIES, ADULT_STORIES }

    data class Moment(
        val key: String,
        val title: String,
        val sub: String,
        val action: Action,
        val genreKey: String?, // für HITMIX (null = Deutsche Charts)
        val color1: String,
        val color2: String,
        val icon: Int,
        val storyIndex: Int = 0,
    )

    fun greeting(cal: Calendar = Calendar.getInstance()): String = when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> "Guten Morgen"
        in 11..13 -> "Mahlzeit"
        in 14..17 -> "Schönen Nachmittag"
        in 18..21 -> "Guten Abend"
        else -> "Gute Nacht"
    }

    fun now(cal: Calendar = Calendar.getInstance()): List<Moment> {
        val out = mutableListOf<Moment>()
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY)

        // Jahreszeit
        when {
            (m == 11 && d >= 15) || (m == 12 && d <= 26) ->
                out += Moment("xmas", "Weihnachts-Hits", "Last Christmas & Co. – nur Weihnachtslieder", Action.HITMIX, "xmas", "#FFEF4444", "#FF15803D", R.drawable.ic_star)
            (m == 12 && d >= 27) || (m == 1 && d <= 2) ->
                out += Moment("nye", "Silvester-Party", "Party-Hits fürs neue Jahr", Action.HITMIX, "party", "#FFD946EF", "#FF86198F", R.drawable.ic_bolt)
            m == 1 || m == 2 ->
                out += Moment("apres", "Après-Ski & Fasching", "Hütten- und Party-Hits", Action.HITMIX, "party", "#FF38BDF8", "#FF1E3A8A", R.drawable.ic_bolt)
            m in 6..8 ->
                out += Moment("summer", "Sommerhits", "Gute Laune für warme Tage", Action.HITMIX, "summer", "#FFFACC15", "#FFEA580C", R.drawable.ic_sun)
            m == 10 && d >= 15 ->
                out += Moment("halloween", "Halloween-Grusel", "Gruselgeschichten zum Hören", Action.ADULT_STORIES, null, "#FFF97316", "#FF431407", R.drawable.ic_book, storyIndex = 2)
        }

        // Tageszeit
        out += when (h) {
            in 5..10 -> Moment("morning", "Guten-Morgen-Mix", "Gute-Laune-Hits zum Wachwerden", Action.HITMIX, "pop", "#FFFBBF24", "#FFEA580C", R.drawable.ic_sun)
            in 11..13 -> Moment("noon", "Mittags-Charts", "Die aktuellen Hits für die Pause", Action.HITMIX, null, "#FF2EE6A8", "#FF0E7490", R.drawable.ic_star)
            in 14..17 -> Moment("afternoon", "Nachmittags-Power", "Dance & Party gegen das Tief", Action.HITMIX, "dance", "#FF818CF8", "#FF3730A3", R.drawable.ic_eq)
            in 18..21 -> Moment("evening", "Feierabend-Klassiker", "Die größten Hits der 2000er", Action.HITMIX, "2000s", "#FFA855F7", "#FF581C87", R.drawable.ic_album)
            else -> Moment("night", "Chill in die Nacht", "Ruhige Musik zum Runterkommen", Action.HITMIX, "chill", "#FF06B6D4", "#FF0F2742", R.drawable.ic_sun)
        }
        // Für die Kinder am Abend
        if (h in 17..20) {
            out += Moment("bedtime", "Gute-Nacht-Geschichten", "Zum Einschlafen für die Kleinen", Action.KIDS_STORIES, null, "#FF6366F1", "#FF1E1B4B", R.drawable.ic_book)
        } else if (h in 7..19) {
            out += Moment("kidsmix", "Kinder-Hit-Mix", "Bekannte Kinderlieder aus dem Radio", Action.HITMIX, "kids", "#FF22C55E", "#FF166534", R.drawable.ic_kids)
        }
        // Immer dabei
        if (out.none { it.genreKey == null && it.action == Action.HITMIX }) {
            out += Moment("charts", "Charts-Mix", "Nur aktuelle Hits – springt automatisch weiter", Action.HITMIX, null, "#FF2EE6A8", "#FF0E7490", R.drawable.ic_star)
        }
        return out
    }
}

/** Lieder, die beim Radiohören liefen (höchstens 100). */
object Heard {
    data class Song(val title: String, val station: String, val time: Long)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("heard", Context.MODE_PRIVATE)
    fun prefsObj(context: Context) = prefs(context)

    fun all(context: Context): List<Song> = try {
        val arr = JSONArray(prefs(context).getString("list", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Song(o.getString("t"), o.optString("s"), o.optLong("at")) } }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(context: Context, title: String, station: String) {
        val list = all(context)
        if (list.firstOrNull()?.title == title) return
        val arr = JSONArray()
        (listOf(Song(title, station, System.currentTimeMillis())) + list.filter { it.title != title }).take(100).forEach {
            arr.put(JSONObject().put("t", it.title).put("s", it.station).put("at", it.time))
        }
        prefs(context).edit().putString("list", arr.toString()).apply()
    }
}
