package de.gamingdoodz.radiofinder

/** Liedtitel aus dem Radio säubern (z. B. "Don&#8217;t" -> "Don’t"). */
object Text {
    private val entity = Regex("&(#\\d+|#x[0-9a-fA-F]+|amp|quot|apos|lt|gt|nbsp);")

    fun clean(raw: String): String {
        // "Eminem_Lose_Yourself" -> "Eminem Lose Yourself"
        val s = if (raw.contains('_')) raw.replace(Regex("_+"), " ").replace(Regex("\\s+"), " ") else raw
        if (!s.contains('&')) return s.trim()
        return entity.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                e == "amp" -> "&"; e == "quot" -> "\""; e == "apos" -> "'"
                e == "lt" -> "<"; e == "gt" -> ">"; else -> " "
            }
        }.trim()
    }
}
