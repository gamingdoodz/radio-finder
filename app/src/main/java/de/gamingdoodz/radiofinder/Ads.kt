package de.gamingdoodz.radiofinder

import android.content.Context

/**
 * Werbung am Anfang von Radiosendern (Pre-Roll) erkennen.
 * Überspringen geht nicht – aber die App merkt sich je Sender, wie lange sie dauert,
 * zeigt einen Countdown und der Hit-Mix bevorzugt Sender ohne Vorab-Werbung.
 */
object Ads {
    private val adWords = Regex("(?i)werbung|advert|commercial|pre-?roll|ad ?break|sponsor|anzeige|adswizz|triton|spot ?block|commercials")

    fun isAd(title: String?): Boolean = title != null && adWords.containsMatchIn(title)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ads", Context.MODE_PRIVATE)

    /** Gemessene Dauer der Vorab-Werbung in ms (0 = keine bekannt). */
    fun duration(c: Context, stationId: String): Long = prefs(c).getLong(stationId, 0L)

    fun record(c: Context, stationId: String, ms: Long) {
        val clamped = ms.coerceIn(5_000L, 180_000L)
        val old = duration(c, stationId)
        val avg = if (old == 0L) clamped else (old + clamped) / 2
        prefs(c).edit().putLong(stationId, avg).apply()
    }

    fun clear(c: Context, stationId: String) = prefs(c).edit().remove(stationId).apply()

    /** Merken, dass dieser Sender im Player Liedtitel mitschickt (nur dann heißt "kein Titel" am Anfang wohl Werbung). */
    fun markIcy(c: Context, stationId: String) {
        if (!hasIcy(c, stationId)) prefs(c).edit().putBoolean("icy:$stationId", true).apply()
    }

    fun hasIcy(c: Context, stationId: String) = prefs(c).getBoolean("icy:$stationId", false)

    /** Streams großer Werbe-Vermarkter spielen beim Verbinden fast immer erst Werbung. */
    private val adHosts = Regex("(?i)streamtheworld|tritondigital|adswizz|revma|ihrhls|spotxchange|liveadexchanger|deliver\\.adswizz|playerservices|ais-sa|ais-edge|audioad|adtonos|targetspot")

    /** Kommt hier beim Umschalten wahrscheinlich Werbung? (gelernt oder bekannter Werbe-Stream) */
    fun likely(c: Context, stationId: String, url: String): Boolean =
        duration(c, stationId) > 0 || adHosts.containsMatchIn(url)
}
