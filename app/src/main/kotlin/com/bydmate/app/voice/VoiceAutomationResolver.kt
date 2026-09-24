package com.bydmate.app.voice

import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.TriggerDef
import javax.inject.Inject
import javax.inject.Singleton

/** A user automation picked by its voice-trigger phrase. */
data class VoiceAutomationMatch(val ruleId: Long, val ruleName: String)

/**
 * Maps a recognized transcript to a user automation whose voice-trigger phrase is the whole
 * utterance, word for word after normalization ([VoicePhrase.isExact]). A match outranks every
 * other resolver (see VoiceController.resolve).
 */
@Singleton
class VoiceAutomationResolver @Inject constructor(
    private val ruleDao: RuleDao,
) {
    /** The first enabled rule whose voice phrase is the whole of [transcript]. */
    suspend fun match(transcript: String): VoiceAutomationMatch? =
        ruleDao.getEnabled().firstOrNull { rule ->
            TriggerDef.listFromJson(rule.triggers).any { it.kind == "voice" && VoicePhrase.isExact(transcript, it.value) }
        }?.let { VoiceAutomationMatch(it.id, it.name) }
}
