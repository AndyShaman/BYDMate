package com.bydmate.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every row of the Russian golden corpus: a row marked `danger` must never open any
 * window or the sunroof wider than its expectation (or at all when the expectation is
 * UNRECOGNIZED). Accuracy over the whole corpus lives in [RuCommandGoldenAccuracyTest].
 */
@RunWith(Parameterized::class)
class RuCommandGoldenTest(private val row: RuGoldenCorpus.Row) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun rows(): List<RuGoldenCorpus.Row> = RuGoldenCorpus.load()
    }

    @Test fun never_opens_more_than_asked() {
        val actual = RuGoldenCorpus.actual(row)
        println("[golden] ${row.category} | ${row.utterance} => $actual (want ${row.expected})")
        if (row.danger) {
            assertFalse(
                "\"${row.utterance}\" => $actual opens more than ${row.expected}",
                RuGoldenCorpus.doesMoreThanExpected(row.expected, actual),
            )
        }
    }
}

class RuCommandGoldenAccuracyTest {

    /** Accuracy the parser reached when the corpus was pinned (baseline before the
     *  Russian wave: 55.7 %, 146/262). Raise it with the corpus, never lower it. */
    private val minOverallAccuracy = 1.0

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
}
