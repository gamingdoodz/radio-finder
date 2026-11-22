package de.gamingdoodz.radiofinder

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

enum class Kind { STATION, TRACK, SHOW }

/** Ein Eintrag in den Listen: ein Radiosender oder ein freies Lied. */
data class Entry(
    val id: String,          // "st:" Sender, sonst freie Lieder ("jm:" Jamendo, "au:" Audius, "ia:" Archiv)
    val kind: Kind,
    val title: String,       // was groß angezeigt wird (z. B. laufendes Lied)
    val subtitle: String,    // Sendername oder Künstler
    val url: String,
    val artwork: String,
    val name: String,        // fester Name (Sendername bzw. Liedtitel) für Favoriten
    val preview: Boolean = false, // nur 30-Sekunden-Vorschau (Charts von Deezer)
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("title", name)
        .put("subtitle", if (kind == Kind.STATION) "Internetradio" else subtitle)
        .put("feed", if (kind == Kind.SHOW) url else "")
        .put("url", url).put("artwork", artwork).put("name", name).put("preview", preview)

    fun toMediaItem(): MediaItem {
        val uri = Uri.parse(url)
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(if (kind == Kind.STATION) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putString("name", name); putBoolean("preview", preview) })
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
            kind = try { Kind.valueOf(o.optString("kind")) } catch (_: Exception) { Kind.STATION },
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            url = o.getString("url"),
            artwork = o.optString("artwork"),
            name = o.optString("name").ifBlank { o.optString("title") },
            preview = o.optBoolean("preview", false),
        )

        fun fromMediaItem(item: MediaItem): Entry? {
            val url = (item.localConfiguration?.uri ?: item.requestMetadata.mediaUri)?.toString() ?: return null
            val m = item.mediaMetadata
            val title = m.title?.toString() ?: ""
            return Entry(
                id = item.mediaId,
                kind = if (item.mediaId.startsWith("st:")) Kind.STATION else Kind.TRACK,
                title = title,
                subtitle = m.artist?.toString() ?: "",
                url = url,
                artwork = m.artworkUri?.toString() ?: "",
                name = m.extras?.getString("name") ?: title,
                preview = m.extras?.getBoolean("preview", false) ?: false,
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
    subtitle = artist.ifBlank { "Audius" } + " · Audius",
    url = streamUrl,
    artwork = artwork,
    name = title,
)

const val ROOT_ID = "root"
const val RECENT_ID = "recent"
const val STORIES_ID = "stories"
const val MOMENTS_ID = "moments"
const val FREEGENRES_ID = "freegenres"
const val LIVE_ID = "live"
const val CHARTS_LIVE_ID = "chartslive"
const val FAV_ID = "fav"
const val HITS_ID = "hits"
const val KIDS_ID = "kids"
const val FREE_ID = "free"
const val POPULAR_ID = "popular"
const val FORYOU_ID = "foryou"
const val RADIO_ID = "radio"
const val MUSIC_ID = "music"
const val STORY_CONT_ID = "storycont"
const val RADIOCHARTS_ID = "radiocharts"

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

/** Abspielbarer Eintrag, der den Hit-Mix startet (genreKey = null: Deutsche Charts). */
fun hitMixItem(genreKey: String?, title: String, sub: String = "Hit-Mix", group: String? = null): MediaItem {
    val key = genreKey ?: ""
    // Playlists bekommen ihren Namen aufs Bild, Mixe ein großes Symbol
    val label = if (key.startsWith("pl:") || key.startsWith("my:")) title.removePrefix("⚡ ") else ""
    return MediaItem.Builder()
        .setMediaId("hitmix:$key")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title.removePrefix("⚡ "))
                .setArtist(sub)
                .setArtworkUri(ArtProvider.tile("hitmix:$key", label))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .apply { group?.let { setExtras(android.os.Bundle().apply { putString(GROUP_HINT, it) }) } }
                .build()
        )
        .build()
}

const val GROUP_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"

/** In Android Auto unter einer Überschrift zusammenfassen. */
fun MediaItem.inGroup(group: String): MediaItem {
    val m = mediaMetadata
    val extras = android.os.Bundle(m.extras ?: android.os.Bundle()).apply { putString(GROUP_HINT, group) }
    return buildUpon().setMediaMetadata(m.buildUpon().setExtras(extras).build()).build()
}

/** Nachrichten-Eintrag (neueste Folge wird beim Abspielen geholt). */
fun newsItem(key: String, title: String, sub: String, group: String): MediaItem =
    MediaItem.Builder()
        .setMediaId("news:$key")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title).setArtist(sub)
                .setArtworkUri(ArtProvider.tile("news:$key"))
                .setIsPlayable(true).setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setExtras(android.os.Bundle().apply { putString(GROUP_HINT, group) })
                .build()
        )
        .build()
const val TRAFFIC_ID = "traffic"
