package com.bydmate.app.data.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionChannelStateTest {

    @Test
    fun `first valid event confirms channel`() {
        val ch = SubscriptionChannelState("blink", 1..6)
        assertFalse(ch.confirmed)
        assertTrue(ch.onEvent(2, nowMs = 1000))
        assertTrue(ch.confirmed)
        assertEquals(2, ch.lastValue)
        assertEquals(1, ch.eventCount)
    }

    @Test
    fun `out of range value marks channel sick and is not stored`() {
        val ch = SubscriptionChannelState("blink", 1..6)
        assertFalse(ch.onEvent(99, nowMs = 1000))
        assertTrue(ch.sick)
        assertNull(ch.lastValue)
        assertFalse(ch.confirmed)
    }

    @Test
    fun `poll comparison counts mismatches only when both sides present`() {
        val ch = SubscriptionChannelState("blink", 1..6)
        ch.onPollComparison(pollValue = 2)          // subscription silent so far — not a mismatch
        assertEquals(0, ch.mismatchCount)
        ch.onEvent(2, nowMs = 1000)
        ch.onPollComparison(pollValue = 2)          // agrees
        assertEquals(0, ch.mismatchCount)
        ch.onPollComparison(pollValue = 4)          // diverged
        assertEquals(1, ch.mismatchCount)
        ch.onPollComparison(pollValue = null)       // poll has no value — not a mismatch
        assertEquals(1, ch.mismatchCount)
    }
}
