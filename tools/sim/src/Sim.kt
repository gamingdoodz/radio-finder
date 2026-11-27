package de.gamingdoodz.radiofinder

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Hit-Mix und Suche mit echten Daten durchspielen (ohne Android): holt den Stand vom Hitradar-Server wie die App
 * (ein /sync, dann alle 60 s nur Änderungen – so viel Last wie ein einziges Handy) und prüft die Regeln aus MixLogic.
 * Ergebnis: Bericht auf der Konsole (landet im Actions-Protokoll und als Datei).
 */
val SERVER = System.getenv("SERVER") ?: "https://130-61-78-232.sslip.io:8080"
val MINUTES = (System.getenv("MINUTES") ?: "30").toInt()
val out = StringBuilder()
fun say(s: String = "") { println(s); out.append(s).append('\n') }

class Got(val text: String?, val bytes: Int, val ms: Long)

fun get(url: String, gzip: Boolean = true): Got {
    val t0 = System.currentTimeMillis()
    return try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Hitradar-Pruefung/1.0")
        if (gzip) c.setRequestProperty("Accept-Encoding", "gzip")
        val raw = c.inputStream.use { it.readBytes() }
        val text = if (c.contentEncoding == "gzip") GZIPInputStream(raw.inputStream()).use { String(it.readBytes()) } else String(raw)
        Got(text, raw.size, System.currentTimeMillis() - t0)
    } catch (e: Exception) { Got(null, 0, System.currentTimeMillis() - t0) }
}

class Live(var name: String, var url: String, var title: String, var since: Long, var seen: Long, var exact: Boolean)
val live = HashMap<String, Live>()
var version = 0L
var serverT = 0L

fun sync(): Got {
    val g = get("$SERVER/sync?from=$version")
    val text = g.text ?: return g
    val o = JSONObject(text)
    version = o.optLong("v"); serverT = o.optLong("t") * 1000
    if (o.optInt("full") == 1) live.clear()
    val rows = o.optJSONArray("rows") ?: JSONArray()
    for (i in 0 until rows.length()) {
        val r = rows.getJSONArray(i)
        val id = r.getString(0)
        if (r.length() >= 7) live[id] = Live(r.optString(1), r.optString(2), Text.clean(r.optString(4)), r.optLong(5) * 1000, r.optLong(6) * 1000,
            r.length() < 9 || r.optInt(8, 1) == 1)
        else if (r.length() >= 4) live[id]?.let { it.title = Text.clean(r.optString(1)); it.since = r.optLong(2) * 1000; it.seen = r.optLong(3) * 1000
            it.exact = r.length() < 5 || r.optInt(4, 1) == 1 }
    }
    o.optJSONArray("gone")?.let { for (i in 0 until it.length()) live.remove(it.getString(i)) }
    return g
}

