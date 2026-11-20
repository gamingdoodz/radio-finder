package de.gamingdoodz.radiofinder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.SessionCommand
import java.util.Calendar

/** Plant den Radio-Wecker (und nach jedem Klingeln den nächsten). */
object AlarmScheduler {
    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 100, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun nextTime(context: Context): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, Settings.alarmHour(context))
        c.set(Calendar.MINUTE, Settings.alarmMinute(context))
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis() + 30_000) c.add(Calendar.DAY_OF_YEAR, 1)
        if (Settings.alarmWeekdays(context)) {
            while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return c.timeInMillis
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)
        am.cancel(pi)
        if (!Settings.alarmOn(context)) return
        val at = nextTime(context)
        val show = PendingIntent.getActivity(
            context, 101, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exactAllowed = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** Der Wecker klingelt: Musik leise starten und lauter werden lassen. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        PlayerControl.run(context, done = {
            AlarmScheduler.schedule(context)
            result.finish()
        }) { c ->
            c.sendCustomCommand(SessionCommand(PlaybackService.CMD_FADEIN, Bundle.EMPTY), Bundle.EMPTY)
            c.setMediaItem(PlayerControl.itemFor(Settings.alarmSource(context)))
            c.prepare()
            c.play()
        }
    }
}

/** Nach dem Neustart des Handys den Wecker wieder planen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlarmScheduler.schedule(context)
    }
}
