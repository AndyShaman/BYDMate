package com.bydmate.app.ui.automation

import com.bydmate.app.voice.VoicePhrase
import com.bydmate.app.voice.VoiceTriggerValidation
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTriggerCollisionTest {
    @Test fun `duplicate phrase across rules is OtherRule`() {
        val taken = mapOf(VoicePhrase.normalize("навигатор") to "Карта")
        assertEquals(VoiceTriggerValidation.Collision.OtherRule("Карта"),
            VoiceTriggerValidation.check("Навигатор", taken))
    }
    @Test fun `unique phrase is None`() {
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("поехали", mapOf(VoicePhrase.normalize("навигатор") to "Карта")))
    }
}
