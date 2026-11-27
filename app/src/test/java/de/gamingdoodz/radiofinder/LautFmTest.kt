package de.gamingdoodz.radiofinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Antwort im Format von api.laut.fm/station/<name>/current_song. */
class LautFmTest {
    @Test fun senderNameAusAdresse() {
        assertEquals("eins-a-hits", LautFm.stationName("https://stream.laut.fm/eins-a-hits"))
        assertEquals("weihnachtsradio", LautFm.stationName("http://stream.laut.fm/weihnachtsradio?ref=radiobrowser"))
        assertEquals("rock_classics", LautFm.stationName("https://laut.fm/rock_classics"))
        assertNull(LautFm.stationName("https://streams.rockantenne.de/rockantenne/stream/mp3"))
    }

    @Test fun liedzeitLesen() {
        val json = """{"type":"song","title":"Last Christmas","artist":{"name":"Wham!"},
            "started_at":"2026-11-27 14:01:12 +0100","ends_at":"2026-11-27 14:05:39 +0100"}"""
        val s = LautFm.parse(json)
        assertNotNull(s)
        assertEquals("Wham! - Last Christmas", s!!.title)
        assertEquals(267_000L, s.durMs)
        assertEquals(LautFm.parseTime("2026-11-27 13:01:12 +0000"), s.startMs)
    }

    @Test fun kaputteAntwort() {
        assertNull(LautFm.parse("nicht json"))
        assertNull(LautFm.parse("""{"title":"x"}"""))
    }
}
