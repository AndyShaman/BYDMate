package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTuningTest {

    @Test
    fun `noiseScale at default liveliness 33 is about 0,4`() {
        assertEquals(0.398f, TtsTuning.noiseScale(33), 0.0001f)
    }

    @Test
    fun `noiseScale at liveliness 0 is the floor 0,2`() {
        assertEquals(0.2f, TtsTuning.noiseScale(0), 0.0001f)
    }

    @Test
    fun `noiseScale at liveliness 100 is the ceiling 0,8`() {
        assertEquals(0.8f, TtsTuning.noiseScale(100), 0.0001f)
    }

    @Test
    fun `noiseScale clamps below zero to the floor`() {
        assertEquals(0.2f, TtsTuning.noiseScale(-10), 0.0001f)
    }

    @Test
    fun `noiseScale clamps above 100 to the ceiling`() {
        assertEquals(0.8f, TtsTuning.noiseScale(150), 0.0001f)
    }

    @Test
    fun `noiseScaleW follows the same mapping as noiseScale`() {
        assertEquals(0.398f, TtsTuning.noiseScaleW(33), 0.0001f)
        assertEquals(0.2f, TtsTuning.noiseScaleW(0), 0.0001f)
        assertEquals(0.8f, TtsTuning.noiseScaleW(100), 0.0001f)
    }

    @Test
    fun `speed passes through values inside the allowed range`() {
        assertEquals(1.0f, TtsTuning.speed(1.0f), 0.0001f)
    }

    @Test
    fun `speed clamps below the minimum 0,7x`() {
        assertEquals(0.7f, TtsTuning.speed(0.3f), 0.0001f)
    }

    @Test
    fun `speed clamps above the maximum 1,4x`() {
        assertEquals(1.4f, TtsTuning.speed(2.0f), 0.0001f)
    }
}
