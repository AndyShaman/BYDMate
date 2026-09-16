package com.bydmate.app.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `accepted but silent field with a live poll value is reported`() {
        val lines = FidPushDiagnostics.pollVsPushLines(
            listOf(row("maxBatTemp", 1148190752, pollValue = 31)),
            sinceSubscribeMs = 12 * 60_000L,
        )

        assertEquals(listOf("poll≠push: maxBatTemp(1148190752) poll=31 events=0 since=12m"), lines)
    }

    @Test
    fun `fields that push, that were refused, or that the poll cannot read are skipped`() {
        val lines = FidPushDiagnostics.pollVsPushLines(
            listOf(
                row("bsdLeft", 1, events = 7),
                row("gear", 2, subscribed = false),
                row("seatHeatStatusDriver", 3, pollValue = null),
            ),
            sinceSubscribeMs = 12 * 60_000L,
        )

        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a fresh subscription is given a minute before anything is called silent`() {
        val rows = listOf(row("maxBatTemp", 1148190752))

        assertTrue(FidPushDiagnostics.pollVsPushLines(rows, sinceSubscribeMs = 59_000L).isEmpty())
        assertEquals(1, FidPushDiagnostics.pollVsPushLines(rows, sinceSubscribeMs = 60_000L).size)
    }

    @Test
    fun `the section is capped at twenty lines`() {
        val rows = (1..40).map { row("field$it", it) }

        assertEquals(20, FidPushDiagnostics.pollVsPushLines(rows, sinceSubscribeMs = 10 * 60_000L).size)
    }
}
