package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuStressMarkerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Writes stress.tsv bytewise-sorted by wordform, like the real archive. */
    private fun writeDict(entries: List<Pair<String, Int>>): File {
        val file = tmp.newFile("stress.tsv")
        val sorted = entries.sortedBy { it.first }
        file.writeText(sorted.joinToString("\n") { "${it.first}\t${it.second}" }, Charsets.UTF_8)
        return file
    }

    private fun awaitLoad(marker: RuStressMarker) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && !marker.isLoadedForTest()) Thread.sleep(10)
    }

    private fun awaitAttempt(marker: RuStressMarker) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && !marker.loadAttemptedForTest()) Thread.sleep(10)
    }

    // --- (a) dictionary word gets '+' before the correct vowel, case and punctuation preserved ---

    @Test
    fun `mark inserts plus before the dictionary stressed vowel preserving case and punctuation`() {
        val dict = writeDict(listOf("малина" to 2, "слово" to 1))
        val marker = RuStressMarker { dict }
        marker.preload()
        awaitLoad(marker)
        assertEquals("Мал+ина!", marker.mark("Малина!"))
        assertEquals("сл+ово", marker.mark("слово"))
    }

    @Test
    fun `mark uppercases the dictionary stressed vowel for Supertonic style`() {
        val dict = writeDict(listOf("позвонит" to 3, "договор" to 3))
        val marker = RuStressMarker { dict }
        marker.preload()
        awaitLoad(marker)
        assertEquals("позвонИт", marker.mark("позвонит", RuStressMarker.Style.UPPERCASE))
        assertEquals("договОр", marker.mark("договор", RuStressMarker.Style.UPPERCASE))
    }

    @Test
    fun `uppercase style keeps skipped and already-marked words unchanged`() {
        val dict = writeDict(listOf("позвонит" to 3, "ёлка" to 1, "кот" to 1))
        val marker = RuStressMarker { dict }
        marker.preload()
        awaitLoad(marker)
        assertEquals("ёлка", marker.mark("ёлка", RuStressMarker.Style.UPPERCASE))
        assertEquals("кот", marker.mark("кот", RuStressMarker.Style.UPPERCASE))
        assertEquals("позвонИт", marker.mark("позвонИт", RuStressMarker.Style.UPPERCASE))
    }

    // --- (b) yo-words, single-vowel words, out-of-dictionary words, latin/digits unchanged ---

    @Test
    fun `mark leaves yo words, single-vowel words, unknown words and non-cyrillic runs unchanged`() {
        val dict = writeDict(listOf("малина" to 2))
        val marker = RuStressMarker { dict }
        marker.preload()
        awaitLoad(marker)
        assertEquals("ёлка", marker.mark("ёлка")) // contains ё -- always skipped
        assertEquals("Ёлка", marker.mark("Ёлка")) // uppercase Ё too
        assertEquals("кот", marker.mark("кот")) // single vowel
        assertEquals("неизвестноеслово", marker.mark("неизвестноеслово")) // not in dictionary
        assertEquals("Tesla 3000", marker.mark("Tesla 3000")) // latin/digits pass through
        assertEquals("мал+ина+", marker.mark("мал+ина+")) // already marked -- idempotent
    }

    // --- (c) mark() before preload()/with a missing file returns text unchanged ---

    @Test
    fun `mark returns text unchanged before preload finishes`() {
        val dict = writeDict(listOf("малина" to 2))
        val marker = RuStressMarker { dict }
        // preload() deliberately not called
        assertEquals("Малина", marker.mark("Малина"))
    }

    @Test
    fun `mark returns text unchanged when the dictionary file is missing`() {
        val marker = RuStressMarker { null }
        marker.preload()
        awaitAttempt(marker)
        assertEquals("Малина", marker.mark("Малина"))
    }

    @Test
    fun `preload can retry after the dictionary appears later`() {
        val file = File(tmp.root, "stress.tsv")
        val marker = RuStressMarker { file.takeIf { it.isFile } }
        marker.preload()
        awaitAttempt(marker)
        assertEquals("Малина", marker.mark("Малина"))

        file.writeText("малина\t2", Charsets.UTF_8)
        marker.preload()
        awaitLoad(marker)

        assertEquals("Мал+ина", marker.mark("Малина"))
    }

    // --- (d) binary search finds the first and last line of the dictionary ---

    @Test
    fun `find locates the first and last dictionary line`() {
        val entries = listOf("яблоко" to 2, "апельсин" to 3, "банан" to 1, "вишня" to 2)
        val sorted = entries.sortedBy { it.first }
        val bytes = sorted.joinToString("\n") { "${it.first}\t${it.second}" }.toByteArray(Charsets.UTF_8)
        val lineStarts = RuStressMarker.lineStarts(bytes)
        assertEquals(entries.size, lineStarts.size)
        assertEquals(sorted.first().second, RuStressMarker.find(bytes, lineStarts, sorted.first().first))
        assertEquals(sorted.last().second, RuStressMarker.find(bytes, lineStarts, sorted.last().first))
        assertEquals(-1, RuStressMarker.find(bytes, lineStarts, "нетслова"))
    }
}
