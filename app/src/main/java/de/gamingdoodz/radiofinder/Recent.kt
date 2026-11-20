package de.gamingdoodz.radiofinder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Zuletzt gehört (höchstens 30 Einträge). */
object Recent {
    private const val KEY = "list"
    private const val MAX = 30

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun all(context: Context): List<Entry> = try {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(context: Context, entry: Entry) {
        if (entry.preview) return
        val list = all(context).filter { it.id != entry.id }.toMutableList()
        list.add(0, entry)
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
