package com.bydmate.app.data.autoservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogThrottleTest {
    @Test
    fun `first log per key passes, repeat inside window is suppressed, next window passes`() {
        val t = LogThrottle(windowMs = 1_000L)
        assertTrue(t.shouldLog("a", nowMs = 0L))
        assertFalse(t.shouldLog("a", nowMs = 500L))
        assertTrue(t.shouldLog("a", nowMs = 1_500L))
    }

    @Test
    fun `keys are independent`() {
        val t = LogThrottle(windowMs = 1_000L)
        assertTrue(t.shouldLog("a", nowMs = 0L))
        assertTrue(t.shouldLog("b", nowMs = 0L))
    }
}
