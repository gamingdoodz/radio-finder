package de.gamingdoodz.radiofinder

import android.content.Context

/**
 * Wie viel später man einen Liedwechsel hört, als der Server ihn sieht (je Sender gemessen, nur auf dem Handy).
 * Der Server liest den Titel am "Live-Rand" des Streams, das Handy spielt ein paar Sekunden dahinter
 * (Puffer, Vorlauf des Senders). Mit diesem Versatz stimmt die Liedzeit auch beim Einschalten mitten im Lied.
 */
object Latency {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("latency", Context.MODE_PRIVATE)
    @Volatile private var avgCache: Long? = null

    /** Gemessen: selbst gehörter Liedanfang minus Anfang laut Server. Unplausible Werte werden verworfen. */
    fun record(c: Context, stationId: String, diffMs: Long) {
        if (diffMs !in -15_000L..45_000L) return
        val p = prefs(c)
        val old = p.getLong(stationId, Long.MIN_VALUE)
        val v = if (old == Long.MIN_VALUE) diffMs else (old * 2 + diffMs) / 3
        p.edit().putLong(stationId, v).apply()
        avgCache = null
    }

    /** Versatz für diesen Sender; unbekannt = Durchschnitt aller gemessenen Sender (ab 3), sonst 0. */
    fun of(c: Context, stationId: String): Long {
        val p = prefs(c)
        val v = p.getLong(stationId, Long.MIN_VALUE)
        if (v != Long.MIN_VALUE) return v
        return avgCache ?: run {
            val all = p.all.values.mapNotNull { it as? Long }
            (if (all.size >= 3) MixLogic.median(all) else 0L).also { avgCache = it }
        }
    }
}
