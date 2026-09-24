package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControllerLogLineTest {

    @Test fun `every value stays on one line with quotes escaped`() {
        val e = VoiceJournalEntry(
            transcript = "скажи \"привет\"\nещё", route = VoiceJournalEntry.Route.AUTOMATION, detail = "",
            outcome = VoiceJournalEntry.Outcome.OK, command = "Дом\n\"вечер\"\r\n  ночь", refusal = null,
        )
        assertEquals(
            "heard=\"скажи 'привет' ещё\" route=automation cmd=Дом 'вечер' ночь reason=-",
            VoiceController.logLine(e),
        )
    }

    @Test fun `the detail line next to it is flattened and capped too`() {
        assertEquals("automation fired: heard='открой окно' rule=Дом",
            VoiceController.logDetail("automation fired: heard=\"открой\n окно\" rule=Дом"))
        val line = VoiceController.logDetail("скажи \"x\"\n" + "а".repeat(1_000))
        assertTrue(line, '\n' !in line && '"' !in line)
        assertTrue(line.length.toString(), line.length < 600)
    }

    @Test fun `the busy drop line is flattened and capped`() {
        assertEquals("Utterance dropped while busy: открой 'окно'", VoiceController.busyDropLine("открой\n\"окно\""))
        val line = VoiceController.busyDropLine("а".repeat(1_000))
        assertTrue(line.length.toString(), line.length < 600)
    }

    @Test fun `long values are capped`() {
        val long = "а".repeat(1_000)
        val line = VoiceController.logLine(VoiceJournalEntry(transcript = long, route = VoiceJournalEntry.Route.NLU,
            detail = "", outcome = VoiceJournalEntry.Outcome.OK, command = long))
        assertTrue(line.length.toString(), line.length < 500)
        assertTrue(line, '\n' !in line)
    }
}
