package de.gamingdoodz.radiofinder

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Wie genau ist die Liedzeit? Hört bei einigen Sendern mit (nur die Titel im Datenstrom, der Ton wird sofort
 * verworfen und nirgends gespeichert) und merkt sich, wann der Titel wirklich wechselt. Das vergleicht der Bericht
 * mit dem Liedanfang, den der Server meldet bzw. den die App daraus schätzt.
 */
object Messen {
    class Wechsel(val title: String, val at: Long)
    val wechsel = ConcurrentHashMap<String, MutableList<Wechsel>>()
    /** Server-Angaben je Sender und Lied: (since, genau vom Server, von der App geschätzt) – die erste Meldung zählt. */
    class Angabe(val since: Long, val exact: Boolean, val estimated: Boolean)
    val angaben = ConcurrentHashMap<String, ConcurrentHashMap<String, Angabe>>()
    @Volatile var stop = false

    fun melde(id: String, title: String, since: Long, exact: Boolean, estimated: Boolean) {
        if (since <= 0) return
        angaben.getOrPut(id) { ConcurrentHashMap() }.putIfAbsent(MixLogic.normalize(title), Angabe(since, exact, estimated))
    }

    fun start(ids: List<Pair<String, String>>) = ids.map { (id, url) ->
        Thread { try { lauschen(id, url) } catch (_: Exception) {} }.apply { isDaemon = true; start() }
    }

    private fun lauschen(id: String, url: String) {
        var tries = 0
        while (!stop && tries < 3) {
            tries++
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 30_000
                c.setRequestProperty("Icy-MetaData", "1")
                c.setRequestProperty("User-Agent", "Hitradar-Pruefung/1.0")
                val metaInt = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull() ?: return
                c.inputStream.use { lesen(id, it, metaInt) }
            } catch (_: Exception) { Thread.sleep(5_000) }
        }
    }

    private fun lesen(id: String, inp: InputStream, metaInt: Int) {
        val skip = ByteArray(8192)
        var last: String? = null
        var first = true
        while (!stop) {
            var left = metaInt
            while (left > 0) { val n = inp.read(skip, 0, minOf(left, skip.size)); if (n < 0) return; left -= n }   // Ton verwerfen
            val len = inp.read() * 16
            if (len < 0) return
            if (len == 0) continue
            val meta = ByteArray(len); var got = 0
            while (got < len) { val n = inp.read(meta, got, len - got); if (n < 0) return; got += n }
            val t = Regex("StreamTitle='(.*?)';").find(String(meta, Charsets.UTF_8))?.groupValues?.get(1)?.trim().orEmpty()
            if (t.isEmpty() || t == last) continue
            // Der erste Titel nach dem Verbinden ist kein Wechsel (das Lied lief schon)
            if (!first) wechsel.getOrPut(id) { java.util.Collections.synchronizedList(ArrayList()) }.add(Wechsel(Text.clean(t), System.currentTimeMillis()))
            first = false; last = t
        }
    }

    /** Fehler in Sekunden: Server-/App-Liedanfang minus echter Wechsel im Datenstrom. */
    fun bericht(offset: Long) {
        val server = ArrayList<Long>(); val app = ArrayList<Long>(); var unbekannt = 0; var keineAngabe = 0
        for ((id, list) in wechsel) for (w in list.toList()) {
            if (!MixLogic.isMusicTitle(w.title)) continue
            val key = MixLogic.normalize(w.title)
            val a = angaben[id]?.entries?.firstOrNull { it.key == key || MixLogic.sameSong(it.key, key) }?.value
            when {
                a == null -> keineAngabe++
                a.exact && !a.estimated -> server.add((a.since + offset - w.at) / 1000)
                a.estimated -> app.add((a.since + offset - w.at) / 1000)
                else -> unbekannt++
            }
        }
        fun stat(l: List<Long>): String {
            if (l.isEmpty()) return "keine"
            val abs = l.map { kotlin.math.abs(it) }.sorted()
            return "${l.size} Lieder, Fehler im Mittel (Median) ${abs[abs.size / 2]} s, 9 von 10 unter ${abs[(abs.size * 9 / 10).coerceAtMost(abs.size - 1)]} s, " +
                "größter ${abs.last()} s, Richtung im Mittel ${l.sorted()[l.size / 2]} s (+ = Server zu spät)"
        }
        say()
        say("## Wie genau ist die Liedzeit? (echter Titelwechsel im Datenstrom gegen die Angabe)")
        say("- Mitgehört: ${wechsel.size} Sender, ${wechsel.values.sumOf { it.size }} Titelwechsel")
        say("- Anfang genau vom Server: ${stat(server)}")
        say("- Anfang von der App aus dem Abgleich geschätzt: ${stat(app)}")
        say("- Anfang unbekannt (App zeigt „mind.“): $unbekannt, vom Server (noch) nicht gemeldet: $keineAngabe")
    }
}
