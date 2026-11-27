package de.gamingdoodz.radiofinder

import android.content.Context
import android.media.audiofx.Equalizer

/** Klang-Voreinstellungen über den Equalizer des Handys (läuft ganz auf dem Gerät, kein Server). */
object Klang {
    val options = listOf(
        "off" to ("Aus" to "Unveränderter Klang"),
        "bass" to ("Mehr Bass" to "Kräftiger untenrum"),
        "voice" to ("Klare Stimmen" to "Für Hörspiele, Podcasts und Nachrichten"),
        "car" to ("Auto" to "Gegen Fahrgeräusche: etwas mehr Bass und Höhen"),
        "treble" to ("Mehr Höhen" to "Heller, gut für kleine Lautsprecher"),
    )

    fun label(c: Context): String = options.firstOrNull { it.first == Settings.klang(c) }?.second?.first ?: "Aus"

    /** Verstärkung in dB je Frequenz (Hz). Höchstens +5 dB, damit nichts übersteuert. */
    private fun gain(preset: String, hz: Double): Double = when (preset) {
        "bass" -> when { hz < 120 -> 5.0; hz < 300 -> 3.0; hz < 600 -> 1.0; else -> 0.0 }
        "voice" -> when { hz < 150 -> -3.0; hz < 400 -> -1.0; hz < 1000 -> 1.0; hz < 4000 -> 3.0; hz < 8000 -> 1.5; else -> 0.0 }
        "car" -> when { hz < 150 -> 4.0; hz < 400 -> 1.5; hz < 2000 -> 0.0; hz < 6000 -> 2.0; else -> 3.0 }
        "treble" -> when { hz < 2000 -> 0.0; hz < 6000 -> 2.5; else -> 4.0 }
        else -> 0.0
    }

    /** Equalizer für die Wiedergabe anlegen; null = aus oder vom Handy nicht unterstützt. */
    fun create(c: Context, sessionId: Int): Equalizer? {
        val preset = Settings.klang(c)
        if (preset == "off" || sessionId <= 0) return null
        return try {
            val eq = Equalizer(0, sessionId)
            val range = eq.bandLevelRange
            for (b in 0 until eq.numberOfBands) {
                val band = b.toShort()
                val hz = eq.getCenterFreq(band) / 1000.0
                val mb = (gain(preset, hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band, mb.toShort())
            }
            eq.enabled = true
            eq
        } catch (_: Exception) {
            null
        }
    }
}
