package de.gamingdoodz.radiofinder

/**
 * Was gerade auf dem Sender läuft: seit wann und wie lange das Lied dauert.
 * Daraus entstehen Laufleiste, Restzeit und "Lied ist vorbei" – auch wenn der Sender den Titel nicht wechselt.
 */
object NowSong {
    @Volatile var stationId: String? = null
    @Volatile var title: String? = null      // Liedtitel aus dem Stream ("Künstler - Titel")
    @Volatile var startMs = 0L               // Uhrzeit, zu der das Lied angefangen hat
    @Volatile var durMs = 0L                 // Länge laut Deezer (0 = unbekannt)
    @Volatile var tunedAt = 0L               // Uhrzeit, zu der der Sender eingeschaltet wurde
    @Volatile var localAt = 0L               // zuletzt einen Titel direkt aus dem Stream bekommen
    /** Liedanfang selbst beim Hören gesehen (Titelwechsel im Stream, sekundengenau), nicht nur vom Server geschätzt. */
    @Volatile var startLocal = false

    /** Zuletzt bekannter Liedanfang je Sender: Zurück zum Sender zeigt die echte Liedzeit statt bei 0 anzufangen. */
    private class Known(val title: String, val startMs: Long, val durMs: Long, val local: Boolean)
    private val known = object : LinkedHashMap<String, Known>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Known>?) = size > 30
    }

    fun tune(id: String?) {
        // Beim Wegschalten merken, was dort lief und seit wann
        val old = stationId; val t = title
        if (old != null && t != null && startMs > 0) synchronized(known) { known[old] = Known(t, startMs, durMs, startLocal) }
        stationId = id; title = null; startMs = 0L; durMs = 0L; tunedAt = System.currentTimeMillis(); localAt = 0L; startLocal = false
    }

    /** Läuft auf diesem Sender noch dasselbe Lied wie beim letzten Mal? Dann Anfang (und Länge) wiederherstellen. */
    fun recall(id: String, songTitle: String): Boolean {
        val k = synchronized(known) { known[id] } ?: return false
        if (k.title != songTitle) return false
        val age = System.currentTimeMillis() - k.startMs
        if (age < 0 || age > 12 * 60_000L || (k.durMs > 0 && age > k.durMs + 60_000L)) return false
        startMs = k.startMs; startLocal = k.local
        if (durMs == 0L) durMs = k.durMs
        return true
    }

    fun elapsed(): Long = if (startMs > 0) (System.currentTimeMillis() - startMs).coerceAtLeast(0) else 0L

    /** Lied läuft laut Länge schon länger als es dauert (Sender hat den Titel nicht gewechselt). */
    fun overrun(): Boolean = durMs > 0 && startMs > 0 && elapsed() > durMs + 4_000

    fun known() = durMs > 0 && startMs > 0

    fun sinceTune(): Long = if (tunedAt > 0) System.currentTimeMillis() - tunedAt else 0L
}
