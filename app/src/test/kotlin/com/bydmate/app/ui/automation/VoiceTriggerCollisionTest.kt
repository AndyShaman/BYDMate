package com.bydmate.app.ui.automation

import com.bydmate.app.voice.VoicePhrase
import com.bydmate.app.voice.VoiceTriggerValidation
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTriggerCollisionTest {
    @Test fun `duplicate phrase across rules is OtherRule`() {
        val taken = setOf(VoicePhrase.normalize("навигатор"))
        assertEquals(VoiceTriggerValidation.Collision.OtherRule,
            VoiceTriggerValidation.check("Навигатор", taken))
    }
    @Test fun `unique phrase is None`() {
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("поехали", setOf(VoicePhrase.normalize("навигатор"))))
    }
}
