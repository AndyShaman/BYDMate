package com.bydmate.app.hud

/**
 * The script of a road name on its way to the glass (#269, #294: glass drawing «иероглифы»), for
 * the trace: the class alone, never the text. Digits, punctuation and spaces belong to no script;
 * a name made only of them reads as latin (plain ASCII draws like it). A replacement character,
 * a private-use or unassigned code point and any script but Latin and Cyrillic count as other.
 */
internal object HudRoadScript {
    const val LATIN = "latin"
    const val CYRILLIC = "cyrillic"
    const val MIXED = "mixed"
    const val EMPTY = "empty"
    const val OTHER = "other"

    fun classify(text: String): String {
        if (text.isBlank()) return EMPTY
        val found = HashSet<String>()
        text.codePoints().forEach { cp -> scriptOf(cp)?.let { found += it } }
        return when (found.size) {
            0 -> LATIN
            1 -> found.single()
            else -> MIXED
        }
    }

    /** One code point's class; null for digits, punctuation, spaces and combining marks. */
    private fun scriptOf(cp: Int): String? {
        val type = Character.getType(cp)
        if (cp == REPLACEMENT || type == PRIVATE_USE || type == UNASSIGNED) return OTHER
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN -> LATIN
            Character.UnicodeScript.CYRILLIC -> CYRILLIC
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> null
            else -> OTHER
        }
    }

    private const val REPLACEMENT = 0xFFFD
    private val PRIVATE_USE = Character.PRIVATE_USE.toInt()
    private val UNASSIGNED = Character.UNASSIGNED.toInt()
}
