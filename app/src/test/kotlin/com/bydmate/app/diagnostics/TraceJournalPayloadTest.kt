package com.bydmate.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A subsystem journal line mirrored into the trace: first word the event, the rest its detail. */
class TraceJournalPayloadTest {

    @get:Rule val trace = TraceRecorder()

    private fun events() = trace.events().map { it.replace(Regex(" #\\d+"), "").replace(Regex(" +"), " ") }

    @Test fun `the first word is the event and the rest goes to d`() {
        Trace.journal(TraceArea.CLUSTER, "projection active pkg=x")
        Trace.journal(TraceArea.SPLIT, "closed")

        assertEquals(listOf("cluster projection d=active_pkg=x", "split closed"), events())
    }
}
