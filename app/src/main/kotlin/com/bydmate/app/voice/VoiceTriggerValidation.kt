package com.bydmate.app.voice

/**
 * Pure collision check for a candidate voice-trigger phrase.
 * Used by the automation editor before saving a rule.
 */
object VoiceTriggerValidation {
    sealed interface Collision {
        data object None : Collision
        data object Empty : Collision
        /** The same phrase is the user's own phrase for the built-in command [command]. */
        data class UserCommandPhrase(val command: String) : Collision
        /** The same phrase triggers the automation [rule]. */
        data class OtherRule(val rule: String) : Collision
    }

    /**
     * Automations are resolved before built-in commands, so a phrase the built-in parser also
     * understands is allowed: the automation takes it over. Only an exact duplicate is refused.
     *
     * @param phrase user-entered phrase
     * @param otherVoicePhrases normalized voice phrases of OTHER rules → rule name
     * @param userCommandPhrases normalized user phrases of built-in commands → command name
     */
    fun check(
        phrase: String,
        otherVoicePhrases: Map<String, String>,
        userCommandPhrases: Map<String, String> = emptyMap(),
    ): Collision {
        val norm = VoicePhrase.normalize(phrase)
        if (norm.isBlank()) return Collision.Empty
        otherVoicePhrases[norm]?.let { return Collision.OtherRule(it) }
        userCommandPhrases[norm]?.let { return Collision.UserCommandPhrase(it) }
        return Collision.None
    }
}
