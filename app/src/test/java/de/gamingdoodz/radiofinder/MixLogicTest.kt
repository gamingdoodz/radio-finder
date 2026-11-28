package de.gamingdoodz.radiofinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixLogicTest {

    // ---------- Suche: Musikrichtung erkennen ----------

    @Test fun rockSucheIstRichtung() {
        for (q in listOf("Rock", "rock", "Rockmusik", "Rock Musik", "hard rock", "Hardrock", "Deutschrock", "Punk", "Metal",
            "Heavy Metal", "Alternative", "Indie", "Rock n Roll", "Rock Hits", "beste Rock Songs", "Rock Radio")) {
            assertEquals(q, "rock", MixLogic.genreKeyFor(q))
        }
    }

    @Test fun liedOderKuenstlerMitRockBleibtLiedsuche() {
        for (q in listOf("Rock DJ", "Kid Rock", "Metallica", "Daft Punk", "Rocky", "We Will Rock You", "Rock Antenne", "Linkin Park")) {
            assertNull(q, MixLogic.genreKeyFor(q))
        }
    }

    @Test fun andereRichtungen() {
        val cases = mapOf(
            "Pop" to "pop", "Deutschpop" to "pop", "Hip-Hop" to "rap", "hiphop" to "rap", "Rap" to "rap", "Deutschrap" to "deutschrap",
            "Techno" to "dance", "House" to "dance", "Elektro" to "dance", "EDM" to "dance", "Schlager" to "schlager",
            "Volksmusik" to "schlager", "Ballermann" to "party", "80er" to "80s", "Songs aus den 80ern" to "80s",
            "90er Hits" to "90s", "2000er" to "2000s", "Oldies" to "oldies", "70er" to "oldies", "Classic Rock" to "rockclassics",
            "Rock Klassiker" to "rockclassics", "Jazz" to "jazz", "Soul" to "jazz", "Blues" to "jazz", "Klassik" to "classic",
            "Klassische Musik" to "classic", "Reggaeton" to "latin", "Latin" to "latin", "Country" to "country",
            "Chill" to "chill", "Lounge" to "chill", "Entspannung" to "chill", "Weihnachtslieder" to "xmas", "Christmas" to "xmas",
            "Kinderlieder" to "kids", "Kinder" to "kids", "Charts" to "charts", "Top 40" to "charts", "Hits" to "charts",
            "Sommerhits" to "summer",
        )
        for ((q, k) in cases) assertEquals(q, k, MixLogic.genreKeyFor(q))
    }

    @Test fun normaleLiedsuchenSindKeineRichtung() {
        for (q in listOf("Helene Fischer", "Atemlos", "Shape of You", "Ed Sheeran", "Blinding Lights", "Popcorn", "Housemartins",
            "Soulja Boy", "Funkytown", "", "   ")) {
            assertNull(q, MixLogic.genreKeyFor(q))
        }
    }

    // ---------- Sender: passt er wirklich zu Rock? (Tags wie bei radio-browser.info) ----------

    private data class St(val name: String, val tags: String)

    private val rockStations = listOf(
        St("ROCK ANTENNE", "rock,classic rock,hard rock,alternative rock,heavy metal"),
        St("RADIO BOB!", "rock,classic rock,alternative,hard rock"),
        St("Radio BOB! BOBs Metal", "metal,heavy metal,rock"),
        St("Rockland Radio", ""),
        St("FFN Rock", "rock"),
        St("Punk Rock Radio", "punk,punk rock"),
        St("egoFM", "indie,alternative,rock"),
        St("Planet Rock", "classic rock,rock,uk"),
    )

    private val notRock = listOf(
        St("ANTENNE BAYERN", "pop,hits,charts,top 40"),
        St("1LIVE", "pop,hits,charts,news"),
        St("SWR3", "pop,rock,news,hits"),
        St("bigFM", "hip hop,dance,pop,charts"),
        St("Schlager Radio", "schlager,volksmusik"),
        St("Radio Paloma", "schlager,deutsch,oldies"),
        St("Deutschlandfunk", "news,talk,information"),
        St("Klassik Radio", "classical,klassik"),
        St("RPR1.", "pop,rock,80s,90s,hits"),
    )

    @Test fun rockSenderPassen() {
        for (s in rockStations) assertTrue("${s.name} sollte zu Rock passen", MixLogic.stationFits("rock", s.tags, s.name))
    }

    @Test fun hitSenderMitRockTagPassenNicht() {
        for (s in notRock) assertFalse("${s.name} sollte nicht zu Rock passen", MixLogic.stationFits("rock", s.tags, s.name))
    }

    @Test fun reineRocksenderVorHitSendern() {
        val rockAntenne = MixLogic.stationScore("rock", "rock,classic rock,hard rock", "ROCK ANTENNE")
        val swr3 = MixLogic.stationScore("rock", "pop,rock,news,hits", "SWR3")
        assertTrue(rockAntenne > swr3)
    }

    @Test fun schlagerUndKlassik() {
        assertTrue(MixLogic.stationFits("schlager", "schlager,volksmusik", "Schlager Radio"))
        assertFalse(MixLogic.stationFits("schlager", "rock,metal", "Radio BOB!"))
        assertTrue(MixLogic.stationFits("classic", "classical,klassik", "Klassik Radio"))
        assertFalse(MixLogic.stationFits("classic", "pop,hits,charts", "1LIVE"))
        assertTrue(MixLogic.stationFits("rap", "hip hop,rap,rnb", "bigFM Hip-Hop"))
        assertFalse(MixLogic.stationFits("rap", "schlager", "Radio Paloma"))
    }

    @Test fun ohneRegelOderTagsNeutral() {
        assertTrue(MixLogic.stationFits("xmas", "", "Weihnachtsradio"))
        assertTrue(MixLogic.stationFits("rock", "", "Irgendein Sender"))   // keine Tags bekannt: nicht aussortieren
    }

    // ---------- Mix: offene und feste Richtungen ----------

    @Test fun nurOffeneMixeSpielenIrgendeineMusik() {
        assertFalse(MixLogic.focused(null))
        assertFalse(MixLogic.focused("charts"))
        assertFalse(MixLogic.focused("mine"))
        assertFalse(MixLogic.focused("favs"))
        assertTrue(MixLogic.focused("rock"))
        assertTrue(MixLogic.focused("schlager"))
        assertTrue(MixLogic.focused("artist:123:Queen"))
        assertTrue(MixLogic.focused("pl:Rock Klassiker"))
        assertTrue(MixLogic.focused("similar:Queen - Bohemian Rhapsody"))
    }

    @Test fun chartsNurBeiPopUndCharts() {
        assertTrue(MixLogic.chartsBelongTo(null))
        assertTrue(MixLogic.chartsBelongTo("charts"))
        assertTrue(MixLogic.chartsBelongTo("pop"))
        assertFalse(MixLogic.chartsBelongTo("rock"))
        assertFalse(MixLogic.chartsBelongTo("jazz"))
    }

    // ---------- Lieder vergleichen (echte Radiotitel) ----------

    private fun n(s: String) = MixLogic.normalize(s)

    @Test fun gleichesLiedAndersGeschrieben() {
        assertTrue(MixLogic.sameSong(n("Ed Sheeran - Shape Of You"), n("ED SHEERAN - SHAPE OF YOU (RADIO EDIT)")))
        assertTrue(MixLogic.sameSong(n("David Guetta feat. Sia - Titanium"), n("David Guetta - Titanium (feat. Sia)")))
        assertTrue(MixLogic.sameSong(n("Die Ärzte - Schrei nach Liebe"), n("Die Aerzte - Schrei nach Liebe")) ||
            MixLogic.sameSong(n("Die Ärzte - Schrei nach Liebe"), n("Die Arzte - Schrei nach Liebe")))
        assertTrue(MixLogic.sameSong(n("Queen - Bohemian Rhapsody"), n("Bohemian Rhapsody - Queen")))
        assertTrue(MixLogic.sameSong(n("Adele - Hello"), n("ADELE - Hello (Radio Edit)")))
    }

    @Test fun andereLiederSindNichtGleich() {
        assertFalse(MixLogic.sameSong(n("Die Ärzte - Schrei nach Liebe"), n("Die Toten Hosen - Tage wie diese")))
        assertFalse(MixLogic.sameSong(n("Queen - Bohemian Rhapsody"), n("Queen - Radio Ga Ga")))
        assertFalse(MixLogic.sameSong(n("Ed Sheeran - Perfect"), n("ROCK ANTENNE - Wir rocken Bayern")))
        assertFalse(MixLogic.sameSong(n("Adele - Hello"), null))
        // Künstler mit langem Namen: verschiedene Lieder bleiben verschieden
        assertFalse(MixLogic.sameSong(n("Red Hot Chili Peppers - Californication"), n("Red Hot Chili Peppers - Otherside")))
        assertFalse(MixLogic.sameSong(n("Earth, Wind & Fire - September"), n("Earth, Wind & Fire - Boogie Wonderland")))
        // Sender schickt nur den Künstler: das ist nicht jedes Lied von ihm
        assertFalse(MixLogic.sameSong(n("Ed Sheeran"), n("Ed Sheeran - Perfect")))
        // Zusätze bleiben dasselbe Lied
        assertTrue(MixLogic.sameSong(n("Calvin Harris - Summer"), n("Calvin Harris feat. Someone - Summer")))
    }

    @Test fun normalisieren() {
        assertEquals("die arzte schrei nach liebe", n("Die Ärzte – Schrei nach Liebe!"))
        assertEquals("beyonce halo", n("Beyoncé - Halo"))
    }

    @Test fun ganzeWoerter() {
        assertTrue(MixLogic.hasWord("ed sheeran shape of you", "you"))
        assertFalse(MixLogic.hasWord("youngblood 5 seconds of summer", "you"))
        assertTrue(MixLogic.hasWord("lovesong the cure", "love"))
        assertFalse(MixLogic.hasWord("glove song", "love"))
        assertFalse(MixLogic.hasWord("superstar", "up"))
    }

    @Test fun senderNameAlsKuenstlerIstJingle() {
        assertTrue(MixLogic.isStationJingle("ROCK ANTENNE - Wir rocken Bayern", "ROCK ANTENNE"))
        assertTrue(MixLogic.isStationJingle("RADIO BOB! - Live", "RADIO BOB!"))
        assertTrue(MixLogic.isStationJingle("bigFM - Deutschlands biggest Beats", "bigFM"))
        assertFalse(MixLogic.isStationJingle("Bob Marley - Jamming", "RADIO BOB!"))
        assertFalse(MixLogic.isStationJingle("Queen - Radio Ga Ga", "Radio Gaga"))
        assertFalse(MixLogic.isStationJingle("Adele - Hello", null))
        assertFalse(MixLogic.isStationJingle("ROCK ANTENNE", "ROCK ANTENNE"))   // ohne Strich: entscheidet isMusic
    }

    @Test fun genreNamenDerAppWerdenErkannt() {
        // Namen der Genre-Kacheln (Suchvorschläge tippen genau diese ein)
        val names = mapOf("Weihnachten" to "xmas", "Charts" to "charts", "Pop & Deutsch-Pop" to "pop", "Rap & Hip-Hop" to "rap",
            "Dance & Elektro" to "dance", "Rock & Alternative" to "rock", "Schlager" to "schlager", "Party & Après-Ski" to "party",
            "80er" to "80s", "90er" to "90s", "2000er" to "2000s", "Sommerhits" to "summer", "Deutschrap" to "deutschrap",
            "Rock-Klassiker" to "rockclassics", "Oldies" to "oldies", "Latin & Reggaeton" to "latin", "Country" to "country",
            "Jazz & Soul" to "jazz", "Klassik" to "classic", "Chill & Entspannen" to "chill", "Kindermusik" to "kids")
        for ((n, k) in names) assertEquals(n, k, MixLogic.genreKeyFor(n))
    }

    @Test fun nurNummernSindKeinLied() {
        assertFalse(MixLogic.isMusicTitle("9999999 - 9999999"))
        assertFalse(MixLogic.isMusicTitle("6700483 - 6700483"))
        assertTrue(MixLogic.isMusicTitle("Blink-182 - All The Small Things"))
        assertTrue(MixLogic.isMusicTitle("UB40 - Red Red Wine"))
    }

    @Test fun sendernameStattLied() {
        // Echte Titel vom Server (27.11.): das sind Sender/Programme, keine Lieder
        val names = listOf("RADIO BOB! Classic Rock", "Sunshine Live - Techno Live", "Rockland Radio - Linz / Remagen", "FluxFM - FluxLounge",
            "TOGGO Radio Party", "Radio Teddy - Gute Nacht Musik", "Radio21 Leer vf", "Best Of Rock.FM Soft Rock", "Queen FM", "Radiohead Radio")
        val brands = names.flatMap { MixLogic.stationBrands(it) }.toSet()
        for (t in listOf("RADIO BOB - Classic Rock", "SUNSHINE LIVE - Techno", "sunshine live - Drum n Bass", "Rockland Radio - Neuwied",
            "RADIO BOB - 80er Rock RWI Test B", "TOGGO Radio - Party", "Radio TEDDY - Gute Nacht Musik", "RADIO 21 - Leer", "RADIO BOB - Mittelalter Rock"))
            assertTrue(t, MixLogic.isProgramTitle(t, brands))
        for (t in listOf("Queen - Bohemian Rhapsody", "Radiohead - Creep", "Live - Lightning Crashes", "Soundgarden - Black Rain [Radio Edit]",
            "Bon Jovi - Runaway", "Kate Bush - Running up that hill", "The Rolling Stones - Paint It Black"))
            assertFalse(t, MixLogic.isProgramTitle(t, brands))
    }

    @Test fun senderRichtung() {
        // Weiter nach einem Rocksender: Richtung erkennen, damit kein Popsender folgt
        assertTrue(MixLogic.mainGenre("rock,classic rock", "ROCK ANTENNE") in setOf("rock", "rockclassics"))
        assertEquals(null, MixLogic.mainGenre("", "Radio Hamburg"))
        assertEquals("schlager", MixLogic.mainGenre("schlager,volksmusik", "Radio Schlagerparadies"))
    }

    @Test fun daumenRunter() {
        val songs = setOf(MixLogic.songKey("Wham! - Last Christmas"))
        val artists = setOf(MixLogic.artistOf("Mariah Carey - All I Want For Christmas Is You"))
        assertTrue(MixLogic.disliked("WHAM! - Last Christmas (Remastered)", songs, artists))
        assertTrue(MixLogic.disliked("Mariah Carey - Oh Santa!", songs, artists))
        assertTrue(MixLogic.disliked("Mariah Carey & Ariana Grande - Oh Santa!", songs, artists))
        assertFalse(MixLogic.disliked("Chris Rea - Driving Home For Christmas", songs, artists))
        assertFalse(MixLogic.disliked("Mariah Smith - Carey On", emptySet(), setOf("mariah carey")))
        assertFalse(MixLogic.disliked("Wham! - Last Christmas", emptySet(), emptySet()))
    }

    @Test fun fastVorbeiOhneBekanntenAnfang() {
        assertTrue(MixLogic.unknownStartAlmostOver(seenForMs = 200_000, durMs = 230_000))
        assertFalse(MixLogic.unknownStartAlmostOver(seenForMs = 20_000, durMs = 230_000))
        assertTrue(MixLogic.unknownStartAlmostOver(seenForMs = 200_000, durMs = 0))
        assertFalse(MixLogic.unknownStartAlmostOver(seenForMs = 60_000, durMs = 0))
    }

    @Test fun lautstaerkeAngleichen() {
        assertEquals(0f, MixLogic.loudnessGain(-16f), 0.01f)
        assertEquals(6f, MixLogic.loudnessGain(-22f), 0.01f)
        assertEquals(-4f, MixLogic.loudnessGain(-12f), 0.01f)
        assertEquals(8f, MixLogic.loudnessGain(-40f), 0.01f)      // höchstens +8 dB
        assertEquals(0f, MixLogic.loudnessGain(-90f), 0.01f)      // Stille: nichts verstärken
        val loud = FloatArray(1000) { if (it % 2 == 0) 0.5f else -0.5f }
        assertEquals(-6.02f, MixLogic.rmsDb(loud), 0.05f)
        assertEquals(-90f, MixLogic.rmsDb(FloatArray(100)), 0.01f)
    }

    @Test fun gleicherSenderAndereQualitaet() {
        assertEquals(MixLogic.stationBaseName("Radio Paradise Mellow Mix FLAC+meta"), MixLogic.stationBaseName("Radio Paradise Mellow Mix 128k AAC"))
        assertEquals("merry christmas radio", MixLogic.stationBaseName("Merry Christmas Radio (AAC)"))
        assertFalse(MixLogic.stationBaseName("Radio Paradise Main Mix") == MixLogic.stationBaseName("Radio Paradise Mellow Mix"))
        assertEquals("1live", MixLogic.stationBaseName("1LIVE"))
        assertEquals("wdr 2", MixLogic.stationBaseName("WDR 2"))
        assertFalse(MixLogic.stationBaseName("WDR 2") == MixLogic.stationBaseName("WDR 4"))
    }

    private fun q(s: String) = MixLogic.normalize(s).split(" ").filter { it.isNotBlank() }

    @Test fun sucheGanzeWoerterUndWortanfaenge() {
        val t = n("Helene Fischer - Atemlos durch die Nacht")
        assertTrue(MixLogic.searchMatches(t, q("helene fischer")))
        assertTrue(MixLogic.searchMatches(t, q("helen")))              // beim Tippen
        assertTrue(MixLogic.searchMatches(t, q("atemlos")))
        assertTrue(MixLogic.searchMatches(t, q("Fischer atemlos")))   // Reihenfolge egal
        // Nicht mitten im Wort: "up" ist nicht "superstar", "nacht" nicht "Weihnachten"
        assertFalse(MixLogic.searchMatches(n("Jessie J - Superstar"), q("up")))
        assertFalse(MixLogic.searchMatches(n("Wham! - Last Christmas (Weihnachten)"), q("nacht")))
        // Zusammengeschrieben
        assertTrue(MixLogic.searchMatches(n("AC/DC - Highway to Hell"), q("acdc")))
        assertTrue(MixLogic.searchMatches(n("AC/DC - Highway to Hell"), q("ac dc")))
    }

    @Test fun sucheMitTippfehlern() {
        val t = n("Helene Fischer - Atemlos durch die Nacht")
        assertFalse(MixLogic.searchMatches(t, q("helene fisher")))           // genau: nein
        assertTrue(MixLogic.searchMatches(t, q("helene fisher"), fuzzy = true))
        assertTrue(MixLogic.searchMatches(t, q("atemlo durch"), fuzzy = true))
        assertTrue(MixLogic.searchMatches(n("Ed Sheeran - Shape of You"), q("ed sheran"), fuzzy = true))
        assertTrue(MixLogic.searchMatches(n("Rammstein - Du hast"), q("ramstein"), fuzzy = true))
        // Kurze Wörter bleiben genau (sonst fände "rock" auch "rick")
        assertFalse(MixLogic.searchMatches(n("Rick Astley - Never Gonna Give You Up"), q("rock"), fuzzy = true))
        assertFalse(MixLogic.searchMatches(t, q("adele"), fuzzy = true))
    }

    @Test fun senderNamen() {
        assertTrue(MixLogic.stationNameMatches(n("ROCK ANTENNE"), q("rock antenne")))
        assertTrue(MixLogic.stationNameMatches(n("Rockantenne Heavy Metal"), q("antenne")))
        assertTrue(MixLogic.stationNameMatches(n("ANTENNE BAYERN"), q("antene bayern"), fuzzy = true))
        assertTrue(MixLogic.stationNameMatches(n("1LIVE"), q("1live")))
        assertFalse(MixLogic.stationNameMatches(n("Radio Hamburg"), q("bayern"), fuzzy = true))
    }

    @Test fun median() {
        assertEquals(0L, MixLogic.median(emptyList()))
        assertEquals(5L, MixLogic.median(listOf(9L, 1L, 5L)))
        assertEquals(4L, MixLogic.median(listOf(2L, 6L, 3L, 5L)))
    }
}
