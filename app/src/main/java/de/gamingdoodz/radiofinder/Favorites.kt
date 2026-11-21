package de.gamingdoodz.radiofinder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Favoriten (Sender und freie Lieder), gespeichert auf dem Handy. */
object Favorites {
    private const val KEY = "list"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(context: Context): List<Entry> = try {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun isFav(context: Context, id: String) = all(context).any { it.id == id }

    /** Schaltet um und gibt zurück, ob es jetzt Favorit ist. */
    fun toggle(context: Context, entry: Entry): Boolean {
        val list = all(context).toMutableList()
        val nowFav = list.none { it.id == entry.id }
        if (nowFav) list.add(0, entry) else list.removeAll { it.id == entry.id }
        if (nowFav && entry.kind == Kind.TRACK) Radar.event("fav", "${entry.subtitle} - ${entry.title}")
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        return nowFav
    }
}
