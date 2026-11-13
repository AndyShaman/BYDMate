package com.bydmate.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class BlindSpotShowLimitTest {

    private val L = BlindSpotSide.LEFT
    private val R = BlindSpotSide.RIGHT
    private val N = BlindSpotSide.NONE

    @Test fun `limit 0 never hides the camera`() {
        val limit = BlindSpotShowLimit()
        for (t in 0L..120_000L step 1_000L) {
            assertEquals("t=$t", L, limit.apply(L, L, t, 0))
        }
    }

    @Test fun `a show is cut once it lasted the limit and stays cut while the signal holds`() {
        val limit = BlindSpotShowLimit()
        assertEquals(L, limit.apply(L, L, 0L, 10))
        assertEquals(L, limit.apply(L, L, 9_900L, 10))
        assertEquals(N, limit.apply(L, L, 10_000L, 10))
        assertEquals(N, limit.apply(L, L, 30_000L, 10))
    }

    @Test fun `signal off after an expiry re-arms the next show`() {
        val limit = BlindSpotShowLimit()
        limit.apply(L, L, 0L, 10)
        assertEquals(N, limit.apply(L, L, 10_000L, 10))
        assertEquals(N, limit.apply(N, N, 20_000L, 10))
        assertEquals(L, limit.apply(L, L, 40_000L, 10))
        assertEquals(N, limit.apply(L, L, 50_000L, 10))
    }

    @Test fun `switching straight to the other side after an expiry shows it`() {
        val limit = BlindSpotShowLimit()
        limit.apply(L, L, 0L, 10)
        assertEquals(N, limit.apply(L, L, 10_000L, 10))
        assertEquals(R, limit.apply(R, R, 20_000L, 10))
        assertEquals(N, limit.apply(R, R, 30_000L, 10))
    }

    @Test fun `a show interrupted by the speed gate restarts its timer`() {
        val limit = BlindSpotShowLimit()
        for (t in 0L..6_000L step 1_000L) assertEquals(L, limit.apply(L, L, t, 10))
        assertEquals(N, limit.apply(N, L, 7_000L, 10))
        assertEquals(N, limit.apply(N, L, 8_000L - 1L, 10))
        assertEquals(L, limit.apply(L, L, 8_000L, 10))
        assertEquals(L, limit.apply(L, L, 15_000L, 10))
        assertEquals(N, limit.apply(L, L, 18_000L, 10))
    }

    @Test fun `an expired show stays cut when the speed gate flaps under a held signal`() {
        val limit = BlindSpotShowLimit()
        limit.apply(L, L, 0L, 10)
        assertEquals(N, limit.apply(L, L, 10_000L, 10))
        assertEquals(N, limit.apply(N, L, 12_000L, 10))
        assertEquals(N, limit.apply(L, L, 14_000L, 10))
        assertEquals(N, limit.apply(L, L, 16_000L, 10))
    }

    @Test fun `switching the limit off releases an expired show`() {
        val limit = BlindSpotShowLimit()
        limit.apply(L, L, 0L, 10)
        assertEquals(N, limit.apply(L, L, 10_000L, 10))
        assertEquals(L, limit.apply(L, L, 11_000L, 0))
    }

    @Test fun `a clock that jumps back does not throw or expire the show`() {
        val limit = BlindSpotShowLimit()
        assertEquals(L, limit.apply(L, L, 100_000L, 10))
        assertEquals(L, limit.apply(L, L, 50_000L, 10))
        assertEquals(L, limit.apply(L, L, 51_000L, 10))
    }

    @Test fun `nothing decided stays hidden`() {
        val limit = BlindSpotShowLimit()
        for (t in 0L..30_000L step 5_000L) {
            assertEquals("t=$t", N, limit.apply(N, N, t, 10))
        }
    }
}
