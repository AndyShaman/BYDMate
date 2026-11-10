package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every row of the Russian golden corpus: a row marked `danger` must never open any
 * window or the sunroof wider than its expectation (or at all when the expectation is
 * UNRECOGNIZED), and no row may open wider than the share its utterance names.
 * Accuracy over the whole corpus: `overall_accuracy_does_not_regress` below.
 */
class RuCommandGoldenAccuracyTest {

    /** Accuracy the parser reached when the corpus was pinned (baseline before the
     *  Russian wave: 55.7 %, 146/262). Raise it with the corpus, never lower it. */
    private val minOverallAccuracy = 1.0

    @Test fun never_opens_more_than_asked() {
        val rows = RuGoldenCorpus.load()
        // The corpus has 681 rows today; a truncated data file must not pass silently.
        assertTrue("corpus has only ${rows.size} rows", rows.size >= 681)
        val failures = mutableListOf<String>()
        rows.forEach { row ->
            val actual = RuGoldenCorpus.actual(row)
            if (row.danger && RuGoldenCorpus.doesMoreThanExpected(row.expected, actual)) {
                failures += "\"${row.utterance}\" => $actual opens more than ${row.expected}"
            }
            if (RuGoldenCorpus.opensWiderThanSpoken(row.utterance, actual)) {
                failures += "\"${row.utterance}\" => $actual opens wider than it says"
            }
        }
        assertTrue("${failures.size} failing rows:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test fun corpus_is_big_and_covers_every_category() {
        val rows = RuGoldenCorpus.load()
        assertTrue(rows.size >= 150)
        val categories = rows.map { it.category }.toSet()
        assertTrue(categories.containsAll(listOf(
            "windows", "sunroof", "seats", "climate", "fan", "mirrors", "steering heat", "locks", "media", "misc",
        )))
    }

    @Test fun overall_accuracy_does_not_regress() {
        val rows = RuGoldenCorpus.load()
        val scores = RuGoldenCorpus.score(rows)
        scores.forEach { println("[golden] %-14s %3d/%3d %.1f%%".format(it.category, it.correct, it.total, it.accuracy * 100)) }
        val misses = rows.filter { RuGoldenCorpus.actual(it) != it.expected }
            .joinToString("\n") { "\"${it.utterance}\" => ${RuGoldenCorpus.actual(it)} (want ${it.expected})" }
        val overall = scores.last()
        assertTrue("accuracy ${overall.accuracy} < $minOverallAccuracy\n$misses", overall.accuracy >= minOverallAccuracy)
    }

    @Test fun spoken_share_skips_a_filler_between_the_preposition_and_the_number() {
        assertEquals(50, RuGoldenCorpus.spokenShare("открой окно на пожалуйста пятьдесят"))
    }

    /** Every row naming a share must give [RuGoldenCorpus.spokenShare] a non-null answer, so a
     *  gap in the oracle (a filler in the way, an unhandled word) fails loudly here instead of
     *  silently passing [never_opens_more_than_asked] by never checking the aperture at all. */
    @Test fun spoken_share_oracle_has_no_blind_spots() {
        // Words checked whole (not by substring), so "третье"/"третью" does not falsely
        // trigger on "треть"; "процент" alone stands for its three inflected forms.
        val wholeWordTriggers = setOf("наполовину", "половину", "треть", "четверть", "чуть", "немного", "полностью")
        val rows = RuGoldenCorpus.load()
        val blind = rows.filter { row ->
            val words = VoiceNormalizer.tokens(row.utterance)
            val named = words.any { it.startsWith("процент") || it in wholeWordTriggers } || "до конца" in row.utterance
            named && RuGoldenCorpus.spokenShare(row.utterance) == null
        }
        assertTrue(blind.joinToString("\n") { it.utterance }, blind.isEmpty())
    }
}
