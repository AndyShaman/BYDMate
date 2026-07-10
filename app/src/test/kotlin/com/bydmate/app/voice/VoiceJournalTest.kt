package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceJournalTest {

    private fun entry(transcript: String) = VoiceJournalEntry(
        timestampMs = 0L,
        transcript = transcript,
        route = VoiceJournalEntry.Route.NLU,
        detail = transcript,
        outcome = VoiceJournalEntry.Outcome.OK,
    )

    @Test fun `add prepends newest first`() {
        val journal = VoiceJournal()
        journal.add(entry("first"))
        journal.add(entry("second"))

        val entries = journal.entries.value
        assertEquals(2, entries.size)
        assertEquals("second", entries[0].transcript)
        assertEquals("first", entries[1].transcript)
    }

    @Test fun `caps at MAX entries, dropping the oldest`() {
        val journal = VoiceJournal()
        repeat(VoiceJournal.MAX + 1) { i -> journal.add(entry("cmd$i")) }

        val entries = journal.entries.value
        assertEquals(VoiceJournal.MAX, entries.size)
        // Newest first: the very last added ("cmd50") is at index 0.
        assertEquals("cmd${VoiceJournal.MAX}", entries.first().transcript)
        // The oldest ("cmd0") was dropped.
        assertTrue(entries.none { it.transcript == "cmd0" })
    }

    @Test fun `clear empties the journal`() {
        val journal = VoiceJournal()
        journal.add(entry("x"))
        journal.clear()

        assertTrue(journal.entries.value.isEmpty())
    }
}
