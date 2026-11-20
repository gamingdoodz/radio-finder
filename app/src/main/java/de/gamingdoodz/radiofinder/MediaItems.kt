package de.gamingdoodz.radiofinder

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

const val ROOT_ID = "root"
const val HITS_ID = "hits"
const val POPULAR_ID = "popular"

fun Station.toMediaItem(songTitle: String? = null): MediaItem {
    val uri = Uri.parse(url)
    val meta = MediaMetadata.Builder()
        .setTitle(songTitle ?: name)
        .setArtist(if (songTitle != null) name else "Internetradio")
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
    if (favicon.isNotBlank()) meta.setArtworkUri(Uri.parse(favicon))
    return MediaItem.Builder()
        .setMediaId("st:$id")
        .setUri(uri)
        .setMediaMetadata(meta.build())
        // Die Adresse wird hier mitgeschickt, damit der Player sie auch bekommt,
        // wenn der Eintrag von der App-Oberfläche oder Android Auto kommt.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .build()
}

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
