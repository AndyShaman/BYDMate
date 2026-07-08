package com.bydmate.app.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelManagerPathTest {
    @Test fun ru_url_points_to_small_ru_model() {
        assertTrue(VoiceModelManager.modelUrl(VoiceLang.RU).contains("small-ru"))
    }
    @Test fun en_url_points_to_small_en_model() {
        assertTrue(VoiceModelManager.modelUrl(VoiceLang.EN).contains("small-en"))
    }
    @Test fun sizes_are_reported() {
        assertTrue(VoiceModelManager.modelSizeMb(VoiceLang.RU) in 30..60)
        assertTrue(VoiceModelManager.modelSizeMb(VoiceLang.EN) in 30..60)
    }
}
