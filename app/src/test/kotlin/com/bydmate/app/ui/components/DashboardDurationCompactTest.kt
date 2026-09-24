package com.bydmate.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The fallback a Главная trip row shows when the worded duration does not fit on one line. */
class DashboardDurationCompactTest {

    private val start = 1_700_000_000_000L
    private fun endAfter(minutes: Long, extraMs: Long = 0L) = start + minutes * 60_000L + extraMs

    @Test fun `hours and minutes are h colon mm`() {
        assertEquals("1:32", formatDurationCompact(start, endAfter(92)))
        assertEquals("10:05", formatDurationCompact(start, endAfter(605)))
    }

    @Test fun `a day and a hundred hours keep every hour digit`() {
        assertEquals("24:00", formatDurationCompact(start, endAfter(24 * 60)))
        assertEquals("100:00", formatDurationCompact(start, endAfter(100 * 60)))
    }

    @Test fun `under an hour keeps a zero hour`() {
        assertEquals("0:05", formatDurationCompact(start, endAfter(5)))
        assertEquals("0:00", formatDurationCompact(start, endAfter(0)))
    }

    @Test fun `seconds are dropped, not rounded up`() {
        assertEquals("0:23", formatDurationCompact(start, endAfter(23, extraMs = 59_000L)))
    }

    @Test fun `an end before the start shows zero`() {
        assertEquals("0:00", formatDurationCompact(start, start - 60_000L))
    }
}
