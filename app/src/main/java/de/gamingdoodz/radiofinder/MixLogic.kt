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

    /** Klare Richtung eines Senders (z. B. "rock" bei ROCK ANTENNE), null = gemischt/unklar (Hits, Pop, Sommer). */
    fun mainGenre(tags: String, name: String): String? =
        stationRules.keys.filter { it != "summer" }
            .map { it to stationScore(it, tags, name) }
            .filter { it.second >= 5 }
            .maxByOrNull { it.second }?.first

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
     * Beide Titel normalisiert. Mindestens 2 gemeinsame Wörter; der kürzere steckt ganz im längeren, oder 85 % sind gleich.
     */
    fun sameSong(a: String, b: String?): Boolean {
        if (b == null) return false
        if (a == b) return true
        val wa = songWords(a)
        val wb = songWords(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (wa == wb) return true
        val common = wa.intersect(wb).size
        if (common < 2) return false
        // Ein Titel steckt ganz im anderen ("… feat. Sia", "Live"): dasselbe Lied – aber nur bei genug Wörtern
        // (nur "Ed Sheeran" ist nicht "Ed Sheeran - Perfect")
        if (common == minOf(wa.size, wb.size)) return common >= 3 && maxOf(wa.size, wb.size) - common <= 2
        // Beide haben eigene Wörter: fast alles muss gleich sein. Sonst wären zwei Lieder desselben Künstlers
        // mit langem Namen ("Red Hot Chili Peppers - Californication" / "- Otherside") fälschlich gleich.
        return common >= 0.85 * maxOf(wa.size, wb.size)
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
    fun songKey(title: String): String = normalize(title.replace(BRACKETS, " "))
    private val BRACKETS = Regex("\\(.*?\\)|\\[.*?\\]")

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

    // ---------------- Gleicher Sender, andere Qualität ----------------

    private val quality = Regex("^(flac|meta|aac|aacplus|aac\\+|mp3|ogg|opus|hq|lq|hd|hifi|lossless|\\d+k|\\d+kbps|kbps|kbit|kbit/s|stream|mobile)$")

    /** "Radio Paradise Mellow Mix FLAC+meta" und "… 128k AAC" sind derselbe Sender: Name ohne Qualitätsangaben. */
    fun stationBaseName(name: String): String =
        normalize(name).split(" ").filter { it.isNotEmpty() && !quality.matches(it) }.joinToString(" ").ifEmpty { normalize(name) }

    // ---------------- Suche: Treffer finden (auch beim Tippen und mit kleinen Tippfehlern) ----------------

    /** Wie viele Buchstaben dürfen bei einem Suchwort falsch sein? Kurze Wörter genau, ab 5 einer, ab 8 zwei. */
    fun typoAllowance(word: String) = when { word.length >= 8 -> 2; word.length >= 5 -> 1; else -> 0 }

    /** Unterschied zweier Wörter in Buchstaben (Einfügen, Löschen, Tauschen), höchstens bis max (sonst max + 1). */
    fun editDistance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Passt ein Suchwort zu einem Wort im Text? Genau, als Wortanfang (beim Tippen: "helen" findet "Helene"),
     * zusammengeschrieben ("acdc" = "ac dc") und – nur wenn fuzzy – mit kleinem Tippfehler ("fisher" = "Fischer").
     * Nie mitten im Wort ("up" findet nicht "superstar").
     */
    fun wordMatches(q: String, textWord: String, fuzzy: Boolean): Boolean {
        if (q == textWord) return true
        if (q.length >= 3 && textWord.startsWith(q)) return true
        if (!fuzzy) return false
        val max = typoAllowance(q)
        if (max == 0) return false
        // Auch beim Tippen mit Fehler: Anfang des Textworts vergleichen ("fishe" ~ "fische…")
        if (textWord.length > q.length + max && editDistance(q, textWord.substring(0, q.length), max) <= max) return true
        return editDistance(q, textWord, max) <= max
    }

    /** Alle Suchwörter (normalisiert) kommen im normalisierten Text vor? */
    fun searchMatches(normalizedText: String, queryWords: List<String>, fuzzy: Boolean = false): Boolean {
        if (queryWords.isEmpty()) return false
        val words = normalizedText.split(' ').filter { it.isNotEmpty() }
        return queryWords.all { q ->
            words.any { wordMatches(q, it, fuzzy) } ||
                // zusammengeschrieben: "acdc" passt zu "ac dc", "dietoten" zu "die toten"
                (q.length >= 4 && words.indices.any { i -> i + 1 < words.size && (words[i] + words[i + 1]).let { it == q || it.startsWith(q) } })
        }
    }

    /** Sendername passt? Wie oben, zusätzlich Wortteile ab 4 Buchstaben ("antenne" in "Rockantenne"). */
    fun stationNameMatches(normalizedName: String, queryWords: List<String>, fuzzy: Boolean = false): Boolean =
        queryWords.isNotEmpty() && queryWords.all { q ->
            (q.length >= 4 && normalizedName.contains(q)) || searchMatches(normalizedName, listOf(q), fuzzy)
        }

    /** Mittlerer Wert (Ausreißer zählen nicht so stark wie beim Durchschnitt). */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val v = values.sorted()
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }
}
