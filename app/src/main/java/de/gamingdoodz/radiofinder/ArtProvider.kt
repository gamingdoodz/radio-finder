package de.gamingdoodz.radiofinder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Bilder für Android Auto: farbige Kacheln für Hit-Mixe, Playlists und Nachrichten.
 * Android Auto zeigt nur echte Bilder (keine Vektor-Symbole), deshalb werden sie hier gezeichnet.
 */
class ArtProvider : ContentProvider() {
    companion object {
        @Volatile var authority = "de.gamingdoodz.hitradar.art"

        fun init(c: android.content.Context) { authority = c.packageName + ".art" }

        /** key z. B. "hitmix:rock", "pl:Sommerhits", "news:ts100"; label wird auf Playlist-Kacheln geschrieben. */
        fun tile(key: String, label: String = ""): Uri = Uri.Builder().scheme("content").authority(authority)
            .appendPath("tile").appendPath("v2-" + Integer.toHexString((key + "|" + label).hashCode()) + ".png")
            .appendQueryParameter("k", key).appendQueryParameter("t", label).build()

        private val palette = listOf(
            "#FFEC4899" to "#FF9D174D", "#FF8B5CF6" to "#FF4C1D95", "#FF6366F1" to "#FF3730A3", "#FF14B8A6" to "#FF115E59",
            "#FFF97316" to "#FF9A3412", "#FF3B82F6" to "#FF1E3A8A", "#FFEAB308" to "#FFB45309", "#FFEF4444" to "#FF7F1D1D",
        )
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        val name = uri.lastPathSegment?.takeIf { it.matches(Regex("[\\w.-]+")) } ?: return null
        val dir = File(c.cacheDir, "tiles").apply { mkdirs() }
        val f = File(dir, name)
        if (!f.exists()) {
            val bmp = draw(c, uri.getQueryParameter("k").orEmpty(), uri.getQueryParameter("t").orEmpty())
            val tmp = File(dir, "$name.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun draw(c: android.content.Context, key: String, label: String): Bitmap {
        val size = 320
        val genreKey = key.removePrefix("hitmix:")
        val genre = Genres.all.firstOrNull { it.key == genreKey } ?: if (key == "hitmix:") Genres.all.firstOrNull { it.key == "charts" } else null
        val (c1, c2, icon) = when {
            genre != null -> Triple(genre.color1, genre.color2, genre.icon)
            genreKey == "favs" -> Triple("#FFEC4899", "#FF831843", R.drawable.ic_heart_white)
            genreKey == "radiocharts" || genreKey == "pl:radiocharts" -> Triple("#FFF97316", "#FF7C2D12", R.drawable.ic_star)
            genreKey == "pl:rising" -> Triple("#FF2EE6A8", "#FF0E7490", R.drawable.ic_fire)
            genreKey == "pl:community" -> Triple("#FF19B6D2", "#FF1E3A8A", R.drawable.ic_heart_white)
            key.startsWith("news:traffic") -> Triple("#FFF59E0B", "#FF78350F", R.drawable.ic_radar)
            key.startsWith("news:") -> Triple("#FF3B82F6", "#FF0F172A", R.drawable.ic_mic)
            else -> palette[Math.floorMod(key.hashCode(), palette.size)].let { (a, b) -> Triple(a, b, R.drawable.ic_note) }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), Color.parseColor(c1), Color.parseColor(c2), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        // Leuchten oben rechts + Radar-Ringe als Hitradar-Erkennungszeichen
        p.shader = RadialGradient(size * 0.85f, size * 0.15f, size * 0.7f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = 0x22FFFFFF
        for (r in listOf(70f, 120f, 170f)) cv.drawCircle(size * 0.82f, size * 0.82f, r, p)
        p.style = Paint.Style.FILL
        val withText = label.isNotBlank()
        val iconSize = if (withText) 84 else 140
        val d = c.getDrawable(icon)?.mutate()
        if (d != null) {
            d.setTint(Color.WHITE)
            val left = if (withText) 30 else (size - iconSize) / 2
            val top = if (withText) 30 else (size - iconSize) / 2
            d.setBounds(left, top, left + iconSize, top + iconSize)
            d.draw(cv)
        }
        if (withText) {
            p.color = Color.WHITE
            p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            p.textSize = 40f
            val words = label.split(" ")
            val lines = ArrayList<String>()
            var cur = ""
            for (w in words) {
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (p.measureText(t) > size - 60 && cur.isNotEmpty()) { lines.add(cur); cur = w } else cur = t
            }
            if (cur.isNotEmpty()) lines.add(cur)
            val shown = lines.take(3)
            var y = size - 34f - (shown.size - 1) * 46f
            for (l in shown) { cv.drawText(l, 30f, y, p); y += 46f }
        }
        return bmp
    }
}
