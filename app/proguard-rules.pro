# Hitradar – Regeln für R8 (Verkleinern/Optimieren)
# Zeilennummern in Absturzberichten behalten
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Dienste, Empfänger und Widgets werden über das Manifest gefunden (R8 behält sie automatisch),
# zur Sicherheit trotzdem alle App-Klassen mit ihren Namen behalten, die Android per Name startet:
-keep class de.gamingdoodz.radiofinder.PlaybackService { *; }
-keep class de.gamingdoodz.radiofinder.WatchService { *; }
-keep class de.gamingdoodz.radiofinder.RadioWidget { *; }
-keep class de.gamingdoodz.radiofinder.RadioWidgetBig { *; }
-keep class de.gamingdoodz.radiofinder.AlarmReceiver { *; }
-keep class de.gamingdoodz.radiofinder.BootReceiver { *; }

# Kotlin-Coroutines
-dontwarn kotlinx.coroutines.**
# Guava (nur ein kleiner Teil wird genutzt)
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-keep class de.gamingdoodz.radiofinder.CastOptionsProvider { *; }
