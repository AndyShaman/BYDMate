package com.bydmate.app.voice

/**
 * Pure collision check for a candidate voice-trigger phrase.
 * Used by the automation editor before saving a rule.
 */
object VoiceTriggerValidation {
    sealed interface Collision {
        data object None : Collision
        data object Empty : Collision
        data object BuiltIn : Collision
        data object OtherRule : Collision
    }

    /**
     * @param phrase user-entered phrase
     * @param otherVoicePhrases normalized voice phrases already used by OTHER rules
     */
    fun check(phrase: String, otherVoicePhrases: Set<String>): Collision {
        val norm = VoicePhrase.normalize(phrase)
        if (norm.isBlank()) return Collision.Empty
        // Built-in resolver runs first: if NluParser claims the phrase in either
        // language, the automation would be shadowed. Block it.
        val builtIn = NluParser.parse(phrase, VoiceLang.RU) is ParseResult.Command ||
            NluParser.parse(phrase, VoiceLang.EN) is ParseResult.Command
        if (builtIn) return Collision.BuiltIn
        if (norm in otherVoicePhrases) return Collision.OtherRule
        return Collision.None
    }
}
