package com.bydmate.app.voice

import com.bydmate.app.agent.AgentToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class VoiceJournalDumpTest {
    private val t = 1_700_000_000_000L
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(t)

    private fun entry(
        route: VoiceJournalEntry.Route,
        outcome: VoiceJournalEntry.Outcome = VoiceJournalEntry.Outcome.OK,
    ) = VoiceJournalEntry(timestampMs = t, transcript = "x", route = route, detail = "", outcome = outcome)

    @Test fun `empty journal says so`() {
        assertEquals(listOf("(no voice sessions)"), VoiceJournalDump.lines(emptyList(), 20, 200))
    }

    @Test fun `nlu line carries every field`() {
        val e = VoiceJournalEntry(
            timestampMs = t, transcript = "закрой окна", route = VoiceJournalEntry.Route.NLU,
            detail = "", outcome = VoiceJournalEntry.Outcome.OK,
            command = "windows_close_all", asrMs = 320L, dispatchMs = 45L,
        )
        assertEquals(
            "$stamp route=nlu heard=\"закрой окна\" cmd=windows_close_all reason=- result=ok asr=320 dispatch=45",
            VoiceJournalDump.lines(listOf(e), 20, 200)[1],
        )
    }

    @Test fun `refused line shows the code, missing values as dashes, quotes and newlines flattened`() {
        val e = VoiceJournalEntry(
            timestampMs = t, transcript = "скажи \"привет\"\nещё", route = VoiceJournalEntry.Route.REFUSED,
            detail = "", outcome = VoiceJournalEntry.Outcome.NOT_UNDERSTOOD,
            reason = "Не понял", refusal = VoiceRefusal.UNRECOGNIZED,
        )
        assertEquals(
            "$stamp route=refused heard=\"скажи 'привет' ещё\" cmd=- reason=unrecognized" +
                " result=not_understood:\"Не понял\" asr=- dispatch=-",
            VoiceJournalDump.lines(listOf(e), 20, 200)[1],
        )
    }

    @Test fun `agent entry adds tools and clipped answer lines`() {
        val e = entry(VoiceJournalEntry.Route.AGENT).copy(
            tools = listOf(AgentToolOutcome("vehicle_control", true), AgentToolOutcome("read_state", false)),
            answer = "Окна закрыты, всё хорошо",
        )
        val lines = VoiceJournalDump.lines(listOf(e), 20, 10)
        assertEquals("  tools: vehicle_control:ok, read_state:err", lines[2])
        assertTrue(lines[3], lines[3].startsWith("  answer: Окна закр"))
        assertTrue(lines[3], lines[3].length < "  answer: Окна закрыты, всё хорошо".length)
    }

    @Test fun `counters cover the whole journal, lines only the limit`() {
        val entries = listOf(
            entry(VoiceJournalEntry.Route.NLU),
            entry(VoiceJournalEntry.Route.NLU, VoiceJournalEntry.Outcome.BLOCKED),
            entry(VoiceJournalEntry.Route.AUTOMATION),
            entry(VoiceJournalEntry.Route.AGENT),
            entry(VoiceJournalEntry.Route.AGENT, VoiceJournalEntry.Outcome.ERROR),
            entry(VoiceJournalEntry.Route.REFUSED, VoiceJournalEntry.Outcome.NOT_UNDERSTOOD),
        )
        val lines = VoiceJournalDump.lines(entries, 2, 200)
        assertEquals("sessions=6 nlu_ok=1 automation_ok=1 agent=2 refused=1", lines[0])
        assertEquals(3, lines.size)
    }
}
