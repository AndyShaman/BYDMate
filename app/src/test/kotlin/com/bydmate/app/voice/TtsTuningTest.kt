package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTuningTest {

    // Linear map with a floor and a ceiling, and a clamped speed: every case of the old nine tests.
    @Test
    fun `noiseScale, noiseScaleW and speed map inside the range and clamp at both ends`() {
        val cases = listOf(
            Triple("noiseScale default 33", 0.398f, TtsTuning.noiseScale(33)),
            Triple("noiseScale floor 0", 0.2f, TtsTuning.noiseScale(0)),
            Triple("noiseScale ceiling 100", 0.8f, TtsTuning.noiseScale(100)),
            Triple("noiseScale below zero", 0.2f, TtsTuning.noiseScale(-10)),
            Triple("noiseScale above 100", 0.8f, TtsTuning.noiseScale(150)),
            Triple("noiseScaleW default 33", 0.398f, TtsTuning.noiseScaleW(33)),
            Triple("noiseScaleW floor 0", 0.2f, TtsTuning.noiseScaleW(0)),
            Triple("noiseScaleW ceiling 100", 0.8f, TtsTuning.noiseScaleW(100)),
            Triple("speed inside the range", 1.0f, TtsTuning.speed(1.0f)),
            Triple("speed below the minimum 0.7x", 0.7f, TtsTuning.speed(0.3f)),
            Triple("speed above the maximum 1.4x", 1.4f, TtsTuning.speed(2.0f)),
        )
        for ((label, expected, actual) in cases) assertEquals("case: $label", expected, actual, 0.0001f)
    }
}
