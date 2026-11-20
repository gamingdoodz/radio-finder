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

/** Widget für den Startbildschirm: Play/Pause, Weiter, Hit-Mix. */
class RadioWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_PLAY = "de.gamingdoodz.radiofinder.W_PLAY"
        private const val ACTION_NEXT = "de.gamingdoodz.radiofinder.W_NEXT"
        private const val ACTION_MIX = "de.gamingdoodz.radiofinder.W_MIX"

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)

        /** Vom Player aufgerufen, wenn sich etwas ändert. */
        fun update(context: Context, title: String, sub: String, playing: Boolean) {
            prefs(context).edit().putString("title", title).putString("sub", sub).putBoolean("playing", playing).apply()
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RadioWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(context))
        }

        private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, RadioWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun views(context: Context): RemoteViews {
            val p = prefs(context)
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            v.setTextViewText(R.id.wTitle, p.getString("title", null) ?: "Radio Finder")
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
        mgr.updateAppWidget(ids, views(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_PLAY && action != ACTION_NEXT && action != ACTION_MIX) return
        val result = goAsync()
        PlayerControl.run(context, done = { result.finish() }) { c ->
            when (action) {
                ACTION_PLAY -> when {
                    c.isPlaying -> c.pause()
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
            }
        }
    }
}
