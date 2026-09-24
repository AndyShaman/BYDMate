package com.bydmate.app.voice

/**
 * Repairs ASR slips before slot matching: an explicit map of slips seen in real
 * transcripts, then a one-edit fuzzy match of long unknown tokens against the DEVICE
 * words of [VoiceLexicon]. Verbs are never guessed: "откроем" is one edit from "открыть"
 * but talks about later, so only the explicit slip map may repair an action word.
 * Pure and deterministic.
 */
object VoiceSpelling {

    /** Slips GigaAM made on real in-car audio ("подогрев роля", 2026-09-15). */
    private val SLIPS: Map<String, String> = mapOf(
        "роль" to "руль", "роля" to "руля", "ролю" to "рулю", "ролем" to "рулем",
    )

    private const val MIN_FUZZY_STEM = 5

    private class Target(val stem: String, val surface: String, val slots: Set<DeviceSlot>)

    private val targets: List<Target> by lazy {
        val bySurface = LinkedHashMap<String, MutableSet<DeviceSlot>>()
        for ((slot, words) in VoiceLexicon.deviceWords()) {
            words.forEach { bySurface.getOrPut(it) { mutableSetOf() }.add(slot) }
        }
        bySurface.map { (surface, slots) -> Target(VoiceStemmer.stem(surface), surface, slots) }
            .filter { it.stem.length >= MIN_FUZZY_STEM }
    }

    private val knownStems: Set<String> by lazy {
        (VoiceLexicon.actionWords().values + VoiceLexicon.deviceWords().values)
            .flatten().mapTo(HashSet()) { VoiceStemmer.stem(it) }
    }

    fun correct(tokens: List<String>): List<String> = tokens.map { SLIPS[it] ?: fuzzy(it) ?: it }

    /** The device word one edit away from [token], or null when there is none or the
     *  candidates disagree on the slot. */
    private fun fuzzy(token: String): String? {
        val stem = VoiceStemmer.stem(token)
        if (stem.length < MIN_FUZZY_STEM || stem in knownStems || token.any { it.isDigit() }) return null
        val hits = targets.filter { withinOneEdit(stem, it.stem) }
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
