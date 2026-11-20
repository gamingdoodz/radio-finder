package de.gamingdoodz.radiofinder

import android.content.Context
import android.content.SharedPreferences

object Settings {
    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Datensparen unterwegs: mobil nur die beliebtesten Sender durchsuchen. */
    fun saver(context: Context) = prefs(context).getBoolean("saver", true)
    fun setSaver(context: Context, on: Boolean) = prefs(context).edit().putBoolean("saver", on).apply()

    /** Ende des Schlaf-Timers (0 = aus). */
    fun sleepEnd(context: Context) = prefs(context).getLong("sleepEnd", 0L)
    fun setSleepEnd(context: Context, end: Long) = prefs(context).edit().putLong("sleepEnd", end).apply()
}
