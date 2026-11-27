package de.gamingdoodz.radiofinder

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** Kurz mit dem Player verbinden (für Wecker und Widget), etwas tun, wieder trennen. */
object PlayerControl {
    /** holdMs: so lange verbunden bleiben (Hit-Mix/Wecker brauchen zum Laden manchmal über 10 s). */
    fun run(context: Context, done: () -> Unit = {}, holdMs: Long = 3_000L, block: (MediaController) -> Unit) {
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            try {
                block(future.get())
            } catch (_: Exception) {
            }
            val h = Handler(Looper.getMainLooper())
            h.postDelayed({ done() }, 3_000)
            h.postDelayed({ MediaController.releaseFuture(future) }, holdMs.coerceAtLeast(3_000L))
        }, ContextCompat.getMainExecutor(app))
    }

    /** Was beim Wecker / Widget gespielt wird. */
    fun itemFor(source: String) =
        if (source.startsWith("hitmix:")) hitMixItem(source.removePrefix("hitmix:").ifBlank { null }, "⚡ Hit-Mix")
        else try { Entry.fromJson(org.json.JSONObject(source)).toMediaItem() } catch (_: Exception) { hitMixItem("pop", "⚡ Hit-Mix") }
}
