package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTriggerValidationTest {
    @Test fun `blank phrase is Empty`() {
        assertEquals(VoiceTriggerValidation.Collision.Empty,
            VoiceTriggerValidation.check("  ", emptySet()))
    }
    @Test fun `phrase that maps to a built-in command is BuiltIn`() {
        // "открой окна" -> NluParser -> OPEN+WINDOW_ALL -> "车窗全开" (Command).
        // Verified in VoiceCatalog: VoiceCommandSpec(OPEN, WINDOW_ALL) { "车窗全开" }
        assertEquals(VoiceTriggerValidation.Collision.BuiltIn,
            VoiceTriggerValidation.check("открой окна", emptySet()))
    }
    @Test fun `phrase already used by another rule is OtherRule`() {
        val taken = setOf(VoicePhrase.normalize("навигатор"))
        assertEquals(VoiceTriggerValidation.Collision.OtherRule,
            VoiceTriggerValidation.check("навигатор", taken))
    }
    @Test fun `fresh custom phrase is None`() {
        // "поехали" has no action or device slot -> Unrecognized -> None
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("поехали", emptySet()))
    }
}
