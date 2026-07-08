package com.bydmate.app.voice

import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.TriggerDef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second command resolver (after the built-in NluParser): maps a recognized
 * transcript to a user automation whose voice-trigger phrase matches. Also exposes
 * the phrases so they can be merged into the Vosk grammar (vocabulary source seam).
 */
@Singleton
class VoiceAutomationResolver @Inject constructor(
    private val ruleDao: RuleDao,
) {
    suspend fun phrases(): List<String> =
        ruleDao.getEnabled().flatMap { rule ->
            TriggerDef.listFromJson(rule.triggers)
                .filter { it.kind == "voice" }
                .map { it.value }
        }.filter { it.isNotBlank() }

    suspend fun match(transcript: String): Long? {
        val norm = VoicePhrase.normalize(transcript)
        if (norm.isBlank()) return null
        for (rule in ruleDao.getEnabled()) {
            val hit = TriggerDef.listFromJson(rule.triggers).any {
                it.kind == "voice" && VoicePhrase.normalize(it.value) == norm
            }
            if (hit) return rule.id
        }
        return null
    }
}
