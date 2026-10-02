package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The no-guidance trace: one line per streak of window reads without guidance while a route is
 *  guided, one "read again" per streak, the tree dump behind a 60 s floor and an edge-triggered
 *  "navigator window not found". */
class NoGuidanceTraceTest {

    private val trace = NoGuidanceTrace()

    @Test fun `the first no-guidance read of a guided route starts a streak, the next ones do not`() {
        assertTrue(trace.startsStreak(T0) { true })
        assertFalse(trace.startsStreak(T0 + 500) { true })
        assertFalse(trace.startsStreak(T0 + 9_000) { true })
    }

    @Test fun `no route, no streak`() {
        assertFalse(trace.startsStreak(T0) { false })
        assertTrue(trace.startsStreak(T0 + 500) { true })
    }

    @Test fun `an armed streak does not read the route state again`() {
        trace.startsStreak(T0) { true }
        assertFalse(trace.startsStreak(T0 + 500) { error("route state read inside a streak") })
    }

    @Test fun `a guidance read ends the streak once and says how long it lasted`() {
        trace.startsStreak(T0) { true }
        assertEquals(12_500L, trace.endStreak(T0 + 12_500))
        assertNull(trace.endStreak(T0 + 13_000))
    }

    @Test fun `a guidance read without a streak says nothing`() {
        assertNull(trace.endStreak(T0))
    }

    @Test fun `after a guidance read the next no-guidance read starts a new streak`() {
        trace.startsStreak(T0) { true }
        trace.endStreak(T0 + 3_000)
        assertTrue(trace.startsStreak(T0 + 6_000) { true })
    }

    @Test fun `the tree dump waits 60 s since the last one`() {
        assertTrue(trace.takeDump(T0))
        assertFalse(trace.takeDump(T0 + 59_999))
        assertTrue(trace.takeDump(T0 + 60_000))
    }

    @Test fun `a missing navigator window is reported on the edge only`() {
        assertTrue(trace.navigatorMissing())
        assertFalse(trace.navigatorMissing())
        trace.navigatorFound()
        assertTrue(trace.navigatorMissing())
    }

    @Test fun `reset starts a fresh episode`() {
        trace.startsStreak(T0) { true }
        trace.takeDump(T0)
        trace.navigatorMissing()
        trace.reset()
        assertNull(trace.endStreak(T0 + 1_000))
        assertTrue(trace.startsStreak(T0 + 1_000) { true })
        assertTrue(trace.takeDump(T0 + 1_000))
        assertTrue(trace.navigatorMissing())
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
