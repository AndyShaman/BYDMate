package com.bydmate.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendTallyTest {

    @Test fun `the first send writes a line, then one per window with the counts since`() {
        val tally = SendTally(windowMs = 600_000L)

        assertEquals("ok=1 fail=0 over=first last_ms=120", tally.record(true, 120L, 0L))
        // A second a send for ten minutes, three of them failed.
        var line: String? = null
        for (t in 1..600) {
            val r = tally.record(t % 200 != 0, 90L, t * 1_000L)
            if (r != null) line = r
            if (t < 600) assertNull(r)
        }
        assertEquals("ok=597 fail=3 over=600s last_ms=90", line)
        assertNull(tally.record(true, 80L, 601_000L))
    }
}
