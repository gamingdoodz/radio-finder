package de.gamingdoodz.radiofinder

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

enum class Kind { STATION, TRACK }

/** Ein Eintrag in den Listen: ein Radiosender oder ein freies Lied. */
data class Entry(
    val id: String,          // "st:..." für Sender, "au:..." für freie Lieder
    val kind: Kind,
    val title: String,       // was groß angezeigt wird (z. B. laufendes Lied)
    val subtitle: String,    // Sendername oder Künstler
    val url: String,
    val artwork: String,
    val name: String,        // fester Name (Sendername bzw. Liedtitel) für Favoriten
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("title", name)
        .put("subtitle", if (kind == Kind.STATION) "Internetradio" else subtitle)
        .put("url", url).put("artwork", artwork).put("name", name)

    fun toMediaItem(): MediaItem {
        val uri = Uri.parse(url)
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(if (kind == Kind.STATION) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putString("name", name) })
        if (artwork.isNotBlank()) meta.setArtworkUri(Uri.parse(artwork))
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(meta.build())
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }

    companion object {
        fun fromJson(o: JSONObject) = Entry(
            id = o.getString("id"),
            kind = if (o.optString("kind") == "TRACK") Kind.TRACK else Kind.STATION,
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            url = o.getString("url"),
            artwork = o.optString("artwork"),
            name = o.optString("name").ifBlank { o.optString("title") },
        )

        fun fromMediaItem(item: MediaItem): Entry? {
            val url = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString() ?: return null
            val m = item.mediaMetadata
            val title = m.title?.toString() ?: ""
            return Entry(
                id = item.mediaId,
                kind = if (item.mediaId.startsWith("au:")) Kind.TRACK else Kind.STATION,
                title = title,
                subtitle = m.artist?.toString() ?: "",
                url = url,
                artwork = m.artworkUri?.toString() ?: "",
                name = m.extras?.getString("name") ?: title,
            )
        }
    }
}

fun Station.toEntry(song: String? = null) = Entry(
    id = "st:$id",
    kind = Kind.STATION,
    title = song ?: name,
    subtitle = if (song != null) name else "Internetradio",
    url = url,
    artwork = favicon,
    name = name,
)

fun Audius.Track.toEntry() = Entry(
    id = "au:$id",
    kind = Kind.TRACK,
    title = title,
    subtitle = artist,
    url = streamUrl,
    artwork = artwork,
    name = title,
)

const val ROOT_ID = "root"
const val FAV_ID = "fav"
const val HITS_ID = "hits"
const val KIDS_ID = "kids"
const val FREE_ID = "free"
const val POPULAR_ID = "popular"

fun folderItem(id: String, title: String): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()
