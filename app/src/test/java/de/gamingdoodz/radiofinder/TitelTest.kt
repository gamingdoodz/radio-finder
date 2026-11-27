package de.gamingdoodz.radiofinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Titel, wie sie Sender wirklich im Stream schicken. */
class TitelTest {

    @Test fun liederSindMusik() {
        for (t in listOf("Ed Sheeran - Shape Of You", "AC/DC - Highway To Hell", "Die Ärzte – Schrei nach Liebe",
            "Helene Fischer - Atemlos durch die Nacht", "Linkin Park - In The End", "Queen - Don't Stop Me Now")) {
            assertTrue(t, MixLogic.isMusicTitle(t))
        }
    }

    @Test fun ansagenUndWerbungSindKeineMusik() {
        for (t in listOf("1LIVE - Nachrichten", "SWR3 - Verkehr und Wetter", "ANTENNE BAYERN - Gewinnspiel",
            "Werbung - Jetzt anrufen", "Radio XY - www.radioxy.de", "Hotline 0800 123 456 - Jetzt anrufen",
            "ROCK ANTENNE", "Live-Sendung", "Deutschlandfunk - Interview der Woche")) {
            assertFalse(t, MixLogic.isMusicTitle(t))
        }
    }

    @Test fun textSaeubern() {
        assertEquals("Don’t Stop", Text.clean("Don&#8217;t Stop"))
        assertEquals("Simon & Garfunkel - The Boxer", Text.clean("Simon &amp; Garfunkel - The Boxer"))
        assertEquals("Eminem Lose Yourself", Text.clean("Eminem_Lose_Yourself"))
        assertEquals("Queen - Bohemian Rhapsody", Text.clean("  Queen - Bohemian Rhapsody "))
    }
}
