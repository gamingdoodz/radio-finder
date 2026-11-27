package de.gamingdoodz.radiofinder

import android.content.Context

/** Daumen runter: Lieder und Künstler, die im Hit-Mix nicht mehr kommen sollen (nur auf dem Handy gespeichert). */
object Dislikes {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("dislikes", Context.MODE_PRIVATE)

    @Volatile private var cache: Pair<Set<String>, Set<String>>? = null

    fun songs(c: Context): Set<String> = load(c).first
    fun artists(c: Context): Set<String> = load(c).second

    private fun load(c: Context): Pair<Set<String>, Set<String>> = cache ?: run {
        val p = prefs(c)
        (p.getStringSet("songs", emptySet()).orEmpty().toSet() to p.getStringSet("artists", emptySet()).orEmpty().toSet()).also { cache = it }
    }

    fun addSong(c: Context, title: String) = add(c, "songs", MixLogic.songKey(title))
    fun addArtist(c: Context, title: String) = add(c, "artists", MixLogic.artistOf(title))

    private fun add(c: Context, key: String, v: String) {
        if (v.isBlank()) return
        val p = prefs(c)
        // Höchstens 300 Einträge je Liste
        val set = (p.getStringSet(key, emptySet()).orEmpty() + v).toList().takeLast(300).toSet()
        p.edit().putStringSet(key, set).apply()
        cache = null
    }

    fun clear(c: Context) { prefs(c).edit().clear().apply(); cache = null }

    fun count(c: Context) = songs(c).size + artists(c).size

    fun isDisliked(c: Context, title: String) = MixLogic.disliked(title, songs(c), artists(c))
}
