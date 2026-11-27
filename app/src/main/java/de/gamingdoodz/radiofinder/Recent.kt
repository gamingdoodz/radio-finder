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
            .distinctBy { if (it.kind == Kind.STATION) "st:" + MixLogic.stationBaseName(it.name) else it.id }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(context: Context, entry: Entry) {
        if (entry.preview) return
        // Derselbe Sender in anderer Qualität (z. B. "FLAC" und "128k AAC") nur einmal
        val base = if (entry.kind == Kind.STATION) MixLogic.stationBaseName(entry.name) else null
        val list = all(context).filter { it.id != entry.id && (base == null || it.kind != Kind.STATION || MixLogic.stationBaseName(it.name) != base) }.toMutableList()
        list.add(0, entry)
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
