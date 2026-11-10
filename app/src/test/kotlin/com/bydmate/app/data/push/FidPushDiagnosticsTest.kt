package com.bydmate.app.data.push

import org.junit.Assert.assertEquals
import org.junit.Test

class FidPushDiagnosticsTest {

    private fun row(
        field: String,
        fid: Int,
        subscribed: Boolean = true,
        events: Int = 0,
        pollValue: Any? = 31,
    ) = FidPushDiagnostics.PollPushRow(field, fid, subscribed, events, pollValue)

    @Test
    fun `the section is capped at twenty lines`() {
        val rows = (1..40).map { row("field$it", it) }

        assertEquals(20, FidPushDiagnostics.pollVsPushLines(rows, sinceSubscribeMs = 10 * 60_000L).size)
    }
}
