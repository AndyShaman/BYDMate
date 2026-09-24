package com.bydmate.app.voice

import com.bydmate.app.agent.AgentToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceJournalTest {

    @get:Rule val tmp = TemporaryFolder()

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

    @Test fun `persisted entries survive a new instance with every field`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        val full = VoiceJournalEntry(
            timestampMs = 1_700_000_000_000L,
            transcript = "что с окнами",
            route = VoiceJournalEntry.Route.AGENT,
            detail = "Окна закрыты",
            outcome = VoiceJournalEntry.Outcome.ERROR,
            reason = "нет сети",
            tools = listOf(AgentToolOutcome("vehicle_control", true), AgentToolOutcome("read_state", false)),
            answer = "Окна закрыты",
            command = "windows_close_all",
            refusal = VoiceRefusal.UNRECOGNIZED,
            asrMs = 310L,
            dispatchMs = 1_200L,
        )
        val bare = entry("открой окна").copy(timestampMs = 5L, route = VoiceJournalEntry.Route.REFUSED)
        VoiceJournal(file).apply { add(bare); add(full) }

        assertEquals(listOf(full, bare), VoiceJournal(file).entries.value)
    }

    @Test fun `persisted journal is bounded to MAX`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        val journal = VoiceJournal(file)
        repeat(VoiceJournal.MAX + 5) { i -> journal.add(entry("cmd$i")) }

        val reloaded = VoiceJournal(file).entries.value
        assertEquals(VoiceJournal.MAX, reloaded.size)
        assertEquals("cmd${VoiceJournal.MAX + 4}", reloaded.first().transcript)
        assertEquals("cmd5", reloaded.last().transcript)
    }

    @Test fun `clear is persisted`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        VoiceJournal(file).apply { add(entry("x")); clear() }

        assertTrue(VoiceJournal(file).entries.value.isEmpty())
    }

    @Test fun `unreadable file starts empty and unknown entries are skipped`() {
        val broken = File(tmp.root, "broken.json").apply { writeText("{not json") }
        assertTrue(VoiceJournal(broken).entries.value.isEmpty())

        val mixed = File(tmp.root, "mixed.json")
        val good = VoiceJournal.toJson(entry("ok"))
        val future = VoiceJournal.toJson(entry("future")).put("route", "SOMETHING_NEW")
        mixed.writeText(org.json.JSONArray(listOf(future, good)).toString())
        assertEquals(listOf("ok"), VoiceJournal(mixed).entries.value.map { it.transcript })
    }
}
