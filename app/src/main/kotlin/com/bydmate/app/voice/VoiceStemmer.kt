package com.bydmate.app.voice

/** Light, deterministic suffix stripper for ru/en command tokens. Not a full
 *  morphological stemmer — just enough to unify common inflections so the
 *  lexicon match in [NluParser] tolerates case/number endings. */
object VoiceStemmer {
    // Longest-first so multi-char endings win over their suffixes.
    private val SUFFIXES: List<String> = listOf(
        // ru
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими",
        "ть", "ой", "ей", "ом", "ем", "ах", "ях", "ую", "юю",
        "ая", "яя", "ое", "ее", "ые", "ие", "ам", "ям",
        // en
        "ing", "es", "s",
        // ru single-char
        "а", "я", "о", "е", "ы", "и", "у", "ю", "ь", "й",
    ).sortedByDescending { it.length }

    private const val MIN_STEM = 3

    fun stem(token: String): String {
        val t = token.lowercase().trim()
        for (suf in SUFFIXES) {
            if (t.length - suf.length >= MIN_STEM && t.endsWith(suf)) {
                return t.removeSuffix(suf)
            }
        }
        return t
    }
}
