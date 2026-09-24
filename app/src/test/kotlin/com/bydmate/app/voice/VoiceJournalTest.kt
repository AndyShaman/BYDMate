package com.bydmate.app.voice

import com.bydmate.app.agent.AgentToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class VoiceJournalTest {

    @get:Rule val tmp = TemporaryFolder()

    // File work runs on the caller's thread, so a test sees every read and write at once.
    private val direct = Executor { it.run() }

    private fun journal(file: File) = VoiceJournal(file, direct)

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
        journal(file).apply { add(bare); add(full) }

        assertEquals(listOf(full, bare), journal(file).entries.value)
    }

    @Test fun `persisted journal is bounded to MAX`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        val journal = journal(file)
        repeat(VoiceJournal.MAX + 5) { i -> journal.add(entry("cmd$i")) }

        val reloaded = journal(file).entries.value
        assertEquals(VoiceJournal.MAX, reloaded.size)
        assertEquals("cmd${VoiceJournal.MAX + 4}", reloaded.first().transcript)
        assertEquals("cmd5", reloaded.last().transcript)
    }

    @Test fun `clear is persisted`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        journal(file).apply { add(entry("x")); clear() }

        assertTrue(journal(file).entries.value.isEmpty())
    }

    @Test fun `unreadable file starts empty and unknown entries are skipped`() {
        val broken = File(tmp.root, "broken.json").apply { writeText("{not json") }
        assertTrue(journal(broken).entries.value.isEmpty())

        val mixed = File(tmp.root, "mixed.json")
        val good = VoiceJournal.toJson(entry("ok"))
        val future = VoiceJournal.toJson(entry("future")).put("route", "SOMETHING_NEW")
        mixed.writeText(org.json.JSONArray(listOf(future, good)).toString())
        assertEquals(listOf("ok"), journal(mixed).entries.value.map { it.transcript })
    }

    @Test fun `a file over the size bound is not read and the journal starts empty`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        val one = VoiceJournal.toJson(entry("old")).toString()
        val padded = "[" + one + " ".repeat(VoiceJournal.MAX_FILE_BYTES.toInt()) + "]"
        file.writeText(padded)
        assertTrue(file.length() > VoiceJournal.MAX_FILE_BYTES)

        val journal = journal(file)
        assertTrue(journal.entries.value.isEmpty())
        journal.add(entry("new"))
        assertEquals(listOf("new"), journal(file).entries.value.map { it.transcript })
    }

    @Test fun `long transcript, command and reason are cut to the field bound in memory and on disk`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        val long = "а".repeat(VoiceJournal.MAX_FIELD_CHARS + 100)
        journal(file).add(entry(long).copy(command = long, reason = long, answer = "ответ"))

        val e = journal(file).entries.value.single()
        assertEquals(VoiceJournal.MAX_FIELD_CHARS, e.transcript.length)
        assertEquals(VoiceJournal.MAX_FIELD_CHARS, e.command?.length)
        assertEquals(VoiceJournal.MAX_FIELD_CHARS, e.reason?.length)
        assertEquals("ответ", e.answer)
    }

    @Test fun `fifty long agent answers are cut, persisted and read back`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        val answer = "ж".repeat(3_000)
        val long = "а".repeat(VoiceJournal.MAX_FIELD_CHARS)
        val writer = journal(file)
        repeat(VoiceJournal.MAX) {
            writer.add(entry("$it $long").copy(route = VoiceJournalEntry.Route.AGENT, detail = answer, answer = answer,
                command = long, reason = long))
        }
        assertTrue(file.length() <= VoiceJournal.MAX_FILE_BYTES)

        val back = journal(file).entries.value
        assertEquals(VoiceJournal.MAX, back.size)
        assertTrue(back.all { it.detail.length == VoiceJournal.MAX_TEXT_CHARS && it.answer?.length == VoiceJournal.MAX_TEXT_CHARS })
    }

    @Test fun `stored fields over the bound are cut on load`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        val long = "а".repeat(VoiceJournal.MAX_TEXT_CHARS + 100)
        file.writeText(org.json.JSONArray(listOf(VoiceJournal.toJson(entry(long).copy(answer = long)))).toString())

        val e = journal(file).entries.value.single()
        assertEquals(VoiceJournal.MAX_FIELD_CHARS, e.transcript.length)
        assertEquals(VoiceJournal.MAX_TEXT_CHARS, e.detail.length)
        assertEquals(VoiceJournal.MAX_TEXT_CHARS, e.answer?.length)
    }

    @Test fun `writes wait for the queued read and coalesce into the latest list`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        journal(file).add(entry("stored"))
        val queued = mutableListOf<Runnable>()
        val journal = VoiceJournal(file, Executor { queued += it })

        journal.add(entry("a"))
        journal.add(entry("b"))
        journal.clear()
        journal.add(entry("c"))
        // Nothing touched the disk on the caller's thread: one read, one coalesced write.
        assertEquals(2, queued.size)
        assertEquals(listOf("stored"), journal(file).entries.value.map { it.transcript })

        queued.forEach { it.run() }
        assertEquals(listOf("c"), journal.entries.value.map { it.transcript })
        assertEquals(listOf("c"), journal(file).entries.value.map { it.transcript })
    }

    @Test fun `sessions added before the read finishes stay first`() {
        val file = File(tmp.root, VoiceJournal.FILE_NAME)
        journal(file).add(entry("stored"))
        val queued = mutableListOf<Runnable>()
        val journal = VoiceJournal(file, Executor { queued += it })

        journal.add(entry("fresh"))
        queued.toList().forEach { it.run() }

        assertEquals(listOf("fresh", "stored"), journal.entries.value.map { it.transcript })
        assertEquals(listOf("fresh", "stored"), journal(file).entries.value.map { it.transcript })
    }
}
