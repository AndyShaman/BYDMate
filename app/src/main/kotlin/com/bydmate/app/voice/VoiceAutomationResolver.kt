package com.bydmate.app.voice

import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.TriggerDef
import javax.inject.Inject
import javax.inject.Singleton

/** A user automation picked by its voice-trigger phrase. */
data class VoiceAutomationMatch(val ruleId: Long, val ruleName: String)

/**
 * First command resolver (before the user's own command phrases and the built-in NluParser,
 * see VoiceController.resolve): maps a recognized transcript to a user automation whose
 * voice-trigger phrase occurs in it as a whole-word sequence after normalization.
 */
@Singleton
class VoiceAutomationResolver @Inject constructor(
    private val ruleDao: RuleDao,
) {
    /** The longest matching phrase wins, so «открой окна дома» picks the rule for «окна дома»
     *  over the one for «окна»; on a tie the first enabled rule wins. */
    suspend fun match(transcript: String): VoiceAutomationMatch? {
        val heard = VoicePhrase.tokens(transcript)
        if (heard.isEmpty()) return null
        var best: VoiceAutomationMatch? = null
        var bestLen = 0
        for (rule in ruleDao.getEnabled()) {
            for (t in TriggerDef.listFromJson(rule.triggers)) {
                if (t.kind != "voice") continue
                val phrase = VoicePhrase.tokens(t.value)
                if (phrase.size > bestLen && VoicePhrase.containsSequence(heard, phrase)) {
                    best = VoiceAutomationMatch(rule.id, rule.name)
                    bestLen = phrase.size
                }
            }
        }
        return best
    }
}
