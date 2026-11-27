package de.gamingdoodz.radiofinder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.media3.session.SessionCommand

/** Großes Widget: Cover, Titel, Hit-Mix, Favorit, Play/Pause, Weiter. */
class RadioWidgetBig : RadioWidget()

/** Widget für den Startbildschirm: Cover, Play/Pause, Weiter, Hit-Mix. */
open class RadioWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_PLAY = "de.gamingdoodz.radiofinder.W_PLAY"
        private const val ACTION_NEXT = "de.gamingdoodz.radiofinder.W_NEXT"
        private const val ACTION_MIX = "de.gamingdoodz.radiofinder.W_MIX"
        private const val ACTION_FAV = "de.gamingdoodz.radiofinder.W_FAV"

        /** Cover des laufenden Liedes (oder Senderlogo) für beide Widgets. */
        @Volatile var art: android.graphics.Bitmap? = null

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)

        /** Vom Player aufgerufen, wenn sich etwas ändert. */
        fun update(context: Context, title: String, sub: String, playing: Boolean, fav: Boolean = false) {
            prefs(context).edit().putString("title", title).putString("sub", sub).putBoolean("playing", playing)
                .putBoolean("fav", fav).apply()
            refresh(context)
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val small = mgr.getAppWidgetIds(ComponentName(context, RadioWidget::class.java))
            if (small.isNotEmpty()) mgr.updateAppWidget(small, views(context, R.layout.widget_player))
            val big = mgr.getAppWidgetIds(ComponentName(context, RadioWidgetBig::class.java))
            if (big.isNotEmpty()) mgr.updateAppWidget(big, views(context, R.layout.widget_player_big))
        }

        private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, RadioWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun views(context: Context, layout: Int): RemoteViews {
            val p = prefs(context)
            val v = RemoteViews(context.packageName, layout)
            art?.let { v.setImageViewBitmap(R.id.wArt, it) } ?: v.setImageViewResource(R.id.wArt, R.mipmap.ic_launcher)
            // Widget ist immer dunkel: Symbole immer weiß (auch wenn das Handy hell eingestellt ist)
            val white = 0xFFFFFFFF.toInt()
            listOf(R.id.wMix, R.id.wNext, R.id.wPlay).forEach { v.setInt(it, "setColorFilter", white) }
            if (layout == R.layout.widget_player_big) {
                val fav = p.getBoolean("fav", false)
                v.setImageViewResource(R.id.wFav, if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                v.setInt(R.id.wFav, "setColorFilter", if (fav) 0 else white)
                v.setOnClickPendingIntent(R.id.wFav, action(context, ACTION_FAV, 5))
                v.setOnClickPendingIntent(R.id.wArt, PendingIntent.getActivity(context, 6, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            v.setTextViewText(R.id.wTitle, p.getString("title", null) ?: "Hitradar")
            v.setTextViewText(R.id.wSub, p.getString("sub", null) ?: "Tippe auf ⚡ für den Hit-Mix")
            v.setImageViewResource(R.id.wPlay, if (p.getBoolean("playing", false)) R.drawable.ic_pause else R.drawable.ic_play)
            v.setOnClickPendingIntent(R.id.wPlay, action(context, ACTION_PLAY, 1))
            v.setOnClickPendingIntent(R.id.wNext, action(context, ACTION_NEXT, 2))
            v.setOnClickPendingIntent(R.id.wMix, action(context, ACTION_MIX, 3))
            v.setOnClickPendingIntent(
                R.id.wInfo, PendingIntent.getActivity(
                    context, 4, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            return v
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, views(context, if (this is RadioWidgetBig) R.layout.widget_player_big else R.layout.widget_player))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_PLAY && action != ACTION_NEXT && action != ACTION_MIX && action != ACTION_FAV) return
        val result = goAsync()
        PlayerControl.run(context, done = { result.finish() }) { c ->
            when (action) {
                ACTION_PLAY -> when {
                    c.playWhenReady && c.mediaItemCount > 0 && c.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                        c.playbackState != androidx.media3.common.Player.STATE_ENDED -> c.pause()   // auch beim Verbinden/Puffern stoppbar
                    c.mediaItemCount > 0 -> { c.prepare(); c.play() }
                    else -> {
                        val last = Recent.all(context).firstOrNull()
                        c.setMediaItem(last?.toMediaItem() ?: hitMixItem(null, "⚡ Hit-Mix"))
                        c.prepare(); c.play()
                    }
                }
                ACTION_NEXT ->
                    if (HitMix.activeName(context) != null) c.sendCustomCommand(SessionCommand(PlaybackService.CMD_NEXT, Bundle.EMPTY), Bundle.EMPTY)
                    else c.seekToNextMediaItem()
                ACTION_MIX -> {
                    c.setMediaItem(hitMixItem(null, "⚡ Hit-Mix"))
                    c.prepare(); c.play()
                }
                ACTION_FAV -> c.currentMediaItem?.let { Entry.fromMediaItem(it) }?.let { e ->
                    val now = Favorites.toggle(context, e)
                    prefs(context).edit().putBoolean("fav", now).apply()
                    refresh(context)
                }
            }
        }
    }
}
