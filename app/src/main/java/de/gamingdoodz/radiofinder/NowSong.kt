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

    fun tune(id: String?) { stationId = id; title = null; startMs = 0L; durMs = 0L; tunedAt = System.currentTimeMillis(); localAt = 0L }

    fun elapsed(): Long = if (startMs > 0) (System.currentTimeMillis() - startMs).coerceAtLeast(0) else 0L

    /** Lied läuft laut Länge schon länger als es dauert (Sender hat den Titel nicht gewechselt). */
    fun overrun(): Boolean = durMs > 0 && startMs > 0 && elapsed() > durMs + 4_000

    fun known() = durMs > 0 && startMs > 0

    fun sinceTune(): Long = if (tunedAt > 0) System.currentTimeMillis() - tunedAt else 0L
}
