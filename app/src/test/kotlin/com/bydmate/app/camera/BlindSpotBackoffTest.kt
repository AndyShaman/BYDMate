package com.bydmate.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Song DiLink 4.0 dump 2026-09-18 13:30 (3.17.3-test, code 477): "camera error: open failed;
// retry in 3 s" at 10.863, 11.134, 11.481, 11.832, 12.184 — the blinker was held the whole time.

class BlindSpotBackoffTest {

    @Test fun `held blinker keeps waiting through the backoff after a failure`() {
        val b = BlindSpotBackoff()
        b.request(BlindSpotSide.LEFT)
        b.fail(now = 10_863L, delayMs = 3_000L)
        // Teardown ran in between; the next ticks still see the same held side.
        b.request(BlindSpotSide.LEFT)
        assertTrue(b.blocked(11_134L))
        b.request(BlindSpotSide.LEFT)
        assertTrue(b.blocked(12_184L))
        assertFalse(b.blocked(13_863L))
    }

    @Test fun `releasing and pulling the blinker again cancels the backoff`() {
        val b = BlindSpotBackoff()
        b.request(BlindSpotSide.LEFT)
        b.fail(now = 1_000L, delayMs = 3_000L)
        b.request(BlindSpotSide.NONE)
        b.request(BlindSpotSide.LEFT)
        assertFalse(b.blocked(1_350L))
    }

    @Test fun `switching to the other side cancels the backoff`() {
        val b = BlindSpotBackoff()
        b.request(BlindSpotSide.LEFT)
        b.fail(now = 1_000L, delayMs = 3_000L)
        b.request(BlindSpotSide.RIGHT)
        assertFalse(b.blocked(1_350L))
    }

    @Test fun `blinker off does not cancel the backoff by itself`() {
        val b = BlindSpotBackoff()
        b.request(BlindSpotSide.LEFT)
        b.fail(now = 1_000L, delayMs = 3_000L)
        b.request(BlindSpotSide.NONE)
        assertTrue(b.blocked(1_350L))
    }
}
