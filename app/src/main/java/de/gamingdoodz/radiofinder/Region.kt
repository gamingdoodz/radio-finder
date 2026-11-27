package de.gamingdoodz.radiofinder

import android.content.Context

/** Sender nur aus bestimmten Ländern (z. B. keine spanische Werbung). */
object Region {
    private val dach = setOf("DE", "AT", "CH", "LI", "LU")
    private val english = setOf("GB", "US", "IE", "CA", "AU", "NZ")
    private val europe = dach + setOf("GB", "IE", "NL", "BE", "FR", "IT", "ES", "PT", "DK", "SE", "NO", "FI", "PL", "CZ",
        "SK", "HU", "SI", "HR", "RO", "BG", "GR", "EE", "LV", "LT", "IS", "MT", "CY", "RS", "BA", "ME", "MK", "AL")

    val options = listOf(
        "dach" to ("Deutschland, Österreich, Schweiz" to "Nur deutschsprachige Sender (Standard)"),
        "dach_en" to ("Deutschsprachig + Englisch" to "Dazu Sender aus UK, USA, Irland, Kanada, Australien"),
        "eu" to ("Europa" to "Alle europäischen Sender"),
        "all" to ("Weltweit" to "Alle ~50.000 Sender"),
    )

    @Volatile private var current = "dach"

    fun load(c: Context) { current = Settings.region(c) }

    fun label(c: Context) = when (Settings.region(c)) { "dach" -> "DACH"; "eu" -> "Europa"; "dach_en" -> "DACH + EN"; "all" -> "Weltweit"; else -> "DACH" }

    /** cc = Länderkürzel vom Server; null = unbekannt (alter Server), dann erlauben. */
    fun allows(cc: String?): Boolean {
        if (cc == null) return true
        val c = cc.uppercase()
        return when (current) {
            "dach" -> c in dach
            "eu" -> c in europe
            "all" -> true
            else -> c in dach || c in english
        }
    }
}
