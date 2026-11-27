package de.gamingdoodz.radiofinder

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Einstellungen fürs Casten auf Google-Home-/Nest-Lautsprecher und Chromecast (Standard-Empfänger von Google). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        // Eigene Benachrichtigung reicht: keine zweite vom Cast-SDK
        .setCastMediaOptions(com.google.android.gms.cast.framework.media.CastMediaOptions.Builder()
            .setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object Cast {
    /** Läuft gerade auf einem Lautsprecher? Dann werden Sender mit https bevorzugt. */
    @Volatile var active = false
    @Volatile var deviceName: String? = null

    /**
     * Gerät spielt nur verschlüsselte Streams. Sender ohne https lassen sich nicht casten:
     * Hitradar leitet keine Radiomusik über den eigenen Server weiter (rechtlich ein Weitersenden).
     */
    fun convert(item: MediaItem): MediaItem? {
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: return null
        val url = uri.toString()
        val station = item.mediaId.startsWith("st:")
        val target = when {
            url.startsWith("https://") -> url
            station -> return null
            else -> url.replaceFirst("http://", "https://")   // freie Musik/Podcasts bieten fast immer auch https
        }
        val mime = when {
            url.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
            url.contains(".aac", true) -> MimeTypes.AUDIO_AAC
            url.contains(".ogg", true) -> MimeTypes.AUDIO_OGG
            else -> MimeTypes.AUDIO_MPEG
        }
        return item.buildUpon().setUri(Uri.parse(target)).setMimeType(mime).build()
    }

    fun isHttps(url: String) = url.startsWith("https://", true)
}
