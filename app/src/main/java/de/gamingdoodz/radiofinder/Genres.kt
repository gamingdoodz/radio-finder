package de.gamingdoodz.radiofinder

data class Genre(
    val key: String,
    val name: String,
    val color1: String,
    val color2: String,
    val icon: Int,
    val radioTag: String,
    val deezerGenre: Int?,
    val jamendoTag: String?,
    val playlist: String? = null, // Deezer-Playlist (Suche nach Name) statt Genre-Charts
)

object Genres {
    val all = listOf(
        Genre("xmas", "Weihnachten", "#FFEF4444", "#FF15803D", R.drawable.ic_star, "christmas", null, "christmas", "Weihnachtslieder Klassiker"),
        Genre("charts", "Charts", "#FFEAB308", "#FFB45309", R.drawable.ic_star, "charts", 0, "pop"),
        Genre("pop", "Pop & Deutsch-Pop", "#FFEC4899", "#FF9D174D", R.drawable.ic_mic, "pop", 132, "pop"),
        Genre("rap", "Rap & Hip-Hop", "#FF8B5CF6", "#FF4C1D95", R.drawable.ic_fire, "hip hop", 116, "hiphop"),
        Genre("dance", "Dance & Electronic", "#FF6366F1", "#FF3730A3", R.drawable.ic_eq, "dance", 113, "electronic"),
        Genre("rock", "Rock & Alternative", "#FFEF4444", "#FF7F1D1D", R.drawable.ic_album, "rock", 152, "rock"),
        Genre("schlager", "Schlager", "#FFF97316", "#FF9A3412", R.drawable.ic_heart_white, "schlager", null, null, "Schlager Hits"),
        Genre("party", "Party & Après-Ski", "#FFD946EF", "#FF86198F", R.drawable.ic_bolt, "party", null, null, "Party Hits"),
        Genre("80s", "80er", "#FF14B8A6", "#FF115E59", R.drawable.ic_album, "80s", null, null, "80er Hits"),
        Genre("90s", "90er", "#FF3B82F6", "#FF1E3A8A", R.drawable.ic_album, "90s", null, null, "90er Hits"),
        Genre("2000s", "2000er", "#FFA855F7", "#FF581C87", R.drawable.ic_album, "2000s", null, null, "2000er Hits"),
        Genre("chill", "Chill & Entspannen", "#FF06B6D4", "#FF155E75", R.drawable.ic_sun, "chillout", null, "lounge", "Chill Hits"),
        Genre("kids", "Kindermusik", "#FF22C55E", "#FF166534", R.drawable.ic_kids, "kinder", 95, "kids"),
    )
}
