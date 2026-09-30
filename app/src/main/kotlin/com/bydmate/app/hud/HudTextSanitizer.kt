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
 */
internal object HudTextSanitizer {
    private const val TAG = "HudTextSanitizer"

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
        for (c in text) {
            if (isChinese(c)) {
                out.append(c)
                continue
            }
            val one = c.toString()
            out.append(
                transliterator?.let { runCatching { it.transliterate(one) }.getOrElse { fallback(one) } } ?: fallback(one)
            )
        }
        return out.toString().trim()
    }

    private fun fallback(text: String): String =
        combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").replace("ñ", "n").replace("Ñ", "N")

    /** CJK unified ideographs, extension A and the compatibility block, as OpenBYD counts them. */
    @Suppress("MagicNumber") // the Unicode block bounds
    private fun isChinese(c: Char): Boolean =
        c.code in 0x4E00 until 0xA000 || c.code in 0x3400 until 0x4DC0 || c.code in 0xF900 until 0xFB00
}
