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

    @Test fun `long values are capped`() {
        val long = "а".repeat(1_000)
        val line = VoiceController.logLine(VoiceJournalEntry(transcript = long, route = VoiceJournalEntry.Route.NLU,
            detail = "", outcome = VoiceJournalEntry.Outcome.OK, command = long))
        assertTrue(line.length.toString(), line.length < 500)
        assertTrue(line, '\n' !in line)
    }
}
