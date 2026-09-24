package com.bydmate.app.voice

/**
 * Repairs ASR slips before slot matching: an explicit map of slips seen in real
 * transcripts, then a one-edit fuzzy match of long unknown tokens against the device
 * and action words of [VoiceLexicon]. Pure and deterministic.
 */
object VoiceSpelling {

    /** Slips GigaAM made on real in-car audio ("подогрев роля", 2026-09-15). */
    private val SLIPS: Map<String, String> = mapOf(
        "роль" to "руль", "роля" to "руля", "ролю" to "рулю", "ролем" to "рулем",
    )

    private const val MIN_FUZZY_STEM = 5

    /** Comparatives ("громко" is one edit from "громче") change state on small talk: never guessed. */
    private val NOT_FUZZY: Set<ActionSlot> = setOf(
        ActionSlot.WARMER, ActionSlot.COOLER, ActionSlot.LOUDER, ActionSlot.QUIETER,
    )

    private class Target(val stem: String, val surface: String, val slots: Set<String>, val isAction: Boolean)

    private val targets: List<Target> by lazy {
        val bySurface = LinkedHashMap<String, Pair<MutableSet<String>, Boolean>>()
        for ((slot, words) in VoiceLexicon.actionWords()) {
            if (slot in NOT_FUZZY) continue
            words.forEach { bySurface.getOrPut(it) { mutableSetOf<String>() to true }.first.add("A:$slot") }
        }
        for ((slot, words) in VoiceLexicon.deviceWords()) {
            words.forEach { bySurface.getOrPut(it) { mutableSetOf<String>() to false }.first.add("D:$slot") }
        }
        bySurface.map { (surface, v) -> Target(VoiceStemmer.stem(surface), surface, v.first, v.second) }
            .filter { it.stem.length >= MIN_FUZZY_STEM }
    }

    private val knownStems: Set<String> by lazy {
        (VoiceLexicon.actionWords().values + VoiceLexicon.deviceWords().values)
            .flatten().mapTo(HashSet()) { VoiceStemmer.stem(it) }
    }

    fun correct(tokens: List<String>): List<String> = tokens.map { SLIPS[it] ?: fuzzy(it) ?: it }

    /** The lexicon word one edit away from [token], or null when there is none or the
     *  candidates disagree on the slot. An action differing only by an ending ("открыта"
     *  vs "открыть") is a state, not a slip, and is never guessed. */
    private fun fuzzy(token: String): String? {
        val stem = VoiceStemmer.stem(token)
        if (stem.length < MIN_FUZZY_STEM || stem in knownStems || token.any { it.isDigit() }) return null
        val hits = targets.filter { t ->
            withinOneEdit(stem, t.stem) && !(t.isAction && (stem.startsWith(t.stem) || t.stem.startsWith(stem)))
        }
        if (hits.isEmpty() || hits.any { it.slots != hits.first().slots }) return null
        return hits.first().surface
    }

    /** Levenshtein distance <= 1. */
    private fun withinOneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val (s, l) = if (a.length <= b.length) a to b else b to a
        var i = 0
        while (i < s.length && s[i] == l[i]) i++
        if (i == s.length) return true
        return if (s.length == l.length) s.substring(i + 1) == l.substring(i + 1)
        else s.substring(i) == l.substring(i + 1)
    }
}