fun main() {
    say("# Hitradar – Prüfung mit echten Daten (${java.time.LocalDateTime.now()})")
    say()
    say("## Server: Antwortzeiten und Datenmengen (gepackt, wie die App sie lädt)")
    for (p in listOf("/health", "/charts", "/popular", "/search?q=helene", "/stations?q=antenne", "/list?t=popular", "/list?t=tag%3Arock")) {
        val g = get(SERVER + p)
        say("- `$p`: ${if (g.text == null) "FEHLER" else "${g.bytes / 1024} KB in ${g.ms} ms"}")
    }
    val first = sync()
    say("- `/sync` erstes Mal (alles): ${first.bytes / 1024} KB in ${first.ms} ms, ${live.size} Sender")
    if (live.isEmpty()) { say("Server nicht erreichbar – Abbruch"); save(); return }
    val offset = System.currentTimeMillis() - serverT
    say("- Uhr-Abweichung Server/Runner: ${offset / 1000} s")

    // ---- Titel: was hält die App für Musik? ----
    val music = live.values.filter { MixLogic.isMusicTitle(it.title) && !MixLogic.isStationJingle(it.title, it.name) }
    val notMusic = live.values.filter { !MixLogic.isMusicTitle(it.title) }
    say()
    say("## Titel")
    say("- ${live.size} Sender mit Titel, davon ${music.size} als Lied erkannt, ${notMusic.size} nicht")
    say("- Anfang bekannt (exact): ${live.values.count { it.exact }} von ${live.size}")
    say("- Als Lied erkannt, sieht aber verdächtig aus (Stichprobe):")
    val suspicious = Regex("(?i)news|nachricht|werbung|wetter|verkehr|radio|fm\\b|live|sendung|show|podcast|moderat|jingle|www\\.|http|\\.de\\b|\\.com|@|anruf|hotline|gewinn|interview|\\d{2}:\\d{2}")
    music.filter { suspicious.containsMatchIn(it.title) }.shuffled(java.util.Random(1)).take(40).forEach { say("  - „${it.title}“ (${it.name})") }
    say("- Nicht als Lied erkannt, sieht aber wie ein Lied aus (Stichprobe):")
    notMusic.filter { it.title.length in 8..80 && (it.title.contains(" / ") || it.title.contains(" by ", true) || it.title.contains(": ") || it.title.contains("-")) }
        .shuffled(java.util.Random(2)).take(30).forEach { say("  - „${it.title}“ (${it.name})") }

    // ---- Gleiche Lieder ----
    say()
    say("## Gleiches Lied, anders geschrieben (sameSong) – Stichprobe verschiedener Schreibweisen")
    val norms = music.map { MixLogic.normalize(it.title) }.distinct()
    val byFirst = norms.groupBy { it.split(" ").firstOrNull().orEmpty() }
    var shown = 0
    loop@ for ((_, group) in byFirst) {
        for (i in group.indices) for (j in i + 1 until group.size) {
            val a = group[i]; val b = group[j]
            if (MixLogic.sameSong(a, b)) { say("  - „$a“ = „$b“"); if (++shown >= 50) break@loop }
        }
    }

    // ---- Suche ----
    say()
    say("## Suche in den laufenden Titeln (wie die App: erst genau, dann mit Tippfehlern)")
    for (q in listOf("helene fischer", "helene fisher", "ed sheeran", "ed sheran", "rammstein", "ramstein", "queen", "pink", "adele", "acdc",
        "taylor swift", "tailor swift", "coldplay", "david guetta", "udo lindenberg", "nena", "up", "love", "atemlos", "last christmas")) {
        val w = MixLogic.normalize(q).split(" ").filter { it.isNotBlank() }
        val exact = music.filter { MixLogic.searchMatches(MixLogic.normalize(it.title), w) }
        val hits = exact.ifEmpty { music.filter { MixLogic.searchMatches(MixLogic.normalize(it.title), w, fuzzy = true) } }
        say("- „$q“: ${hits.size} Treffer${if (exact.isEmpty() && hits.isNotEmpty()) " (mit Tippfehler)" else ""}: " +
            hits.take(4).joinToString(" | ") { it.title })
    }
    say("- Genre-Erkennung: " + listOf("rock", "rockmusik", "deutschrap", "80er", "schlager", "helene fischer", "queen", "weihnachtslieder", "kinderlieder", "hip hop", "classic rock", "techno")
        .joinToString(", ") { "„$it“→${MixLogic.genreKeyFor(it) ?: "-"}" })

    // ---- Sender je Richtung ----
    val tagged = HashMap<String, String>()
    for (tag in listOf("rock", "schlager", "hiphop", "80s", "christmas")) {
        val g = get("$SERVER/list?t=" + URLEncoder.encode("tag:$tag", "UTF-8"))
        val arr = g.text?.let { runCatching { JSONArray(it) }.getOrNull() } ?: continue
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); tagged[o.getString("id")] = (tagged[o.getString("id")].orEmpty() + " " + o.optString("tags")).trim() }
        say()
        say("## Sender für „$tag“ laut Server: ${arr.length()} (Tags mitgeschickt: ${(0 until arr.length()).count { arr.getJSONObject(it).optString("tags").isNotBlank() }})")
        val key = when (tag) { "hiphop" -> "rap"; "christmas" -> "xmas"; else -> tag }
        val fits = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { MixLogic.stationFits(key, it.optString("tags"), it.optString("name")) }
        say("- davon passen laut App-Regel: ${fits.size}. Was dort gerade läuft:")
        fits.take(15).forEach { o -> say("  - ${o.optString("name")}: ${live[o.optString("id")]?.title ?: "–"}") }
    }

    // ---- 30-Minuten-Mix ----
    say()
    say("## Hit-Mix „Rock“ $MINUTES Minuten (vereinfacht: springt am Liedende zum frischesten passenden Lied)")
    val rockIds = tagged.filter { MixLogic.stationFits("rock", it.value, live[it.key]?.name.orEmpty()) }.keys
    say("- passende Rock-Sender mit Titel: ${rockIds.count { it in live }}")
    var cur: String? = null; var curTitle = ""; val played = ArrayList<String>(); var lastBase = ""
    var waits = 0; var joins = 0; var repeats = 0; var sameStation = 0; val ages = ArrayList<Long>()
    val diffSizes = ArrayList<Int>(); val diffMs = ArrayList<Long>()
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < MINUTES * 60_000L) {
        val now = System.currentTimeMillis() - offset
        val curLive = cur?.let { live[it] }
        val songOver = curLive == null || MixLogic.normalize(curLive.title) != curTitle || !MixLogic.isMusicTitle(curLive.title)
        if (songOver) {
            val cand = rockIds.mapNotNull { id -> live[id]?.let { id to it } }
                .filter { (id, l) -> id != cur && l.exact && MixLogic.isMusicTitle(l.title) && !MixLogic.isStationJingle(l.title, l.name) && now - l.since in 0..90_000 }
                .filter { (_, l) -> MixLogic.stationBaseName(l.name) != lastBase && played.takeLast(20).none { MixLogic.sameSong(it, MixLogic.normalize(l.title)) } }
                .maxByOrNull { it.second.since }
            if (cand == null) { waits++ } else {
                val (id, l) = cand
                if (played.any { MixLogic.sameSong(it, MixLogic.normalize(l.title)) }) repeats++
                if (MixLogic.stationBaseName(l.name) == lastBase) sameStation++
                val age = (now - l.since) / 1000
                ages.add(age); joins++
                say("- ${java.time.LocalTime.now().withNano(0)}  ${l.name}: ${l.title}  (läuft seit ${age} s)")
                cur = id; curTitle = MixLogic.normalize(l.title); played.add(curTitle); lastBase = MixLogic.stationBaseName(l.name)
            }
        }
        Thread.sleep(60_000)
        val g = sync(); diffSizes.add(g.bytes); diffMs.add(g.ms)
    }
    say("- Lieder: $joins, Wiederholungen: $repeats, gleicher Sender direkt danach: $sameStation, Minuten ohne passendes frisches Lied: $waits")
    if (ages.isNotEmpty()) say("- Eingestiegen nach Liedbeginn: Mittel ${ages.average().toInt()} s, max ${ages.max()} s")
    if (diffSizes.isNotEmpty()) say("- `/sync` Änderungen je Minute: Mittel ${diffSizes.average().toInt() / 1024} KB, ${diffMs.average().toInt()} ms (≈ ${diffSizes.sum() / 1024 * 60 / MINUTES / 1024} MB pro Stunde bei 1/min)")
    save()
}

fun save() { java.io.File("bericht.md").writeText(out.toString()) }
