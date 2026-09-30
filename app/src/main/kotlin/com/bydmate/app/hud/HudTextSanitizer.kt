package com.bydmate.app.hud

import android.icu.text.Transliterator
import android.util.Log
import java.text.Normalizer

/**
 * OpenBYD 2.5's HudTextSanitizer (`.research/decompiled/openbyd-2.5/.../utils/HudTextSanitizer.java`):
 * the instrument's CAN road name takes no Cyrillic (the HUD of a Chinese car draws it blank or as
 * garbage), so each character goes through ICU's "Any-Latin; Latin-ASCII" on its own, Chinese
 * characters are kept, and the result is trimmed. A character ICU has no rule for passes as it is;
 * when ICU throws, the character only loses its diacritics. Only ways 2 and 3's CAN road name use
 * it; the SOME/IP frames keep the navigator's text.
 *
 * Unlike OpenBYD, Russian and Belarusian letters go through our own practical table first: ICU's
 * per-letter ISO 9 turns Щукина into "Sukina", which tells a driver nothing.
 */
internal object HudTextSanitizer {
    private const val TAG = "HudTextSanitizer"

    private val cyrillic = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'і' to "i", 'ў' to "w",
    )

    /** Belarusian writes ъ as an apostrophe; after a Cyrillic letter it is dropped like ъ. */
    private val apostrophes = setOf('’', 'ʼ')

    private val icu: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }
            .onFailure { Log.e(TAG, "Failed to initialize ICU Transliterator: ${it.message}") }
            .getOrNull()
    }

    private val combiningMarks = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun sanitize(text: String): String {
        if (text.isBlank()) return ""
        val transliterator = icu
        val out = StringBuilder()
        for ((i, c) in text.withIndex()) {
            val latin = cyrillic[c.lowercaseChar()]
            val one = c.toString()
            out.append(
                when {
                    isChinese(c) -> one
                    latin != null -> if (c.isUpperCase()) capital(latin, text, i) else latin
                    c in apostrophes && i > 0 && isCyrillic(text[i - 1]) -> ""
                    else -> transliterator?.let { runCatching { it.transliterate(one) }.getOrElse { fallback(one) } }
                        ?: fallback(one)
                }
            )
        }
        return out.toString().trim()
    }

    /** An all-caps word stays all caps (ЖУКОВА is ZHUKOVA); otherwise only the first letter is (Жукова is Zhukova). */
    private fun capital(latin: String, text: String, i: Int): String {
        val capsNeighbour = text.getOrNull(i - 1)?.isUpperCase() == true || text.getOrNull(i + 1)?.isUpperCase() == true
        return if (capsNeighbour) latin.uppercase() else latin.replaceFirstChar { it.uppercaseChar() }
    }

    private fun isCyrillic(c: Char): Boolean = Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC

    private fun fallback(text: String): String =
        combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").replace("ñ", "n").replace("Ñ", "N")

    /** CJK unified ideographs, extension A and the compatibility block, as OpenBYD counts them. */
    @Suppress("MagicNumber") // the Unicode block bounds
    private fun isChinese(c: Char): Boolean =
        c.code in 0x4E00 until 0xA000 || c.code in 0x3400 until 0x4DC0 || c.code in 0xF900 until 0xFB00
}
