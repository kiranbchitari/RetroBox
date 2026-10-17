package com.retrobox.tv.library

/**
 * Turns a ROM file name into a title that looks good on screen (display only; the file itself is never renamed):
 * `SUPER_MARIO_BROS_(U)_[!].nes` → "Super Mario Bros", `legend-of-zelda,-the.sfc` → "The Legend of Zelda".
 *
 * Rules: drop the extension and any (region) / [dump] tags; turn `_`, `-`, `+` (and dots in dotted names) into spaces; drop other
 * symbols; move a trailing ", The" to the front; fix casing of all-lower / all-upper words (small words like "of" stay lower,
 * roman numerals stay upper, a lone "n" becomes 'n'); leave mixed-case words such as "FireRed" or "FIFA" alone.
 */
object DisplayTitle {
    private val smallWords = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs", "v", "with")
    /** Well-known all-caps names that must not be turned into "Fifa" / "Nba". */
    private val acronyms = setOf("nba", "nfl", "nhl", "mlb", "fifa", "wwf", "wwe", "ufc", "pga", "tmnt", "rpg", "nes", "snes", "gba", "gbc", "dx", "hd", "ff", "usa", "uk", "dr", "mr", "ms", "jr", "sr", "ii")
    private val roman = setOf("ii", "iii", "iv", "vi", "vii", "viii", "ix", "xi", "xii", "xiii", "xiv", "xv", "xvi", "xvii", "xviii", "xix", "xx")
    private val tags = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
    private val keepChars = Regex("""[^\p{L}\p{N} '&,.:]""")

    fun fromFileName(fileName: String): String {
        var s = fileName.substringAfterLast('/')
        val ext = s.substringAfterLast('.', "")
        if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) s = s.dropLast(ext.length + 1)
        val original = s
        s = tags.replace(s, " ")
        s = s.replace('_', ' ').replace('-', ' ').replace('+', ' ')
        if (!s.trim().contains(' ') && s.count { it == '.' } >= 2) s = s.replace('.', ' ')   // super.mario.bros
        s = keepChars.replace(s, " ")
        s = s.replace(Regex("\\s*,\\s*"), ", ").replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', ' ')
        if (s.isEmpty()) return original.replace('_', ' ').trim().ifEmpty { fileName }
        // "Legend of Zelda, The"  ->  "The Legend of Zelda"
        Regex("^(.*), (the|a|an)$", RegexOption.IGNORE_CASE).find(s)?.let { s = it.groupValues[2] + " " + it.groupValues[1] }

        // Single letters (the lone "n" in "Chip n Dale", the "X" of "Mega Man X") do not decide whether a title is shouting.
        val letters = s.split(' ').filter { w -> w.count { it.isLetter() } >= 2 }.joinToString("").filter { it.isLetter() }
        val allUpper = letters.length >= 2 && letters.all { it.isUpperCase() }
        return s.split(' ').mapIndexed { i, w -> fixWord(w, i == 0, allUpper) }.joinToString(" ")
    }

    private fun fixWord(w: String, first: Boolean, allUpper: Boolean): String {
        if (w.isEmpty()) return w
        val core = w.trimEnd(',', '.', ':')
        val tail = w.substring(core.length)
        val out = when {
            core.equals("n", ignoreCase = true) -> "'n'"
            core.lowercase() in roman && (core == core.uppercase() || core == core.lowercase()) -> core.uppercase()
            core.lowercase() in acronyms && core == core.uppercase() -> if (core.lowercase() == "dr") "Dr" else core
            core == core.uppercase() && core.lowercase() in smallWords && !first -> core.lowercase()
            allUpper || core == core.lowercase() -> {
                val lower = core.lowercase()
                if (!first && lower in smallWords) lower else lower.replaceFirstChar { it.uppercase() }
            }
            else -> core
        }
        return out + tail
    }
}
