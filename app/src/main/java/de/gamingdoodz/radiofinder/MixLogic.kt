package de.gamingdoodz.radiofinder

import java.text.Normalizer
import java.util.Locale

/**
 * Regeln für Suche und Hit-Mix ohne Android-Abhängigkeiten (in Unit-Tests prüfbar):
 * - welche Musikrichtung jemand sucht ("Rock", "Rockmusik", "hard rock", "Songs aus den 80ern"),
 * - ob ein Sender wirklich zu einer Richtung passt (Tags/Name, nicht nur irgendwo "rock" in den Tags),
 * - ob zwei Radiotitel dasselbe Lied sind.
 */
object MixLogic {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Kleinbuchstaben, ohne Akzente/Umlaut-Punkte, nur Wörter mit einem Leerzeichen dazwischen. */
    fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")
        return n.replace(NON_WORD, " ").trim()
    }

    // ---------------- Suche: welche Musikrichtung ist gemeint? ----------------

    /** Reihenfolge zählt: Speziellere Richtungen zuerst (Rock-Klassiker vor Rock, Deutschrap vor Rap). */
    private val rules: List<Pair<String, Regex>> = listOf(
        "xmas" to "weihnacht|christmas|xmas|\\badvent",
        "kids" to "kinderlied|kindermusik|kinder musik|kinderdisco|\\bkinder\\b|kids music|\\bkids\\b",
        "rockclassics" to "classic rock|rock ?klassiker|klassik ?rock|rock classics|classicrock|70er rock|80er rock",
        "80s" to "\\b80er|achtziger|\\b80s\\b|eighties",
        "90s" to "\\b90er|neunziger|\\b90s\\b|nineties",
        "2000s" to "\\b2000er|nuller|\\b2000s\\b|\\b00er",
        "oldies" to "oldie|\\b60er|\\b70er|sechziger|siebziger|\\b60s\\b|\\b70s\\b",
        "deutschrap" to "deutschrap|deutsch ?rap|german rap|deutscher rap",
        "rap" to "\\brap\\b|\\brapmusik|hip ?hop|\\brnb\\b|\\br n b\\b|\\btrap\\b|\\burban\\b",
        "schlager" to "schlager|volksmusik|discofox|volkstumlich",
        "party" to "\\bparty|ballermann|apres|mallorca|stimmungs",
        "rock" to "\\b\\w*rock(musik|n|s)?\\b|\\bpunk\\b|punkrock|grunge|\\bmetal\\b|heavy metal|\\balternative\\b|\\bindie\\b|\\bemo\\b",
        "dance" to "\\bdance|techno|\\bhouse\\b|\\bedm\\b|electro|elektro|electronic|\\btrance\\b|hardstyle|dubstep|drum (and|n) bass|\\bdnb\\b",
        "summer" to "sommerhit|summer hit|sommer hits|summer hits",
        "chill" to "chill|entspann|\\bruhig|lounge|ambient|\\brelax",
        "jazz" to "\\bjazz|\\bsoul\\b|\\bblues\\b|\\bfunk\\b|\\bswing\\b",
        "classic" to "klassik|klassisch|classical|orchester|\\boper\\b|sinfonie|symphon",
        "latin" to "\\blatin|reggaeton|\\bsalsa\\b|bachata",
        "country" to "\\bcountry\\b|\\bwestern\\b",
        "pop" to "\\bpop\\b|deutschpop|deutsch pop|popmusik|\\bkpop\\b|k pop",
        "charts" to "\\bcharts?\\b|top ?40|hitparade|aktuelle hits|\\bhits\\b",
    ).map { (k, rx) -> k to Regex(rx) }

    /** Wörter, die eine Suche nach einer Richtung nicht zu einer Liedsuche machen ("Rock Musik", "beste 80er Hits"). */
    private val filler = setOf(
        "songs", "song", "lieder", "lied", "musik", "music", "hits", "hit", "mix", "radio", "sender", "playlist",
        "aus", "den", "der", "die", "das", "und", "and", "n", "roll", "von", "mit", "nur", "gute", "guter", "beste", "besten",
        "best", "of", "neue", "neu", "alte", "alter", "top", "40", "aktuelle", "aktuell", "sachen", "irgendwas", "was",
        "hard", "heavy", "classic", "deutsch", "deutsche", "deutscher", "german", "spiel", "spiele", "play", "bitte", "mal",
        "jahre", "zum", "fur", "genre", "richtung", "stil", "ski", "hip", "hop", "r", "b", "drum", "bass", "k",
    )

    /** "Rock", "Rockmusik", "hard rock", "Songs aus den 80ern" → Schlüssel der Richtung. "Rock DJ", "Kid Rock" → null (Lied/Künstler). */
    fun genreKeyFor(query: String): String? {
        val t = normalize(query)
        if (t.isEmpty()) return null
        val words = t.split(" ")
        val key = rules.firstOrNull { (_, rx) -> rx.containsMatchIn(t) }?.first ?: return null
        // Jedes Wort muss eine Richtung oder ein Füllwort sein, sonst ist es ein Lied oder Künstler ("Kid Rock")
        val onlyGenre = words.all { w -> w in filler || rules.any { (_, rx) -> rx.containsMatchIn(w) } }
        return if (onlyGenre) key else null
    }

    // ---------------- Sender: passt er wirklich zur Richtung? ----------------

    private class StationRule(val good: Regex, val bad: Regex?)

    private val stationRules: Map<String, StationRule> = mapOf(
        "rock" to StationRule(Regex("rock|metal|punk|grunge|alternative|indie"), Regex("^(pop|top 40|top40|charts|hits|schlager|dance|hip hop|news|talk|80s|90s|oldies|adult contemporary|easy listening)$")),
        "rockclassics" to StationRule(Regex("classic rock|rock|70s|hard rock|blues rock"), Regex("^(pop|top 40|charts|hits|schlager|dance|hip hop|news|talk)$")),
        "pop" to StationRule(Regex("pop|hits|charts|top 40|top40|adult contemporary"), Regex("^(news|talk|klassik|classical|schlager|metal|jazz)$")),
        "charts" to StationRule(Regex("charts|top 40|top40|hits|pop"), Regex("^(news|talk|klassik|classical|metal)$")),
        "rap" to StationRule(Regex("hip ?hop|rap|rnb|r&b|urban|trap"), Regex("^(schlager|news|talk|classical|rock|80s|oldies)$")),
        "deutschrap" to StationRule(Regex("deutschrap|deutsch rap|german rap|hip ?hop|rap"), Regex("^(schlager|news|talk|classical|rock)$")),
        "dance" to StationRule(Regex("dance|electro|electronic|edm|house|techno|trance|club|remix"), Regex("^(schlager|news|talk|classical|rock|metal|jazz|oldies)$")),
        "schlager" to StationRule(Regex("schlager|volksmusik|discofox|deutsch"), Regex("^(rock|metal|hip hop|rap|dance|techno|news|talk)$")),
        "party" to StationRule(Regex("party|ballermann|apres|mallorca|stimmung|schlager"), Regex("^(news|talk|classical|jazz)$")),
        "80s" to StationRule(Regex("80s|80er|eighties"), Regex("^(news|talk)$")),
        "90s" to StationRule(Regex("90s|90er|nineties"), Regex("^(news|talk)$")),
        "2000s" to StationRule(Regex("2000s|2000er|00s"), Regex("^(news|talk)$")),
        "oldies" to StationRule(Regex("oldies|60s|70s|50s|60er|70er"), Regex("^(news|talk|hip hop|techno)$")),
        "chill" to StationRule(Regex("chill|lounge|ambient|relax|easy listening|smooth"), Regex("^(news|talk|metal|hip hop|techno|hardstyle)$")),
        "jazz" to StationRule(Regex("jazz|soul|blues|swing|funk"), Regex("^(news|talk|schlager|metal)$")),
        "classic" to StationRule(Regex("classical|klassik|klassische|orchestra|opera|oper|baroque"), Regex("^(news|talk|pop|rock|hits)$")),
        "latin" to StationRule(Regex("latin|reggaeton|salsa|bachata|cumbia|latino"), Regex("^(news|talk|schlager)$")),
        "country" to StationRule(Regex("country|western|americana|bluegrass"), Regex("^(news|talk|hip hop|techno)$")),
        "summer" to StationRule(Regex("summer|sommer|hits|party|dance|pop"), Regex("^(news|talk|classical)$")),
    )

    /**
     * Wie gut passt ein Sender zur Richtung? Name zählt am meisten ("ROCK ANTENNE"),
     * dann passende Tags. Tags wie "pop, hits, charts" neben "rock" ziehen ab (Hit-Sender, die auch mal Rock spielen).
     * Ohne Regel (Weihnachten, Kinder: eigene Listen) oder ohne Tags: neutral 2.
     */
    fun stationScore(genreKey: String, tags: String, name: String): Int {
        val rule = stationRules[genreKey] ?: return 2
        val tokens = tags.lowercase(Locale.ROOT).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val nameHit = rule.good.containsMatchIn(normalize(name))
        if (tokens.isEmpty()) return if (nameHit) 5 else 2
        val good = tokens.count { rule.good.containsMatchIn(it) }
        val bad = rule.bad?.let { b -> tokens.count { b.matches(it) } } ?: 0
        val firstGood = rule.good.containsMatchIn(tokens.first())
        return (if (nameHit) 3 else 0) + good.coerceAtMost(3) * 2 + (if (firstGood) 1 else 0) - bad * 2
    }

    /** Sender passt zur Richtung (mindestens ein passender Tag und nicht vor allem ein Hit-/Pop-Sender). */
    fun stationFits(genreKey: String, tags: String, name: String) = stationScore(genreKey, tags, name) >= 2

    /**
     * Mix mit klarer Richtung: dort darf nie "irgendeine Musik" kommen, nur Lieder der Liste oder
     * Musik auf passenden Sendern. Offen sind nur Charts, "Mein Mix", Lieblingssender und Meistgespielt.
     */
    fun focused(genreKey: String?): Boolean =
        genreKey != null && genreKey !in setOf("charts", "mine", "favs", "radiocharts", "pl:rising", "pl:radiocharts", "pl:community")

    /** Bei diesen Richtungen gehören die deutschen Top-Charts dazu, bei allen anderen nicht (sonst Pop im Rock-Mix). */
    fun chartsBelongTo(genreKey: String?): Boolean = genreKey == null || genreKey == "charts" || genreKey == "pop"

    // ---------------- Lieder vergleichen ----------------

    /**
     * Dasselbe Lied, nur anders geschrieben ("(Radio Edit)", "feat.", andere Reihenfolge)?
     * Beide Titel normalisiert. Mindestens 2 gemeinsame Wörter und 70 % der Wörter des kürzeren Titels.
     */
    fun sameSong(a: String, b: String?): Boolean {
        if (b == null) return false
        if (a == b) return true
        val wa = songWords(a)
        val wb = songWords(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (wa == wb) return true
        val common = wa.intersect(wb).size
        return common >= 2 && common >= 0.7 * minOf(wa.size, wb.size)
    }

    /** Zusätze, die nichts über das Lied sagen. */
    private val noise = setOf("radio", "edit", "version", "remastered", "remaster", "feat", "ft", "featuring", "mix", "original", "single", "album", "the", "der", "die", "das")

    private fun songWords(s: String) = s.split(" ").filter { it.length >= 2 && it !in noise }.toSet()

    /** Kommt das Wort als ganzes Wort vor? Kurze Wörter (bis 3 Buchstaben) nur ganz, sonst reicht der Wortanfang ("love" in "lovesong"). */
    fun hasWord(normalizedText: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var i = normalizedText.indexOf(word)
        while (i >= 0) {
            val startOk = i == 0 || normalizedText[i - 1] == ' '
            val end = i + word.length
            val endOk = end == normalizedText.length || normalizedText[end] == ' ' || word.length >= 4
            if (startOk && endOk) return true
            i = normalizedText.indexOf(word, i + 1)
        }
        return false
    }

    /**
     * Sender schickt seinen Namen als "Künstler" ("ROCK ANTENNE - Wir rocken Bayern", "RADIO BOB! - Live"):
     * das ist ein Jingle/Slogan, kein Lied. Nur wenn der Teil vor dem Strich genau der Sendername ist
     * (so bleibt "Bob Marley - Jamming" auf Radio Bob ein Lied).
     */
    fun isStationJingle(title: String, stationName: String?): Boolean {
        if (stationName.isNullOrBlank()) return false
        val artist = normalize(title.substringBefore(" - ").substringBefore(" – "))
        if (artist.isEmpty() || artist == normalize(title)) return false
        val name = normalize(stationName)
        if (name.length < 3) return false
        val core = name.split(" ").filter { it !in stationNoise }.joinToString(" ")
        return artist == name || (core.length >= 3 && artist == core)
    }

    private val stationNoise = setOf("radio", "fm", "live", "stream", "webradio", "online", "de", "the", "das", "der", "die")

    // ---------------- Liedtitel oder Ansage? ----------------

    private val talkWords = Regex("(?i)nachrichten|news|verkehr|wetter|werbung|podcast|interview|magazin|sendung|moderation|gewinnspiel")

    private val junk = Regex("(?i)verbraucherinfo|ein angebot de[rs]|livestream|anzeige|hotline|jetzt anrufen|www\\.|" +
        "\\b[\\w-]+\\.(com|de|ch|at|fm|net|org|info|eu|io|radio)\\b|\\d{3,}[\\s/-]\\d{2,}")

    /** Ein Liedtitel sieht meist so aus: "Künstler - Titel". Sendungen, Nachrichten, Werbung nicht. */
    fun isMusicTitle(title: String) =
        (title.contains(" - ") || title.contains(" – ")) && !talkWords.containsMatchIn(title) && !junk.containsMatchIn(title)

    // ---------------- Daumen runter ----------------

    /** Künstler-Teil eines Radiotitels ("Künstler - Titel"), normalisiert. */
    fun artistOf(title: String): String = normalize(title.substringBefore(" - ").substringBefore(" – "))

    /** Schlüssel eines Liedes (normalisiert, ohne Zusätze wie "Radio Edit"), für "nicht mehr spielen". */
    fun songKey(title: String): String = normalize(title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " "))

    /**
     * Soll dieses Lied im Mix nicht kommen? Gleiches Lied (auch anders geschrieben) oder ein abgelehnter Künstler
     * (als ganze Wörter im Künstler-Teil, "Queen" sperrt nicht "Queensryche" … aber "Queen & David Bowie").
     */
    fun disliked(title: String, songs: Set<String>, artists: Set<String>): Boolean {
        if (songs.isEmpty() && artists.isEmpty()) return false
        val key = songKey(title)
        if (songs.any { it == key || sameSong(key, it) }) return true
        val artist = artistOf(title)
        if (artist.isEmpty()) return false
        return artists.any { a -> a.isNotEmpty() && a.split(" ").all { w -> artist.split(" ").contains(w) } }
    }

    // ---------------- Fast vorbei, obwohl der Anfang unbekannt ist ----------------

    /**
     * Lied mit unbekanntem Anfang: wir wissen nur, seit wann wir es sehen (seenForMs). Läuft es schon länger,
     * als es dauert (minus 1 Min.), oder ohne Länge schon über 3 Min., ist es wohl fast vorbei.
     */
    fun unknownStartAlmostOver(seenForMs: Long, durMs: Long): Boolean =
        if (durMs > 0) seenForMs > durMs - 60_000L else seenForMs > 180_000L

    // ---------------- Gleiche Lautstärke zwischen Sendern ----------------

    /** Zielpegel für Musik (dBFS, gemessen als RMS). */
    const val TARGET_DB = -16f

    /** Verstärkung in dB, damit ein Sender mit diesem Pegel so laut ist wie die anderen (höchstens ±8 dB). */
    fun loudnessGain(stationDb: Float): Float =
        if (stationDb.isNaN() || stationDb < -60f) 0f else (TARGET_DB - stationDb).coerceIn(-8f, 8f)

    /** RMS-Pegel eines Tonstücks in dBFS (-90 bei Stille). */
    fun rmsDb(samples: FloatArray): Float {
        if (samples.isEmpty()) return -90f
        var sum = 0.0
        for (v in samples) sum += v * v
        val rms = kotlin.math.sqrt(sum / samples.size)
        return if (rms <= 1e-5) -90f else (20 * kotlin.math.log10(rms)).toFloat()
    }
}
