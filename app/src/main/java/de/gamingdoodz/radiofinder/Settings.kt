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

    /** Audioqualität: 0 = Datensparend, 1 = Normal, 2 = Hoch. */
    /** 0 Sparend, 1 Normal, 2 Hoch, 3 Automatisch (WLAN hoch, mobil normal). */
    /** Design: 0 wie das Handy, 1 hell, 2 dunkel. */
    fun theme(context: Context) = prefs(context).getInt("theme", 0)
    fun setTheme(context: Context, v: Int) = prefs(context).edit().putInt("theme", v).apply()

    /** Schriftgröße: 0 klein, 1 normal, 2 groß, 3 sehr groß. */
    fun textSize(context: Context) = prefs(context).getInt("textsize", 1)
    fun setTextSize(context: Context, v: Int) = prefs(context).edit().putInt("textsize", v).apply()
    fun textScale(context: Context) = listOf(0.9f, 1f, 1.15f, 1.3f)[textSize(context).coerceIn(0, 3)]

    /** Kompakte Ansicht: kleinere Karten, mehr auf einen Blick. */
    fun compact(context: Context) = prefs(context).getBoolean("compact", false)
    fun setCompact(context: Context, v: Boolean) = prefs(context).edit().putBoolean("compact", v).apply()

    /** Startseite: welche Bereiche angezeigt werden. */
    val homeSections = listOf("now" to "Jetzt läuft", "quick" to "Schnellstart", "again" to "Weiterhören",
        "live" to "Jetzt im Radio", "radiocharts" to "Meistgespielt im Radio", "charts" to "Deutsche Charts")
    fun homeOn(context: Context, key: String) = prefs(context).getBoolean("home_$key", true)
    fun setHomeOn(context: Context, key: String, v: Boolean) = prefs(context).edit().putBoolean("home_$key", v).apply()

    /** Mit welchem Reiter die App startet: 0 Start, 1 Musik, 2 Hörspiele, 3 Bibliothek. */
    fun startTab(context: Context) = prefs(context).getInt("starttab", 0)
    fun setStartTab(context: Context, v: Int) = prefs(context).edit().putInt("starttab", v).apply()

    fun quality(context: Context) = prefs(context).getInt("quality", 3)

    /** Gerade hohe Qualität? Bei "Automatisch" je nach WLAN oder mobilen Daten. */
    fun highQuality(context: Context): Boolean = when (quality(context)) {
        2 -> true
        3 -> Net.isUnmetered(context)
        else -> false
    }
    fun setQuality(context: Context, q: Int) = prefs(context).edit().putInt("quality", q).apply()

    /** Lautstärke zwischen Sendern angleichen. */
    fun normalize(context: Context) = prefs(context).getBoolean("normalize", true)
    fun setNormalize(context: Context, on: Boolean) = prefs(context).edit().putBoolean("normalize", on).apply()

    /** Kinder-Modus mit PIN. */
    fun kidsMode(context: Context) = prefs(context).getBoolean("kidsMode", false)
    fun setKidsMode(context: Context, on: Boolean) = prefs(context).edit().putBoolean("kidsMode", on).apply()
    fun pin(context: Context) = prefs(context).getString("pin", "") ?: ""
    fun setPin(context: Context, pin: String) = prefs(context).edit().putString("pin", pin).apply()

    // Radio-Wecker
    fun alarmOn(context: Context) = prefs(context).getBoolean("alarmOn", false)
    fun alarmHour(context: Context) = prefs(context).getInt("alarmH", 6)
    fun alarmMinute(context: Context) = prefs(context).getInt("alarmM", 30)
    fun alarmWeekdays(context: Context) = prefs(context).getBoolean("alarmWeekdays", true)
    /** "hitmix:<genre>" oder ein gespeicherter Eintrag als JSON. */
    fun alarmSource(context: Context) = prefs(context).getString("alarmSource", "hitmix:pop") ?: "hitmix:pop"
    fun alarmLabel(context: Context) = prefs(context).getString("alarmLabel", "Guten-Morgen-Mix") ?: "Guten-Morgen-Mix"

    fun setAlarm(
        context: Context,
        on: Boolean = alarmOn(context),
        hour: Int = alarmHour(context),
        minute: Int = alarmMinute(context),
        weekdays: Boolean = alarmWeekdays(context),
        source: String = alarmSource(context),
        label: String = alarmLabel(context),
    ) {
        prefs(context).edit()
            .putBoolean("alarmOn", on).putInt("alarmH", hour).putInt("alarmM", minute)
            .putBoolean("alarmWeekdays", weekdays).putString("alarmSource", source).putString("alarmLabel", label)
            .apply()
        AlarmScheduler.schedule(context)
    }
}
