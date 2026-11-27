package de.gamingdoodz.radiofinder

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Klang-Erkennung auf dem Handy mit YAMNet (Google, Apache 2.0, ~4 MB): hört, ob gerade Musik oder Sprache läuft.
 * Läuft nur kurz nach dem Umschalten und während "Werbung stumm" (höchstens ~3 Minuten), alle 2 s ein Stück von 1 s.
 * Nichts wird gespeichert oder verschickt, kein Server.
 */
@OptIn(UnstableApi::class)
object SoundSense : TeeAudioProcessor.AudioBufferSink {
    private const val RATE = 16_000
    private const val WINDOW = 15_600          // 0,975 s bei 16 kHz (YAMNet-Eingabe)
    private const val EVERY_MS = 2_000L

    enum class State { MUSIC, TALK, UNKNOWN }

    /** Soll gerade zugehört werden? (setzt PlaybackService) */
    @Volatile var active = false
        set(v) { if (field != v) { field = v; if (!v) fill = 0 } }

    @Volatile private var failed = false
    @Volatile private var busy = false
    @Volatile private var lastRun = 0L
    @Volatile private var appContext: Context? = null

    // Ergebnis: seit wann ununterbrochen Musik bzw. Sprache
    @Volatile var state = State.UNKNOWN
        private set
    @Volatile private var streakSince = 0L

    private var srcRate = 0
    private var channels = 0
    private var pcm16 = false
    private var step = 1.0
    private var phase = 0.0
    private var sum = 0f
    private var count = 0
    private val buf = FloatArray(WINDOW)
    @Volatile private var fill = 0

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Klangerkennung").apply { priority = Thread.MIN_PRIORITY } }
    private var interpreter: Interpreter? = null
    private var input: ByteBuffer? = null
    private var output: ByteBuffer? = null

    fun init(c: Context) { appContext = c.applicationContext }

    /** Neuer Sender: altes Ergebnis vergessen. */
    fun reset() { state = State.UNKNOWN; streakSince = 0L; fill = 0; lastRun = 0L }

    /** Läuft seit mindestens ms ununterbrochen Musik? */
    fun musicFor(ms: Long) = state == State.MUSIC && System.currentTimeMillis() - streakSince >= ms

    /** Läuft seit mindestens ms ununterbrochen Sprache (ohne Musik)? */
    fun talkFor(ms: Long) = state == State.TALK && System.currentTimeMillis() - streakSince >= ms

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        srcRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        pcm16 = encoding == C.ENCODING_PCM_16BIT
        step = if (sampleRateHz > 0) RATE.toDouble() / sampleRateHz else 1.0
        phase = 0.0; sum = 0f; count = 0; fill = 0
    }

    /** Wird vom Player-Thread mit den Tondaten aufgerufen: muss schnell sein, liest nur. */
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!active || failed || !pcm16 || srcRate <= 0 || busy) return
        if (fill == 0 && System.currentTimeMillis() - lastRun < EVERY_MS) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = 2 * channels
        var pos = b.position()
        val end = b.limit()
        while (pos + frameBytes <= end && fill < WINDOW) {
            var s = 0f
            for (ch in 0 until channels) s += b.getShort(pos + 2 * ch) / 32768f
            sum += s / channels; count++
            pos += frameBytes
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                buf[fill++] = sum / count   // Mittelwert = einfacher Tiefpass vor dem Verkleinern auf 16 kHz
                sum = 0f; count = 0
            }
        }
        if (fill >= WINDOW) {
            val copy = buf.copyOf()
            fill = 0
            busy = true
            lastRun = System.currentTimeMillis()
            worker.execute { try { classify(copy) } finally { busy = false } }
        }
    }

    private fun load(): Boolean {
        if (interpreter != null) return true
        val c = appContext ?: return false
        return try {
            val bytes = c.assets.open("yamnet.tflite").use { it.readBytes() }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
            val ip = Interpreter(model, Interpreter.Options().setNumThreads(1))
            val inShape = ip.getInputTensor(0).shape()
            if (inShape.size != 1 || inShape[0] != WINDOW) { ip.resizeInput(0, intArrayOf(WINDOW)); ip.allocateTensors() }
            input = ByteBuffer.allocateDirect(WINDOW * 4).order(ByteOrder.nativeOrder())
            output = ByteBuffer.allocateDirect(ip.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            interpreter = ip
            true
        } catch (_: Throwable) {
            failed = true   // Handy kann es nicht: einfach ohne Klang-Erkennung weiter
            false
        }
    }

    private fun classify(samples: FloatArray) {
        try {
            if (!active || !load()) return
            val inp = input ?: return
            val out = output ?: return
            inp.clear(); for (v in samples) inp.putFloat(v); inp.rewind()
            out.clear()
            interpreter?.run(inp, out)
            out.rewind()
            val n = out.capacity() / 4
            if (n < 277) return
            val scores = FloatArray(n) { out.getFloat() }
            // YAMNet-Klassen: 0–3 Sprache/Erzählen, 24–31 Gesang, 132–276 Musik und Instrumente
            val speech = (0..3).maxOf { scores[it] }
            val music = maxOf((132..276).maxOf { scores[it] }, (24..31).maxOf { scores[it] })
            val now = when {
                music >= 0.35f && music > speech -> State.MUSIC
                speech >= 0.35f && speech >= music -> State.TALK
                else -> State.UNKNOWN
            }
            if (now != state) { state = now; streakSince = lastRun }
        } catch (_: Throwable) {
            failed = true
        }
    }

    fun release() {
        active = false
        worker.execute { try { interpreter?.close() } catch (_: Throwable) {}; interpreter = null }
    }
}
