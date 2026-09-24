package com.bydmate.app.voice

import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.TriggerDef
import javax.inject.Inject
import javax.inject.Singleton

/** A user automation picked by its voice-trigger phrase; [exact] when the phrase is the whole
 *  utterance after normalization, not just a part of it. */
data class VoiceAutomationMatch(val ruleId: Long, val ruleName: String, val exact: Boolean = true)

/**
 * Maps a recognized transcript to a user automation whose voice-trigger phrase occurs in it as
 * a whole-word sequence after normalization. An exact match outranks every other resolver, a
 * contained one yields to the built-in NluParser (see VoiceController.resolve).
 */
@Singleton
class VoiceAutomationResolver @Inject constructor(
    private val ruleDao: RuleDao,
) {
    /** The longest matching phrase wins, so «открой окна дома» picks the rule for «окна дома»
     *  over the one for «окна»; on a tie an exact phrase wins, then the first enabled rule.
     *  Exact means word for word without stemming (see [VoicePhrase.isExact]). */
    suspend fun match(transcript: String): VoiceAutomationMatch? {
        val heard = VoicePhrase.tokens(transcript)
        if (heard.isEmpty()) return null
        var best: VoiceAutomationMatch? = null
        var bestLen = 0
        for (rule in ruleDao.getEnabled()) {
            for (t in TriggerDef.listFromJson(rule.triggers).filter { it.kind == "voice" }) {
                val phrase = VoicePhrase.tokens(t.value)
                if (phrase.size < bestLen || !VoicePhrase.containsSequence(heard, phrase)) continue
                val exact = VoicePhrase.isExact(transcript, t.value)
                if (phrase.size > bestLen || exact && best?.exact == false) {
                    best = VoiceAutomationMatch(rule.id, rule.name, exact)
                    bestLen = phrase.size
                }
            }
        }
        return best
    }
}
