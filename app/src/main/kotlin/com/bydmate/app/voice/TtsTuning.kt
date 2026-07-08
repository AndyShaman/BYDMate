package com.bydmate.app.voice

/** Pure mapping from the Settings sliders to sherpa-onnx VITS synthesis parameters.
 *  Kept separate from [SherpaTtsEngine] so the mapping itself is unit-testable without
 *  the JNI-backed OfflineTts. */
object TtsTuning {
    // UI slider 0..100 -> VITS noiseScale 0.2..0.8 (default 33 -> 0.398 ≈ ns 0.4, выбран прослушкой 2026-07-07)
    fun noiseScale(liveliness: Int): Float = 0.2f + 0.006f * liveliness.coerceIn(0, 100)

    // noiseScaleW same mapping 0.2..0.8 (default 33 -> ≈0.4; LOW-чекпоинт стохастический, nsw реально влияет)
    fun noiseScaleW(liveliness: Int): Float = 0.2f + 0.006f * liveliness.coerceIn(0, 100)

    // UI "Скорость" 0.7x..1.4x passed to sherpa generate(speed=) directly (sherpa speed = 1/lengthScale)
    fun speed(rate: Float): Float = rate.coerceIn(0.7f, 1.4f)
}
