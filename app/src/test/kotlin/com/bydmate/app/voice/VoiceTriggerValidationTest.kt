package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTriggerValidationTest {
    @Test fun `blank phrase is Empty`() {
        assertEquals(VoiceTriggerValidation.Collision.Empty,
            VoiceTriggerValidation.check("  ", emptyMap()))
    }
    @Test fun `phrase made of fillers only is Empty`() {
        assertEquals(VoiceTriggerValidation.Collision.Empty,
            VoiceTriggerValidation.check("эй, пожалуйста", emptyMap()))
    }
    @Test fun `phrase the built-in parser understands is allowed`() {
        // "открой окна" is a built-in command, but automations resolve first, so the rule wins.
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("открой окна", emptyMap()))
    }
    @Test fun `phrase already used by another rule is OtherRule with its name`() {
        val taken = mapOf(VoicePhrase.normalize("навигатор") to "Карта")
        assertEquals(VoiceTriggerValidation.Collision.OtherRule("Карта"),
            VoiceTriggerValidation.check("навигатор", taken))
    }
    @Test fun `user phrase of a built-in command is UserCommandPhrase with the command name`() {
        val userPhrases = mapOf(VoicePhrase.normalize("свежий воздух") to "все окна на проветривание")
        assertEquals(VoiceTriggerValidation.Collision.UserCommandPhrase("все окна на проветривание"),
            VoiceTriggerValidation.check("Свежий воздух!", emptyMap(), userPhrases))
    }
    @Test fun `only an exact duplicate collides, a longer phrase does not`() {
        val taken = mapOf(VoicePhrase.normalize("навигатор") to "Карта")
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("открой навигатор", taken))
    }
    @Test fun `another inflection of a taken phrase does not collide`() {
        val taken = mapOf(VoicePhrase.normalize("открой окна") to "Проветрить")
        assertEquals(VoiceTriggerValidation.Collision.None, VoiceTriggerValidation.check("открой окно", taken))
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("открой окно", emptyMap(), mapOf(VoicePhrase.normalize("открой окна") to "Окна")))
        assertEquals(VoiceTriggerValidation.Collision.OtherRule("Проветрить"),
            VoiceTriggerValidation.check("Открой, окна!", taken))
    }
    @Test fun `fresh custom phrase is None`() {
        assertEquals(VoiceTriggerValidation.Collision.None,
            VoiceTriggerValidation.check("поехали", emptyMap()))
    }
}
